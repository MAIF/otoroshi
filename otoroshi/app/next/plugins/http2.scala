package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.models.NgTarget
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.mvc.Result

import scala.concurrent.{ExecutionContext, Future}

class Http2Caller extends NgRequestTransformer {

  override def steps: Seq[NgStep]                = Seq(NgStep.TransformRequest, NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Experimental)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgInternal

  override def multiInstance: Boolean            = false
  override def isTransformRequestAsync: Boolean  = true
  override def isTransformResponseAsync: Boolean = true

  override def transformsRequest: Boolean  = true
  override def transformsResponse: Boolean = true

  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val hasBody = ctx.request.theHasBody
    val bodyF   = if (hasBody) ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _) else ByteString.empty.vfuture
    bodyF.map { bodyRaw =>
      val target = NgTarget(
        id = "http2-client-proxy",
        hostname = "127.0.0.1",
        port = env.http2ClientProxyPort,
        tls = false
      )
      ctx.attrs.put(otoroshi.plugins.Keys.RequestTargetKey -> target.toTarget)
      ctx.attrs.put(otoroshi.next.plugins.Keys.BackendKey  -> target)
      val base64body = bodyRaw.decodeBase64
      val body       = Json
        .obj(
          "method"  -> ctx.otoroshiRequest.method,
          "url"     -> ctx.otoroshiRequest.url,
          "headers" -> ctx.otoroshiRequest.headers, // TODO: add cookies
          "target"  -> ctx.otoroshiRequest.backend
            .map(b =>
              b.json.asObject.applyOn { obj =>
                val tls_config = b.tlsConfig.copy(
                  certs = b.tlsConfig.certs
                    .map(id => env.proxyState.certificate(id))
                    .collect { case Some(cert) => cert }
                    .map(c => c.bundle),
                  trustedCerts = b.tlsConfig.trustedCerts
                    .map(id => env.proxyState.certificate(id))
                    .collect { case Some(cert) => cert }
                    .map(c => c.bundle)
                )
                obj ++ Json.obj("tls_config" -> tls_config.json)
              }
            )
            .getOrElse(JsNull)
            .as[JsValue]
        )
        .applyOnIf(hasBody) { obj =>
          obj ++ Json.obj("body" -> base64body)
        }
        .stringify
        .byteString
      val newRequest = ctx.otoroshiRequest.copy(
        url = s"http://127.0.0.1:${env.http2ClientProxyPort}/http2_request",
        method = "POST",
        headers = Map(
          "Host"           -> "127.0.0.1:8555",
          "Content-Type"   -> "application/json",
          "Content-Length" -> (body.size - 0).toString
        ),
        cookies = Seq.empty,
        version = ctx.otoroshiRequest.version,
        clientCertificateChain = ctx.otoroshiRequest.clientCertificateChain,
        backend = Some(target),
        body = Source(body.grouped(16 * 1024).toList)
      )
      newRequest.right
    }
  }

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map { bodyRaw =>
      val body    = Json.parse(bodyRaw.utf8String)
      val bodyOut = ByteString(body.select("body").asString).decodeBase64
      NgPluginHttpResponse(
        status = body.select("status").asInt,
        headers = body.select("headers").as[Map[String, String]],
        cookies = Seq.empty, // TODO: handle cookies
        body = Source(bodyOut.grouped(16 * 1024).toList)
      ).right
    }
  }
}
