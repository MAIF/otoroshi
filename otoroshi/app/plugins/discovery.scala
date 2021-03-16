package otoroshi.plugins.discovery

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.models.Target
import otoroshi.script._
import otoroshi.security.IdGenerator
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}

import java.util.concurrent.TimeUnit
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}

case class SelfRegistrationConfig(raw: JsValue) {
  lazy val hosts: Seq[String] = raw.select("hosts").asOpt[Seq[String]].getOrElse(Seq.empty)
  lazy val targetTemplate: JsObject = raw.select("targetTemplate").asOpt[JsObject].getOrElse(Json.obj())
  lazy val registrationTtl: FiniteDuration = raw.select("registrationTtl").asOpt[Long].map(_.millis).getOrElse(60.seconds)
}

object SelfRegistrationConfig {
  val configName: String = "DiscoverySelfRegistration"
  def from(ctx: ContextWithConfig): SelfRegistrationConfig = {
    SelfRegistrationConfig(ctx.configFor(configName))
  }
}

object DiscoveryHelper {

  def register(serviceIdOpt: Option[String], body: Source[ByteString, _], config: SelfRegistrationConfig)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    implicit val mat = env.otoroshiMaterializer
    body.runFold(ByteString.empty)(_ ++ _).flatMap { bodyRaw =>

      val json = bodyRaw.utf8String.parseJson.asObject
      val serviceId = json.select("serviceId").asOpt[String].orElse(serviceIdOpt).get
      val rawTarget = config.targetTemplate.deepMerge(json)
      val target = Target.format.reads(rawTarget).get

      registerTarget(serviceId, target, config).map { registrationId =>
        Results.Ok(Json.obj("registrationId" -> registrationId, "serviceId" -> serviceId))
      }
    }
  }

  def unregister(registrationId: String, serviceId: Option[String], req: RequestHeader, config: SelfRegistrationConfig)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    (serviceId match {
      case Some(sid) => unregisterTarget(sid, Target("--"), registrationId, config)
      case None      => {
        env.datastores.rawDataStore.allMatching(s"${env.storageRoot}:service-discovery:registrations:*:$registrationId").flatMap { items =>
          Future.sequence(items.map { item =>
            val sid = item.utf8String.parseJson.select("serviceId").asString
            env.datastores.rawDataStore.del(Seq(s"${env.storageRoot}:service-discovery:registrations:$sid:$registrationId"))
          }).map(_ => true)
        }
      }
    }).map { _ =>
      Results.Ok(Json.obj("done" -> true))
    }
  }

  def heartbeat(registrationId: String, serviceId: Option[String], req: RequestHeader, config: SelfRegistrationConfig)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    (serviceId match {
      case Some(sid) => env.datastores.rawDataStore.pexpire(s"${env.storageRoot}:service-discovery:registrations:$sid:$registrationId", config.registrationTtl.toMillis)
      case None      => {
        env.datastores.rawDataStore.allMatching(s"${env.storageRoot}:service-discovery:registrations:*:$registrationId").flatMap { items =>
          Future.sequence(items.map { item =>
            val sid = item.utf8String.parseJson.select("serviceId").asString
            env.datastores.rawDataStore.pexpire(s"${env.storageRoot}:service-discovery:registrations:$sid:$registrationId", config.registrationTtl.toMillis)
          }).map(_ => true)
        }
      }
    }).map { _ =>
      Results.Ok(Json.obj("done" -> true))
    }
  }

  def getTargetsFor(serviceId: String, config: SelfRegistrationConfig)(implicit env: Env, ec: ExecutionContext): Future[Seq[(DiscoveryJobRegistrationId, Target)]] = {
    env.datastores.rawDataStore.allMatching(s"${env.storageRoot}:service-discovery:registrations:$serviceId:*").map { items =>
      items.map { item =>
        val jsonTarget = item.utf8String.parseJson.asObject
        val registrationId = jsonTarget.select("registrationId").asString
        val json = config.targetTemplate.deepMerge(jsonTarget)
        val target = Target.format.reads(json).get
        (DiscoveryJobRegistrationId(registrationId), target)
      }
    }
  }

  def getAllTargets(config: SelfRegistrationConfig)(implicit env: Env, ec: ExecutionContext): Future[Map[DiscoveryJobServiceId, Seq[(DiscoveryJobRegistrationId, Target)]]] = {
    env.datastores.rawDataStore.allMatching(s"${env.storageRoot}:service-discovery:registrations:*").map { items =>
      val targets = items.map { item =>
        val jsonTarget = item.utf8String.parseJson.asObject
        val serviceId = jsonTarget.select("serviceId").asString
        val registrationId = jsonTarget.select("registrationId").asString
        val json = config.targetTemplate.deepMerge(jsonTarget)
        val target = Target.format.reads(json).get
        (serviceId, registrationId, target)
      }
      targets.groupBy(_._1).map {
        case (key, v) => (DiscoveryJobServiceId(key), v.map(tuple => (DiscoveryJobRegistrationId(tuple._1), tuple._3)))
      }
    }
  }

  def unregisterTarget(id: String, target: Target, registrationId: String, config: SelfRegistrationConfig)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val key = s"${env.storageRoot}:service-discovery:registrations:$id:$registrationId"
    env.datastores.rawDataStore.del(Seq(key)).map(_ => ())
  }

  def registerTarget(id: String, target: Target, config: SelfRegistrationConfig)(implicit env: Env, ec: ExecutionContext): Future[String] = {
    val registrationId = "registration_" + IdGenerator.uuid
    val json = Json.obj(
      "serviceId" -> id,
      "registrationId" -> registrationId,
      "host"       -> target.host,
      "scheme"     -> target.scheme,
      "ipAddress"  -> target.ipAddress.map(JsString.apply).getOrElse(JsNull).as[JsValue]
    )
    env.datastores.rawDataStore.set(s"${env.storageRoot}:service-discovery:registrations:$id:$registrationId", json.stringify.byteString, config.registrationTtl.toMillis.some).map { _ =>
      registrationId
    }
  }

  def registerTargets(id: String, targets: Seq[Target], config: SelfRegistrationConfig)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    Future.sequence(targets.map(t => registerTarget(id, t, config))).map(_ => ())
  }
}

