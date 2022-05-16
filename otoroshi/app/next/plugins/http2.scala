package otoroshi.next.plugins

import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.next.models.NgTarget
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Either

class Http2Caller extends NgBackendCall {

  override def useDelegates: Boolean             = false
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Experimental)
  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgInternal
  override def multiInstance: Boolean            = false

  override def defaultConfigObject: Option[NgPluginConfig] = None

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {
    val hasBody = ctx.rawRequest.theHasBody
    val bodyF   = if (hasBody) ctx.request.body.runFold(ByteString.empty)(_ ++ _) else ByteString.empty.vfuture
    bodyF.flatMap { bodyRaw =>
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
          "method"  -> ctx.request.method,
          "url"     -> ctx.request.url,
          "headers" -> ctx.request.headers, // TODO: add cookies
          "target"  -> ctx.request.backend
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
      env.Ws
        .url(s"http://127.0.0.1:${env.http2ClientProxyPort}/http2_request")
        .withMethod("POST")
        .withHttpHeaders(
          "Host"           -> "127.0.0.1:8555",
          "Content-Type"   -> "application/json",
          "Content-Length" -> (body.size - 0).toString
        )
        .withBody(Source(body.grouped(16 * 1024).toList))
        .execute()
        .map { resp =>
          val body    = resp.json
          val bodyOut = ByteString(body.select("body").asString).decodeBase64
          bodyResponse(
            body.select("status").asInt,
            body.select("headers").as[Map[String, String]],
            Source(bodyOut.grouped(16 * 1024).toList)
          )
        }
    }
  }
}
