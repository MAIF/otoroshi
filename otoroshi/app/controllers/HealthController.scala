package otoroshi.controllers

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.util.ByteString
import otoroshi.actions.{ApiAction, BackOfficeActionAuth, UnAuthApiAction}
import org.apache.pekko.http.scaladsl.util.FastFuture
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import otoroshi.cluster.{ClusterMode, MemberView}
import otoroshi.env.Env
import otoroshi.models.{ApiKey, BackOfficeUser, HSAlgoSettings, SecComInfoTokenVersion}
import otoroshi.storage.{Healthy, Unhealthy, Unreachable}
import play.api.Logger
import play.api.libs.json.{JsArray, JsObject, JsString, JsValue, Json}
import play.api.mvc.{AbstractController, ControllerComponents, EssentialAction, Headers, Request, RequestHeader, Result, Results}
import otoroshi.ssl.DynamicSSLEngineProvider
import otoroshi.utils.infotoken.InfoTokenHelper
import otoroshi.utils.syntax.implicits.*
import play.api.http.HttpRequestHandler
import play.api.libs.typedmap.TypedMap
import play.api.mvc.request.{RemoteConnection, RequestTarget}

import java.net.{InetAddress, URI, URLEncoder}
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContext, Future}

object HealthController {

  private def transformToArray(input: String): JsValue = {
    val metrics = Json.parse(input)
    metrics match {
      case JsObject(value) =>
        value.toSeq.foldLeft(Json.arr()) {
          case (arr, (key, JsObject(value))) =>
            arr ++ value.toSeq.foldLeft(Json.arr()) {
              case (arr2, (key2, value2 @ JsObject(_))) =>
                arr2 ++ Json.arr(
                  value2 ++ Json.obj(
                    "name" -> key2.applyOnWithPredicate(_.endsWith(" {}"))(_.replace(" {}", "")),
                    "type" -> key
                  )
                )
              case (arr2, (key2, value2))               => arr2
            }
          case (arr, (key, value))           => arr
        }
      case a               => a
    }
  }