class DiscoverySelfRegistrationSink extends RequestSink {

  import kaleidoscope._


  override def name: String = "Global self registration endpoints (service discovery)"

  override def defaultConfig: Option[JsObject] = {
    Some(
      Json.obj(
        SelfRegistrationConfig.configName -> Json.obj(
          "hosts"        -> Json.arr(),
          "targetTemplate"  -> Json.obj(),
          "registrationTtl" -> 60000
        )
      )
    )
  }

  override def description: Option[String] = {
    Some(
      s"""This plugin add support for self registration endpoint on specific hostnames.
         |
         |This plugin accepts the following configuration:
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
      """.stripMargin
    )
  }

  override def matches(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Boolean = {
    val config = SelfRegistrationConfig.from(ctx)
    config.hosts.contains(ctx.request.theDomain)
  }

  override def handle(ctx: RequestSinkContext)(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    val config = SelfRegistrationConfig.from(ctx)
    (ctx.request.method.toLowerCase(), ctx.request.thePath) match {
      case ("post",   "/discovery/_register")                             => DiscoveryHelper.register(None, ctx.body, config)
      case ("delete", r"/discovery/${registrationId}@(.*)/_unregister")   => DiscoveryHelper.unregister(registrationId, None, ctx.request, config)
      case ("post",   r"/discovery/${registrationId}@(.*)/_heartbeat")    => DiscoveryHelper.heartbeat(registrationId,  None, ctx.request, config)
      case _ => Results.NotFound(Json.obj("error" -> "resource not found !")).future
    }
  }
}

class DiscoverySelfRegistrationTransformer extends RequestTransformer {

  import kaleidoscope._

  private val awaitingRequests = new TrieMap[String, Promise[Source[ByteString, _]]]()

  override def name: String = "Self registration endpoints (service discovery)"

  override def defaultConfig: Option[JsObject] = {
    Some(
      Json.obj(
        SelfRegistrationConfig.configName -> Json.obj(
          "hosts"        -> Json.arr(),
          "targetTemplate"  -> Json.obj(),
          "registrationTtl" -> 60000
        )
      )
    )
  }

  override def description: Option[String] = {
    Some(
      s"""This plugin add support for self registration endpoint on a specific service.
         |
         |This plugin accepts the following configuration:
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
      """.stripMargin
    )
  }

  override def beforeRequest(
    ctx: BeforeRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    awaitingRequests.putIfAbsent(ctx.snowflake, Promise[Source[ByteString, _]])
    funit
  }

  override def afterRequest(
    ctx: AfterRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Unit] = {
    awaitingRequests.remove(ctx.snowflake)
    funit
  }

