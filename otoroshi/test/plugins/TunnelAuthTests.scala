package plugins

import functional.PluginsTestSpecBase
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.headers.{Host, RawHeader}
import org.apache.pekko.http.scaladsl.model.ws.{InvalidUpgradeResponse, ValidUpgrade, WebSocketRequest, Message as WsMessage}
import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import play.api.http.Status

import java.nio.charset.StandardCharsets
import java.util.Base64

// the websockets used by tunnel agents (register) and by the nodes relaying tunnel requests (relay) used to accept
// anybody. they now take the admin api authentication: the admin api route validates the apikey and sends the claim
class TunnelAuthTests(parent: PluginsTestSpecBase) {
  import parent.*

  val adminCredentials = "Basic " + Base64.getEncoder.encodeToString(
    "admin-api-apikey-id:admin-api-apikey-secret".getBytes(StandardCharsets.UTF_8)
  )

  def upgrade(path: String, host: String, authorization: Option[String]): Int = {
    val (response, _) = Http()(using system).singleWebSocketRequest(
      WebSocketRequest(
        s"ws://127.0.0.1:$port$path",
        extraHeaders = Seq(Host(host)) ++ authorization.map(a => RawHeader("Authorization", a))
      ),
      Flow.fromSinkAndSourceMat(Sink.ignore, Source.maybe[WsMessage])((_, promise) => promise)
    )
    response.futureValue match {
      case ValidUpgrade(r, _)           => r.status.intValue
      case InvalidUpgradeResponse(r, _) => r.status.intValue
    }
  }

  val register = "/api/tunnels/register?tunnel_id=tunnel-auth-test"
  val relay    = "/api/tunnels/tunnel-auth-test/relay"

  Seq(register, relay).foreach { path =>
    withClue(s"$path on the backoffice host, no credentials ") {
      upgrade(path, env.backOfficeHost, None) mustBe Status.UNAUTHORIZED
    }
    withClue(s"$path on the internal admin api host, no claim ") {
      upgrade(path, env.adminApiHost, None) mustBe Status.UNAUTHORIZED
    }
    withClue(s"$path through the admin api route, admin apikey ") {
      upgrade(path, env.adminApiExposedHost, Some(adminCredentials)) mustBe Status.SWITCHING_PROTOCOLS
    }
  }
}