  def fetchHealth()(using env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[JsValue, JsValue]] = {
    val handler: HttpRequestHandler = env.handlerRef.get()
    val membersF = if (env.clusterConfig.mode == ClusterMode.Leader) {
      env.datastores.clusterStateDataStore.getMembers()
    } else {
      FastFuture.successful(Seq.empty[MemberView])
    }
    for {
      _health  <- env.datastores.health()
      scripts  <- env.scriptManager.state()
      overhead <- env.datastores.serviceDescriptorDataStore.globalCallsOverhead()
      members  <- membersF
      engineRes <- {
        val request = new HealthRequest(env)
        val (nreq, reqHandler) = handler.handlerForRequest(request)
        reqHandler match {
          case a: EssentialAction => try {
            a.apply(nreq).run(ByteString.empty).map { _ =>
              Results.Ok("engine works")
            }.recover {
              case e: Throwable => Results.InternalServerError("engine is dead - 1")
            }
          } catch {
            case e: Throwable => Results.InternalServerError("engine is dead - 2").vfuture
          }
          case _ => Results.Ok("no websocket").vfuture
        }
      }
    } yield {
      val workerReady      =
        if (env.clusterConfig.mode == ClusterMode.Worker) !env.clusterAgent.cannotServeRequests() else true
      val workerReadyStr   = workerReady match {
        case true  => "loaded"
        case false => "loading"
      }
      val cluster          = env.clusterConfig.mode match {
        case ClusterMode.Off    => Json.obj()
        case ClusterMode.Worker =>
          Json.obj(
            "cluster" -> Json.obj(
              "status"   -> "healthy",
              "lastSync" -> env.clusterAgent.lastSync.toString(),
              "worker"   -> Json.obj(
                "status"      -> workerReadyStr,
                "initialized" -> workerReady
              )
            )
          )
        case ClusterMode.Leader => {
          val healths     = members.map(_.health)
          val foundOrange = healths.contains("orange")
          val foundRed    = healths.contains("red")
          val health      = if (foundRed) "unhealthy" else (if (foundOrange) "notthathealthy" else "healthy")
          Json.obj("cluster" -> Json.obj("health" -> health))
        }
      }
      val certificates     = DynamicSSLEngineProvider.isFirstSetupDone match {
        case true  => "loaded"
        case false => "loading"
      }
      val scriptsReady     = scripts.initialized match {
        case true  => "loaded"
        case false => "loading"
      }
      val otoroshiStatus   = JsString(_health match {
        case Healthy if overhead <= env.healthLimit => "healthy"
        case Healthy if overhead > env.healthLimit  => "unhealthy"
        case Healthy                                => "unhealthy"
        case Unhealthy                              => "unhealthy"
        case Unreachable                            => "down"
      })
      val dataStoreStatus  = JsString(_health match {
        case Healthy     => "healthy"
        case Unhealthy   => "unhealthy"
        case Unreachable => "unreachable"
      })
      val eventstoreStatus = if (otoroshi.jobs.updates.EventstoreCheckerJob.initialized.get()) {
        if (otoroshi.jobs.updates.EventstoreCheckerJob.works.get()) {
          JsString("healthy")
        } else {
          JsString("down")
        }
      } else {
        JsString("unknown")
      }
      val proxyStatus: String = engineRes.header.status match {
        case 200 => "healthy"
        case _ => "down"
      }
      val payload          = Json.obj(
        "otoroshi"     -> otoroshiStatus,
        "datastore"    -> dataStoreStatus,
        "proxy"        -> Json.obj(
          "initialized" -> true,
          "status"      -> proxyStatus
        ),
        "storage"      -> Json.obj(
          "initialized" -> true,
          "status"      -> dataStoreStatus
        ),
        "eventstore"   -> Json.obj(
          "initialized" -> otoroshi.jobs.updates.EventstoreCheckerJob.initialized.get(),
          "status"      -> eventstoreStatus
        ),
        "certificates" -> Json.obj(
          "initialized" -> DynamicSSLEngineProvider.isFirstSetupDone,
          "status"      -> certificates
        ),
        "scripts"      -> (scripts.json.as[JsObject] ++ Json.obj("status" -> scriptsReady))
      ) ++ cluster
      val err              = (payload \ "otoroshi").asOpt[String].exists(_ != "healthy") ||
        (payload \ "datastore").asOpt[String].exists(_ != "healthy") ||
        (payload \ "cluster").asOpt[String].orElse(Some("healthy")).exists(v => v != "healthy") ||
        engineRes.header.status != 200 ||
        !scripts.initialized ||
        !workerReady ||
        !DynamicSSLEngineProvider.isFirstSetupDone
      if (err) {
        Left(payload)
      } else {
        Right(payload)
      }
    }
  }

  def fetchMetrics(
      format: Option[String],
      acceptsJson: Boolean,
      acceptsProm: Boolean,
      filter: Option[String]
  )(using env: Env, ec: ExecutionContext): Result = {
    if (format.contains("old_json") || format.contains("old")) {
      Results.Ok(env.metrics.jsonExport(filter)).as("application/json")
    } else if (format.contains("json")) {
      Results.Ok(transformToArray(env.metrics.jsonExport(filter))).as("application/json")
    } else if (format.contains("prometheus") || format.contains("prom")) {
      Results.Ok(env.metrics.prometheusExport(filter)).as("text/plain")
    } else if (acceptsJson) {
      Results.Ok(transformToArray(env.metrics.jsonExport(filter))).as("application/json")
    } else if (acceptsProm) {
      Results.Ok(env.metrics.prometheusExport(filter)).as("text/plain")
    } else {
      Results.Ok(transformToArray(env.metrics.jsonExport(filter))).as("application/json")
    }
  }
}