  override def transformRequestBodyWithCtx(
    ctx: TransformerRequestBodyContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Source[ByteString, _] = {
    awaitingRequests.get(ctx.snowflake).map(_.trySuccess(ctx.body))
    ctx.body
  }

  override def transformRequestWithCtx(ctx: TransformerRequestContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpRequest]] = {
    val config = SelfRegistrationConfig.from(ctx)
    (ctx.request.method.toLowerCase(), ctx.request.thePath) match {
      case ("post",   "/discovery/_register")                             => {
        awaitingRequests.get(ctx.snowflake).map { promise =>
          val bodySource: Source[ByteString, _] = Source
            .future(promise.future)
            .flatMapConcat(s => s)
          DiscoveryHelper.register(ctx.descriptor.id.some, bodySource, config).map(r => Left(r))
        } getOrElse {
          // no body
          Results.BadRequest(Json.obj("error" -> "bad_request", "error_description" -> s"no body found !")).leftf
        }
      }
      case ("delete", r"/discovery/${registrationId}@(.*)/_unregister")   => DiscoveryHelper.unregister(registrationId, ctx.descriptor.id.some, ctx.request, config).map(r => Left(r))
      case ("post",   r"/discovery/${registrationId}@(.*)/_heartbeat")    => DiscoveryHelper.heartbeat(registrationId, ctx.descriptor.id.some, ctx.request, config).map(r => Left(r))
      case _ => Right(ctx.otoroshiRequest).future
    }
  }
}

class DiscoveryTargetsSelector extends PreRouting {

  override def name: String = "Service discovery target selector (service discovery)"

  override def defaultConfig: Option[JsObject] = {
    Some(
      Json.obj(
        SelfRegistrationConfig.configName -> Json.obj(
          "hosts"        -> Json.arr(),
          "targetTemplate"  -> Json.obj(),
          "registrationTtl" -> 60000
        )
      )
    )
  }

  override def description: Option[String] = {
    Some(
      s"""This plugin select a target in the pool of discovered targets for this service.
         |Use in combination with either `DiscoverySelfRegistrationSink` or `DiscoverySelfRegistrationTransformer` to make it work using the `self registration` pattern.
         |Or use an implementation of `DiscoveryJob` for the `third party registration pattern`.
         |
         |This plugin accepts the following configuration:
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
      """.stripMargin
    )
  }

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val config = SelfRegistrationConfig.from(ctx)
    DiscoveryHelper.getTargetsFor(ctx.descriptor.id, config).map {
      case targets if targets.isEmpty => ()
      case _targets => {
        val reqNumber = ctx.attrs.get(otoroshi.plugins.Keys.RequestNumberKey).getOrElse(0)
        val trackingId = ctx.attrs.get(otoroshi.plugins.Keys.RequestTrackingIdKey).getOrElse("none")
        val targets: Seq[Target] = _targets.map(_._2)
          .filter(_.predicate.matches(reqNumber.toString, ctx.request, ctx.attrs))
          .flatMap(t => Seq.fill(t.weight)(t))
        val target = ctx.descriptor.targetsLoadBalancing
          .select(
            reqNumber.toString,
            trackingId,
            ctx.request,
            targets,
            ctx.descriptor
          )
        ctx.attrs.put(otoroshi.plugins.Keys.PreExtractedRequestTargetKey -> target)
      }
    }
  }
}

case class DiscoveryJobServiceId(id: String)
case class DiscoveryJobRegistrationId(id: String)
trait DiscoveryJob extends Job {

  override def visibility: JobVisibility                                       = JobVisibility.UserLand
  override def kind: JobKind                                                   = JobKind.Autonomous
  override def starting: JobStarting                                           = JobStarting.Automatically
  override def instantiation(ctx: JobContext, env: Env): JobInstantiation      = JobInstantiation.OneInstancePerOtoroshiCluster
  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = Some(FiniteDuration(10, TimeUnit.SECONDS))

  def fetchAllTargets(ctx: JobContext, config: SelfRegistrationConfig)(implicit env: Env, ec: ExecutionContext): Future[Map[DiscoveryJobServiceId, Seq[(DiscoveryJobRegistrationId, Target)]]]

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val config = SelfRegistrationConfig.from(ctx)
    for {
      allTargets <- DiscoveryHelper.getAllTargets(config)
      newTargets <- fetchAllTargets(ctx, config)
    } yield {
      allTargets.foreach {
        case (did @ DiscoveryJobServiceId(id), targets) => targets.foreach {
          case (drid @ DiscoveryJobRegistrationId(rid), target) => {
            val newts = newTargets.getOrElse(did, Seq.empty)
            if (!newts.contains((drid, target))) {
              DiscoveryHelper.unregisterTarget(id, target, rid, config)
            }
          }
        }
      }
      newTargets.map {
        case (DiscoveryJobServiceId(id), targets) => DiscoveryHelper.registerTargets(id, targets.map(_._2), config)
      }
    }
  }
}
