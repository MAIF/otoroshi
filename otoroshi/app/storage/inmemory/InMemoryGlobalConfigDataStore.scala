package storage.inmemory

import env.Env
import models._
import play.api.libs.json._
import play.api.libs.json.JodaReads._
import play.api.libs.json.JodaWrites._
import storage.{RedisLike, RedisLikeStore}
import utils.LocalCache
import com.typesafe.config.ConfigRenderOptions
import org.joda.time.DateTime
import play.api.Logger
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.Success

class InMemoryGlobalConfigDataStore(redisCli: RedisLike, _env: Env)
    extends GlobalConfigDataStore
    with RedisLikeStore[GlobalConfig] {

  lazy val logger = Logger("otoroshi-in-memory-globalconfig-datastore")

  override def fmt: Format[GlobalConfig] = GlobalConfig._fmt

  override def key(id: String): Key =
    Key.Empty / _env.storageRoot / "config" / "global" // WARN : its a singleton, id is always global

  override def extractId(value: GlobalConfig): String = "global" // WARN : its a singleton, id is always global

  override def redisLike(implicit env: Env): RedisLike = redisCli

  def throttlingKey(): String = s"${_env.storageRoot}:throttling:global"

  private val callsForIpAddressCache =
    new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.atomic.AtomicLong]()
  private val quotasForIpAddressCache =
    new java.util.concurrent.ConcurrentHashMap[String, java.util.concurrent.atomic.AtomicLong]()

  def incrementCallsForIpAddressWithTTL(ipAddress: String,
                                        ttl: Int = 10)(implicit ec: ExecutionContext): Future[Long] = {

    @inline
    def actualCall() = redisCli.incrby(s"${_env.storageRoot}:throttling:perip:$ipAddress", 1L).flatMap { secCalls =>
      if (!callsForIpAddressCache.containsKey(ipAddress)) {
        callsForIpAddressCache.putIfAbsent(ipAddress, new java.util.concurrent.atomic.AtomicLong(secCalls))
      } else {
        callsForIpAddressCache.get(ipAddress).set(secCalls)
      }
      redisCli.pttl(s"${_env.storageRoot}:throttling:perip:$ipAddress").filter(_ > -1).recoverWith {
        case _ => redisCli.expire(s"${_env.storageRoot}:throttling:perip:$ipAddress", ttl)
      } map (_ => secCalls)
    }

    if (callsForIpAddressCache.containsKey(ipAddress)) {
      actualCall()
      FastFuture.successful(callsForIpAddressCache.get(ipAddress).get)
    } else {
      actualCall()
    }
  }

  def quotaForIpAddress(ipAddress: String)(implicit ec: ExecutionContext): Future[Option[Long]] = {
    @inline
    def actualCall() =
      redisCli.get(s"${_env.storageRoot}:throttling:peripquota:$ipAddress").map(_.map(_.utf8String.toLong)).andThen {
        case Success(Some(quota)) if !quotasForIpAddressCache.containsKey(ipAddress) =>
          quotasForIpAddressCache.putIfAbsent(ipAddress, new java.util.concurrent.atomic.AtomicLong(quota))
        case Success(Some(quota)) if quotasForIpAddressCache.containsKey(ipAddress) =>
          quotasForIpAddressCache.get(ipAddress).set(quota)
      }
    quotasForIpAddressCache.containsKey(ipAddress) match {
      case true =>
        actualCall()
        FastFuture.successful(Some(quotasForIpAddressCache.get(ipAddress).get))
      case false => actualCall()
    }
  }

  override def isOtoroshiEmpty()(implicit ec: ExecutionContext): Future[Boolean] =
    redisCli.keys(s"${_env.storageRoot}:*").map(_.isEmpty)

  private val throttlingQuotasCache = new java.util.concurrent.atomic.AtomicLong(0L)

  override def withinThrottlingQuota()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    val config = latest()
    //singleton().map { config =>
    redisCli.get(throttlingKey()).map { bs =>
      throttlingQuotasCache.set(bs.map(_.utf8String.toLong).getOrElse(0L))
      throttlingQuotasCache.get() <= (config.throttlingQuota * 10L)
    }
    //}
  }
  // singleton().flatMap { config =>
  //   redisCli.get(throttlingKey()).map { bs =>
  //     val count = bs.map(_.utf8String.toLong).getOrElse(0L)
  //     count <= (config.throttlingQuota * 10L)
  //   }
  // }

  def quotasValidationFor(from: String)(implicit ec: ExecutionContext,
                                        env: Env): Future[(Boolean, Long, Option[Long])] = {
    val a = withinThrottlingQuota()
    val b = incrementCallsForIpAddressWithTTL(from)
    val c = quotaForIpAddress(from)
    for {
      within     <- a
      secCalls   <- b
      maybeQuota <- c
    } yield (within, secCalls, maybeQuota)
  }

  override def updateQuotas(config: models.GlobalConfig)(implicit ec: ExecutionContext, env: Env): Future[Unit] =
    for {
      secCalls <- redisCli.incrby(throttlingKey(), 1L)
      _        <- redisCli.ttl(throttlingKey()).filter(_ > -1).recoverWith { case _ => redisCli.expire(throttlingKey(), 10) }
      fu       = env.statsd.meter(s"global.throttling-quotas", secCalls.toDouble)(config.statsdConfig)
    } yield ()

  override def allEnv()(implicit ec: ExecutionContext, env: Env): Future[Set[String]] = singleton().map(_.lines.toSet)

  private val configCache     = new java.util.concurrent.atomic.AtomicReference[GlobalConfig](null)
  private val lastConfigCache = new java.util.concurrent.atomic.AtomicLong(0L)

  override def latest()(implicit ec: ExecutionContext, env: Env): GlobalConfig = {
    val ref = configCache.get()
    if (ref == null) {
      Await.result(singleton(), 1.second) // WARN: await here should never be executed
    } else {
      ref
    }
  }

  override def singleton()(implicit ec: ExecutionContext, env: Env): Future[GlobalConfig] = {
    val time = System.currentTimeMillis
    val ref  = configCache.get()

    @inline
    def actualCall() = findById("global").map(_.get).andThen {
      case Success(conf) =>
        lastConfigCache.set(time)
        configCache.set(conf)
    }

    if (ref == null) {
      lastConfigCache.set(time)
      logger.warn("Fetching GlobalConfig for the first time")
      actualCall()
    } else {
      if ((lastConfigCache.get() + 6000) < time) {
        lastConfigCache.set(time)
        actualCall()
      } else if ((lastConfigCache.get() + 5000) < time) {
        lastConfigCache.set(time)
        actualCall()
        FastFuture.successful(ref)
      } else {
        FastFuture.successful(ref)
      }
    }
  }

  override def fullImport(export: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    val config             = GlobalConfig.fromJsons((export \ "config").as[JsObject])
    val admins             = (export \ "admins").as[JsArray]
    val simpleAdmins       = (export \ "simpleAdmins").as[JsArray]
    val serviceGroups      = (export \ "serviceGroups").as[JsArray]
    val apiKeys            = (export \ "apiKeys").as[JsArray]
    val serviceDescriptors = (export \ "serviceDescriptors").as[JsArray]
    val errorTemplates     = (export \ "errorTemplates").as[JsArray]

    for {
      _ <- redisCli.flushall()
      _ <- config.save()
      _ <- Future.sequence(
            admins.value.map(
              v => redisCli.set(s"${env.storageRoot}:u2f:users:${(v \ "randomId").as[String]}", Json.stringify(v))
            )
          )
      _ <- Future.sequence(
            simpleAdmins.value.map(
              v => redisCli.set(s"${env.storageRoot}:admins:${(v \ "username").as[String]}", Json.stringify(v))
            )
          )
      _ <- Future.sequence(serviceGroups.value.map(ServiceGroup.fromJsons).map(_.save()))
      _ <- Future.sequence(apiKeys.value.map(ApiKey.fromJsons).map(_.save()))
      _ <- Future.sequence(serviceDescriptors.value.map(ServiceDescriptor.fromJsons).map(_.save()))
      _ <- Future.sequence(errorTemplates.value.map(ErrorTemplate.fromJsons).map(_.save()))
    } yield ()
  }

  override def fullExport()(implicit ec: ExecutionContext, env: Env): Future[JsValue] = {
    val appConfig =
      Json.parse(
        env.configuration
          .getOptional[play.api.Configuration]("app")
          .get
          .underlying
          .root()
          .render(ConfigRenderOptions.concise())
      )
    for {
      config       <- env.datastores.globalConfigDataStore.singleton()
      descs        <- env.datastores.serviceDescriptorDataStore.findAll()
      apikeys      <- env.datastores.apiKeyDataStore.findAll()
      groups       <- env.datastores.serviceGroupDataStore.findAll()
      tmplts       <- env.datastores.errorTemplateDataStore.findAll()
      calls        <- env.datastores.serviceDescriptorDataStore.globalCalls()
      dataIn       <- env.datastores.serviceDescriptorDataStore.globalDataIn()
      dataOut      <- env.datastores.serviceDescriptorDataStore.globalDataOut()
      admins       <- env.datastores.u2FAdminDataStore.findAll()
      simpleAdmins <- env.datastores.simpleAdminDataStore.findAll()
    } yield
      Json.obj(
        "label"   -> "Otoroshi export",
        "dateRaw" -> DateTime.now(),
        "date"    -> DateTime.now().toString("yyyy-MM-dd hh:mm:ss"),
        "stats" -> Json.obj(
          "calls"   -> calls,
          "dataIn"  -> dataIn,
          "dataOut" -> dataOut
        ),
        "config"             -> config.toJson,
        "appConfig"          -> appConfig,
        "admins"             -> JsArray(admins),
        "simpleAdmins"       -> JsArray(simpleAdmins),
        "serviceGroups"      -> JsArray(groups.map(_.toJson)),
        "apiKeys"            -> JsArray(apikeys.map(_.toJson)),
        "serviceDescriptors" -> JsArray(descs.map(_.toJson)),
        "errorTemplates"     -> JsArray(tmplts.map(_.toJson))
      )
  }
}
