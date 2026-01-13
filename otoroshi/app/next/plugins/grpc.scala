package otoroshi.next.plugins

import akka.stream.Materializer
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class GrpcWebConfig(
    allowServices: Seq[String] = Seq.empty,
    allowMethods: Seq[String] = Seq.empty,
    blockedMethods: Seq[String] = Seq.empty
) extends NgPluginConfig {
  override def json: JsValue = GrpcWebConfig.fmt.writes(this)
}

object GrpcWebConfig {
  val fmt = new Format[GrpcWebConfig] {
    override def writes(o: GrpcWebConfig): JsValue             =
      Json.obj(
        "allowed_services" -> o.allowServices,
        "allow_methods"    -> o.allowMethods,
        "blocked_methods"  -> o.blockedMethods
      )
    override def reads(json: JsValue): JsResult[GrpcWebConfig] =
      Try {
        GrpcWebConfig(
          allowServices = json.select("allow_services").asOpt[Seq[String]].getOrElse(Seq.empty),
          allowMethods = json.select("allow_methods").asOpt[Seq[String]].getOrElse(Seq.empty),
          blockedMethods = json.select("blocked_methods").asOpt[Seq[String]].getOrElse(Seq.empty)
        )
      } match {
        case Failure(e)    => JsError(e.getMessage)
        case Success(apkr) => JsSuccess(apkr)
      }
  }

  def validateGrpcPath(
      path: String,
      config: GrpcWebConfig
  ): Boolean = {

    // Parse the path: /package.Service/Method
    val parts = path.split("/").filter(_.nonEmpty)

    if (parts.length < 2) {
      return false
    }

    val servicePath = parts(0) // e.g., "helloworld.Greeter"
    val method      = parts(1) // e.g., "SayHello"

    // Extract service name (last part after dot)
    val serviceName = if (servicePath.contains(".")) {
      servicePath.substring(servicePath.lastIndexOf(".") + 1)
    } else {
      servicePath
    }

    // 1. Check blocked methods first (highest priority)
    if (config.blockedMethods.nonEmpty) {
      if (config.blockedMethods.contains(method)) {
        return false
      }
    }

    // 2. Check allowed services (if configured)
    if (config.allowServices.nonEmpty) {
      val serviceAllowed = config.allowServices.exists { allowed =>
        // Exact match or prefix match
        servicePath == allowed ||
        servicePath.startsWith(allowed + ".") ||
        serviceName == allowed
      }

      if (!serviceAllowed) {
        return false
      }
    }

    // 3. Check allowed methods (if configured)
    if (config.allowMethods.nonEmpty) {
      if (!config.allowMethods.contains(method)) {
        return false
      }
    }

    true
  }
}

class GrpcWebProxyPlugin extends NgRequestTransformer {

  override def name: String                                = "gRPC-Web Proxy"
  override def description: Option[String]                 = Some("Proxies gRPC-Web requests to gRPC backend - Envoy compatible")
  override def defaultConfigObject: Option[NgPluginConfig] = Some(GrpcWebConfig())
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Custom, NgPluginCategory.Transformations)
  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformRequest, NgStep.TransformResponse)

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {

    val config = ctx
      .cachedConfig(internalName)(GrpcWebConfig.fmt.reads)
      .getOrElse(GrpcWebConfig())

    val contentType   = ctx.request.headers.get("Content-Type").getOrElse("")
    val isGrpcWebText = contentType.contains("application/grpc-web-text")
    val isGrpcWeb     = contentType.contains("application/grpc-web")

    if (!contentType.contains("application/grpc-web")) {
      ctx.otoroshiRequest.rightf
    } else if (!GrpcWebConfig.validateGrpcPath(ctx.request.path, config)) {
      Results.Forbidden(Json.obj("error" -> "You're not authorized here !")).leftf
    } else {
      val body = if (isGrpcWebText) {
        ctx.otoroshiRequest.body
          .fold(ByteString.empty)(_ ++ _)
          .map { fullBody =>
            try {
              val decoded = Base64.getDecoder.decode(fullBody.toArray)
              ByteString(decoded)
            } catch {
              case _: Exception => fullBody
            }
          }
          .mapConcat(bs => List(bs))
      } else {
        ctx.otoroshiRequest.body
      }

      val headers: Map[String, String] = if (!isGrpcWeb && !isGrpcWebText) {
        ctx.otoroshiRequest.headers
      } else {
        ctx.request.headers.toMap
          .filter(_._1.toLowerCase != "content-length")
          .map { case (key, value) =>
            if (key.toLowerCase == "content-type") {
              ("content-type", if (isGrpcWebText) "application/grpc+proto" else "application/grpc")
            } else {
              (key, value.mkString(","))
            }
          } + ("te" -> "trailers")
      }

      ctx.otoroshiRequest
        .copy(headers = headers, body = body)
        .rightf
    }
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {

    val requestContentType = ctx.request.headers.get("Content-Type").getOrElse("")
    val isGrpcWebText      = requestContentType.contains("application/grpc-web-text")

    val body = if (!requestContentType.contains("application/grpc-web")) {
      // Not gRPC-Web request, pass through
      ctx.otoroshiResponse.body
    } else {
      // For grpc-web-text, encode response as base64
      if (isGrpcWebText) {
        ctx.otoroshiResponse.body
          .fold(ByteString.empty)(_ ++ _)
          .map { fullBody =>
            val encoded = Base64.getEncoder.encode(fullBody.toArray)
            ByteString(encoded)
          }
          .mapConcat(bs => List(bs))
      } else {
        ctx.otoroshiResponse.body
      }
    }

    val headers = ctx.otoroshiResponse.headers.map { case (key, value) =>
      if (key.toLowerCase == "content-type") {
        if (isGrpcWebText) {
          ("content-type", "application/grpc-web-text+proto")
        } else {
          ("content-type", value.replace("grpc", "grpc-web"))
        }
      } else {
        (key, value)
      }
    }

    ctx.otoroshiResponse
      .copy(headers = headers, body = body)
      .rightf
  }
}
