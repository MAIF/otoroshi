package plugins

import functional.PluginsTestSpec
import org.apache.commons.codec.binary.{Base64 => ApacheBase64}
import otoroshi.auth.{BasicAuthModuleConfig, BasicAuthUser, SessionCookieValues}
import otoroshi.models._
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins._
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json._

import scala.concurrent.duration.DurationInt

class OtoroshiInfoTokenTests(parent: PluginsTestSpec) {
  import parent._

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
