package otoroshi.storage.stores

import akka.http.scaladsl.util.FastFuture
import env.Env
import models.{ChaosDataStore, Outage, ServiceDescriptor, SnowMonkeyConfig}
import org.joda.time.DateTime
import play.api.libs.json.{JsSuccess, Json}
import otoroshi.storage.RedisLike

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

class KvChaosDataStore(redisCli: RedisLike, _env: Env) extends ChaosDataStore {

  override def serviceAlreadyOutage(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    redisCli.get(s"${env.storageRoot}:outage:bydesc:until:$serviceId").map(_.isDefined)
  }

  override def serviceOutages(serviceId: String)(implicit ec: ExecutionContext, env: Env): Future[Int] = {
    redisCli.get(s"${env.storageRoot}:outage:bydesc:counter:$serviceId").map(_.map(_.utf8String.toInt).getOrElse(0))
  }

  override def groupOutages(groupId: String)(implicit ec: ExecutionContext, env: Env): Future[Int] = {
    redisCli.get(s"${env.storageRoot}:outage:bygroup:counter:$groupId").map(_.map(_.utf8String.toInt).getOrElse(0))
  }

  override def registerOutage(
      descriptor: ServiceDescriptor,
      conf: SnowMonkeyConfig
  )(implicit ec: ExecutionContext, env: Env): Future[FiniteDuration] = {
    val dayEnd = DateTime.now().millisOfDay().withMaximumValue().getMillis - System.currentTimeMillis()
    val bound =
      if (conf.outageDurationTo.toMillis.toInt == conf.outageDurationFrom.toMillis.toInt)
        conf.outageDurationFrom.toMillis.toInt
      else (conf.outageDurationTo.toMillis.toInt - conf.outageDurationFrom.toMillis.toInt)
    val outageDuration    = (conf.outageDurationFrom.toMillis + new scala.util.Random().nextInt(bound)).millis
    val serviceUntilKey   = s"${env.storageRoot}:outage:bydesc:until:${descriptor.id}" // until end of duration
    val serviceCounterKey = s"${env.storageRoot}:outage:bydesc:counter:${descriptor.id}" // until end of day
    val groupCounterKey   = s"${env.storageRoot}:outage:bygroup:counter:${descriptor.groupId}" // until end of day
    for {
      _ <- redisCli.incr(groupCounterKey)
      _ <- redisCli.incr(serviceCounterKey)
      _ <- redisCli.set(
            serviceUntilKey,
            Json.stringify(
              Json.obj(
                "descriptorName" -> descriptor.name,
                "descriptorId"   -> descriptor.id,
                "until"          -> DateTime.now().plusMillis(outageDuration.toMillis.toInt).toLocalTime.toString,
                "duration"       -> outageDuration.toMillis,
                "startedAt"      -> DateTime.now().toString()
              )
            ),
            pxMilliseconds = Some(outageDuration.toMillis)
          )
      _ <- redisCli.pexpire(serviceCounterKey, dayEnd)
      _ <- redisCli.pexpire(groupCounterKey, dayEnd)
    } yield outageDuration
  }

  override def resetOutages()(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    for {
      uKeys <- redisCli.keys(s"${env.storageRoot}:outage:bydesc:until:*")
      sKeys <- redisCli.keys(s"${env.storageRoot}:outage:bydesc:counter:*")
      gKeys <- redisCli.keys(s"${env.storageRoot}:outage:bygroup:counter:*")
      _     <- redisCli.del((Seq.empty ++ uKeys ++ sKeys ++ gKeys): _*)
    } yield ()
  }

  override def startSnowMonkey()(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    for {
      _ <- env.datastores.chaosDataStore.resetOutages()
      c <- env.datastores.globalConfigDataStore.singleton()
      _ <- c.copy(snowMonkeyConfig = c.snowMonkeyConfig.copy(enabled = true)).save()
    } yield ()
  }

  override def stopSnowMonkey()(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    for {
      _ <- env.datastores.chaosDataStore.resetOutages()
      c <- env.datastores.globalConfigDataStore.singleton()
      _ <- c.copy(snowMonkeyConfig = c.snowMonkeyConfig.copy(enabled = false)).save()
    } yield ()
  }

  override def getOutages()(implicit ec: ExecutionContext, env: Env): Future[Seq[Outage]] = {
    for {
      keys        <- redisCli.keys(s"${env.storageRoot}:outage:bydesc:until:*")
      outagesBS   <- if (keys.isEmpty) FastFuture.successful(Seq.empty) else redisCli.mget(keys: _*)
      outagesJson = outagesBS.filter(_.isDefined).map(_.get).map(v => v.utf8String)
      outages = outagesJson.map(v => Outage.fmt.reads(Json.parse(v))).collect {
        case JsSuccess(i, _) => i
      }
    } yield outages
  }
}
