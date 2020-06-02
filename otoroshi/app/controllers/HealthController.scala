package controllers

import actions.{ApiAction, UnAuthApiAction}
import akka.http.scaladsl.util.FastFuture
import cluster.{ClusterMode, MemberView}
import env.Env
import otoroshi.storage.{Healthy, Unhealthy, Unreachable}
import play.api.Logger
import play.api.libs.json.{JsObject, JsString, JsValue, Json}
import play.api.mvc.{AbstractController, ControllerComponents, Result}
import ssl.DynamicSSLEngineProvider

class HealthController(cc: ControllerComponents)(implicit env: Env)
  extends AbstractController(cc) {

  implicit lazy val ec  = env.otoroshiExecutionContext
  implicit lazy val mat = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-health-api")

  def processMetrics() = Action.async { req =>
    val format = req.getQueryString("format")

    def transformToArray(input: String): JsValue = {
      val metrics = Json.parse(input)
      metrics.as[JsObject].value.toSeq.foldLeft(Json.arr()) {
        case (arr, (key, JsObject(value))) =>
          arr ++ value.toSeq.foldLeft(Json.arr()) {
            case (arr2, (key2, value2 @ JsObject(_))) =>
              arr2 ++ Json.arr(value2 ++ Json.obj("name" -> key2, "type" -> key))
            case (arr2, (key2, value2)) =>
              arr2
          }
        case (arr, (key, value)) => arr
      }
    }

    def fetchMetrics(): Result = {
      if (format.contains("old_json") || format.contains("old")) {
        Ok(env.metrics.jsonExport(None)).as("application/json")
      } else if (format.contains("json")) {
        Ok(transformToArray(env.metrics.jsonExport(None))).as("application/json")
      } else if (format.contains("prometheus") || format.contains("prom")) {
        Ok(env.metrics.prometheusExport(None)).as("text/plain")
      } else if (req.accepts("application/json")) {
        Ok(transformToArray(env.metrics.jsonExport(None))).as("application/json")
      } else if (req.accepts("application/prometheus")) {
        Ok(env.metrics.prometheusExport(None)).as("text/plain")
      } else {
        Ok(transformToArray(env.metrics.jsonExport(None))).as("application/json")
      }
    }

    if (env.metricsEnabled) {
      FastFuture.successful(
        ((req.getQueryString("access_key"), req.getQueryString("X-Access-Key"), env.metricsAccessKey) match {
          case (_, _, None)                                  => fetchMetrics()
          case (Some(header), _, Some(key)) if header == key => fetchMetrics()
          case (_, Some(header), Some(key)) if header == key => fetchMetrics()
          case _                                             => Unauthorized(Json.obj("error" -> "unauthorized"))
        }) withHeaders (
          env.Headers.OtoroshiStateResp -> req.headers
            .get(env.Headers.OtoroshiState)
            .getOrElse("--")
          )
      )
    } else {
      FastFuture.successful(NotFound(Json.obj("error" -> "metrics not enabled")))
    }
  }

  def health() = Action.async { req =>
    def fetchHealth() = {
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
      } yield {
        val cluster = env.clusterConfig.mode match {
          case ClusterMode.Off => Json.obj()
          case ClusterMode.Worker =>
            Json.obj("cluster" -> Json.obj("health" -> "healthy", "lastSync" -> env.clusterAgent.lastSync.toString()))
          case ClusterMode.Leader => {
            val healths     = members.map(_.health)
            val foundOrange = healths.contains("orange")
            val foundRed    = healths.contains("red")
            val health      = if (foundRed) "unhealthy" else (if (foundOrange) "notthathealthy" else "healthy")
            Json.obj("cluster" -> Json.obj("health" -> health))
          }
        }
        val certificates = DynamicSSLEngineProvider.isFirstSetupDone match {
          case true  => "loaded"
          case false => "loading"
        }
        val payload = Json.obj(
          "otoroshi" -> JsString(_health match {
            case Healthy if overhead <= env.healthLimit => "healthy"
            case Healthy if overhead > env.healthLimit  => "unhealthy"
            case Unhealthy                              => "unhealthy"
            case Unreachable                            => "down"
          }),
          "datastore" -> JsString(_health match {
            case Healthy     => "healthy"
            case Unhealthy   => "unhealthy"
            case Unreachable => "unreachable"
          }),
          "certificates" -> certificates,
          "scripts"      -> scripts
        ) ++ cluster
        val err = (payload \ "otoroshi").asOpt[String].exists(_ != "healthy") ||
          (payload \ "datastore").asOpt[String].exists(_ != "healthy") ||
          (payload \ "cluster").asOpt[String].orElse(Some("healthy")).exists(v => v != "healthy")
        if (err) {
          InternalServerError(payload)
        } else {
          Ok(payload)
        }
      }
    }

    ((req.getQueryString("access_key"), req.getQueryString("X-Access-Key"), env.healthAccessKey) match {
      case (_, _, None)                                  => fetchHealth()
      case (Some(header), _, Some(key)) if header == key => fetchHealth()
      case (_, Some(header), Some(key)) if header == key => fetchHealth()
      case _                                             => FastFuture.successful(Unauthorized(Json.obj("error" -> "unauthorized")))
    }) map { res =>
      res.withHeaders(
        env.Headers.OtoroshiStateResp -> req.headers
          .get(env.Headers.OtoroshiState)
          .getOrElse("--")
      )
    }
  }
}