class HealthController(cc: ControllerComponents, BackOfficeActionAuth: BackOfficeActionAuth)(using env: Env)
    extends AbstractController(cc) {

  implicit lazy val ec: scala.concurrent.ExecutionContext = env.otoroshiExecutionContext
  implicit lazy val mat: org.apache.pekko.stream.Materializer = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-health-api")

  def withSecurity(req: RequestHeader, _key: Option[String])(f: => Future[Result]): Future[Result] = {
    ((req.getQueryString("access_key"), req.getQueryString("X-Access-Key"), _key) match {
      case (_, _, None)                                  => f
      case (Some(header), _, Some(key)) if header == key => f
      case (_, Some(header), Some(key)) if header == key => f
      case _                                             => FastFuture.successful(Unauthorized(Json.obj("error" -> "unauthorized")))
    }) map { res =>
      res.withHeaders(
        env.Headers.OtoroshiStateResp -> req.headers
          .get(env.Headers.OtoroshiState)
          .getOrElse("--")
      )
    }
  }

  def fetchHealth() = {
    HealthController.fetchHealth().map {
      case Left(payload)  => ServiceUnavailable(payload)
      case Right(payload) => Ok(payload)
    }
  }

  def processMetrics() = Action.async { req =>
    val format      = req.getQueryString("format")
    val filter      = req.getQueryString("filter")
    val acceptsJson = req.accepts("application/json")
    val acceptsProm = req.accepts("application/prometheus")
    if (env.metricsEnabled) {
      withSecurity(req, env.metricsAccessKey)(
        HealthController.fetchMetrics(format, acceptsJson, acceptsProm, filter).future
      )
    } else {
      FastFuture.successful(NotFound(Json.obj("error" -> "metrics not enabled")))
    }
  }

  def backofficeMetrics() = BackOfficeActionAuth { (ctx: otoroshi.actions.BackOfficeActionContextAuth[play.api.mvc.AnyContent]) =>
    HealthController.fetchMetrics("json".some, true, false, None)
  }

  def health() =
    Action.async { req =>
      withSecurity(req, env.healthAccessKey)(fetchHealth())
    }

  def live() =
    Action.async { req =>
      withSecurity(req, env.healthAccessKey) {
        Ok(Json.obj("live" -> true)).future
      }
    }

  def ready() =
    Action.async { req =>
      withSecurity(req, env.healthAccessKey)(fetchHealth().map {
        case r if r.header.status == 200 => Ok(Json.obj("ready" -> true))
        case r                           => ServiceUnavailable(Json.obj("ready" -> false))
      })
    }

  def startup() =
    Action.async { req =>
      withSecurity(req, env.healthAccessKey)(fetchHealth().map {
        case r if r.header.status == 200 => Ok(Json.obj("started" -> true))
        case r                           => ServiceUnavailable(Json.obj("started" -> false))
      })
    }
}

class HealthRequest(
  env: Env
) extends Request[Source[ByteString, ?]] {

  private val newUri = s"http://127.0.0.1:${env.port}/otoroshi-engine-self-health-check"

  override def connection: RemoteConnection = new HealthRemoteConnection()
  override def target: RequestTarget        = new BackOfficeRequestTarget(newUri)
  override def headers: Headers             = Headers.apply(
    "Host" -> s"otoroshi-engine.self-health-check.local",
    "User-Agent" -> "Otoroshi-Engine-Self-Health-Check",
    "Accept" -> "*/*"
  )

  override def version: String             = "HTTP/1.1"
  override def attrs: TypedMap             = TypedMap.empty
  override def method: String              = "GET"
  override def body: Source[ByteString, ?] = Source.empty
}

class BackOfficeRequestTarget(newUri: String) extends RequestTarget {
  private val _uri                                = Uri(newUri)
  override def uri: URI                           = URI.create(newUri)
  override def uriString: String                  = _uri.toString()
  override def path: String                       = _uri.path.toString()
  override def queryMap: Map[String, Seq[String]] = _uri.query().toMultiMap
}

class HealthRemoteConnection() extends RemoteConnection {
  override def remoteAddress: InetAddress                           = InetAddress.getLocalHost
  override def clientCertificateChain: Option[Seq[X509Certificate]] = None
  override def secure: Boolean                                      = false
}

