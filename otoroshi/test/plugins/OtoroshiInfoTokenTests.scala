package plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import functional.PluginsTestSpecBase
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import otoroshi.auth.{BasicAuthModuleConfig, BasicAuthUser, SessionCookieValues}
import otoroshi.models.*
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.*
import otoroshi.security.IdGenerator
import otoroshi.ssl.DynamicSSLEngineProvider
import otoroshi.ssl.pki.models.GenCsrQuery
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json.*

import java.security.interfaces.RSAPublicKey
import scala.concurrent.duration.DurationInt

class OtoroshiInfoTokenTests(parent: PluginsTestSpecBase) {
  import parent.*

  // the token is signed with the keypair of a certificate: its header must name that certificate in `kid`,
  // which is also the key id otoroshi exposes in its jwks, so that a verifier can pick the right key
  def withRsaKeyPair() = {
    val cert = env.pki
      .genSelfSignedCert(GenCsrQuery(hosts = Seq("info-token-signer.oto.tools"), subject = Some("CN=info-token-signer")))
      .futureValue
      .toOption
      .get
      .toCert
      .copy(id = s"info-token-signer-${IdGenerator.token(8)}", name = "info-token-signer")
      .enrich()
    cert.save()(using env.otoroshiExecutionContext, env).futureValue

    // the certificate store is fed from the proxy state, which syncs periodically
    def certInStore: Boolean = DynamicSSLEngineProvider.certificates.contains(cert.id)
    var waited               = 0
    while (!certInStore && waited < 30) {
      await(500.millis)
      waited += 1
    }
    withClue(s"certificate ${cert.id} never showed up in the store ") {
      certInStore mustBe true
    }

    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OtoroshiInfos],
          config = NgPluginInstanceConfig(
            NgOtoroshiInfoConfig
              .apply(
                secComVersion = SecComInfoTokenVersionLatest,
                secComTtl = 30.seconds,
                headerName = Some("foo"),
                addFields = None,
                projection = Json.obj(),
                algo = RSAKPAlgoSettings(256, cert.id)
              )
              .json
              .as[JsObject]
          )
        )
      ),
      id = IdGenerator.uuid
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK

    val rawToken = getInHeader(resp, "foo").get
    val header   = Json.parse(ApacheBase64.decodeBase64(rawToken.split("\\.")(0))).as[JsObject]
    header.selectAsString("alg") mustBe "RS256"
    header.selectAsString("kid") mustBe cert.id

    // the kid designates the key that actually signed the token
    val decoded = JWT
      .require(Algorithm.RSA256(cert.cryptoKeyPair.getPublic.asInstanceOf[RSAPublicKey], null))
      .build()
      .verify(rawToken)
    decoded.getKeyId mustBe cert.id

    deleteOtoroshiRoute(route).futureValue
    env.datastores.certificatesDataStore.delete(cert.id)(using env.otoroshiExecutionContext, env).futureValue
  }

  def withUser() = {
    val authenticationModule = BasicAuthModuleConfig(
      id = IdGenerator.namedId("auth_mod", env),
      name = "New auth. module",
      desc = "New auth. module",
      tags = Seq.empty,
      metadata = Map.empty,
      sessionCookieValues = SessionCookieValues(),
      clientSideSessionEnabled = true,
      users = Seq(
        BasicAuthUser(
          name = "Stefanie Koss",
          password = "$2a$10$RtYWagxgvorxpxNIYTi4Be2tU.n8294eHpwle1ad0Tmh7.NiVXOEq",
          email = "user@oto.tools",
          tags = Seq.empty,
          rights = UserRights(
            Seq(
              UserRight(
                TenantAccess("*"),
                Seq(TeamAccess("*"))
              )
            )
          ),
          adminEntityValidators = Map()
        )
      )
    )

    createAuthModule(authenticationModule).futureValue

    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[BasicAuthWithAuthModule],
          config = NgPluginInstanceConfig(
            BasicAuthWithAuthModuleConfig(ref = authenticationModule.id).json.as[JsObject]
          )
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OtoroshiInfos],
          config = NgPluginInstanceConfig(
            NgOtoroshiInfoConfig
              .apply(
                secComVersion = SecComInfoTokenVersionLatest,
                secComTtl = 30.seconds,
                headerName = Some("foo"),
                addFields = None,
                projection = Json.obj(),
                algo = HSAlgoSettings(512, "secret", base64 = false)
              )
              .json
              .as[JsObject]
          )
        )
      ),
      id = IdGenerator.uuid
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"          -> route.frontend.domains.head.domain,
        "Authorization" -> "Basic dXNlckBvdG8udG9vbHM6cGFzc3dvcmQ="
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK

    val tokenBody = getInHeader(resp, "foo").get.split("\\.")(1)
    val token     = Json.parse(ApacheBase64.decodeBase64(tokenBody)).as[JsObject]
    token.selectAsString("iss") mustBe "Otoroshi"
    token.selectAsString("access_type") mustBe "user"
    token.selectAsObject("user").selectAsString("email") mustBe "user@oto.tools"

    deleteAuthModule(authenticationModule).futureValue
    deleteOtoroshiRoute(route).futureValue
  }

  def default() = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OtoroshiInfos],
          config = NgPluginInstanceConfig(
            NgOtoroshiInfoConfig
              .apply(
                secComVersion = SecComInfoTokenVersionLatest,
                secComTtl = 30.seconds,
                headerName = Some("foo"),
                addFields = None,
                projection = Json.obj(),
                algo = HSAlgoSettings(512, "secret", base64 = false)
              )
              .json
              .as[JsObject]
          )
        )
      ),
      id = IdGenerator.uuid
    ).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK

    val tokenBody = getInHeader(resp, "foo").get.split("\\.")(1)
    Json.parse(ApacheBase64.decodeBase64(tokenBody)).as[JsObject].selectAsString("iss") mustBe "Otoroshi"
    Json.parse(ApacheBase64.decodeBase64(tokenBody)).as[JsObject].selectAsString("access_type") mustBe "public"

    // a shared secret has no key id: the header stays as it was
    val tokenHeader = Json.parse(ApacheBase64.decodeBase64(getInHeader(resp, "foo").get.split("\\.")(0))).as[JsObject]
    tokenHeader.keys mustBe Set("typ", "alg")

    deleteOtoroshiRoute(route).futureValue
  }

  def withApikeys() = {
    val route = createRouteWithExternalTarget(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OtoroshiInfos],
          config = NgPluginInstanceConfig(
            NgOtoroshiInfoConfig
              .apply(
                secComVersion = SecComInfoTokenVersionLatest,
                secComTtl = 30.seconds,
                headerName = Some("foo"),
                addFields = None,
                projection = Json.obj(),
                algo = HSAlgoSettings(512, "secret", base64 = false)
              )
              .json
              .as[JsObject]
          )
        )
      ),
      id = IdGenerator.uuid
    ).futureValue

    val apikey = ApiKey(
      clientId = IdGenerator.token(16),
      clientSecret = IdGenerator.token(64),
      clientName = "apikey1",
      authorizedEntities = Seq(RouteIdentifier(route.id))
    )
    createOtoroshiApiKey(apikey).futureValue

    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host"                   -> route.frontend.domains.head.domain,
        "Otoroshi-Client-Id"     -> apikey.clientId,
        "Otoroshi-Client-Secret" -> apikey.clientSecret
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK

    val tokenBody = getInHeader(resp, "foo").get.split("\\.")(1)
    val token     = Json.parse(ApacheBase64.decodeBase64(tokenBody)).as[JsObject]
    token.selectAsString("iss") mustBe "Otoroshi"
    token.selectAsString("access_type") mustBe "apikey"
    token.selectAsObject("apikey").selectAsString("clientId") mustBe apikey.clientId

    deleteOtoroshiApiKey(apikey).futureValue
    deleteOtoroshiRoute(route).futureValue
  }
}
