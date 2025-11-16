package plugins

import functional.PluginsTestSpec
import otoroshi.auth.{BasicAuthModuleConfig, BasicAuthUser, SessionCookieValues}
import otoroshi.models._
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  AuthModule,
  JwtVerification,
  NgAuthModuleConfig,
  NgJwtUserExtractor,
  NgJwtUserExtractorConfig,
  NgJwtVerificationConfig,
  OverrideHost
}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.http.Status
import play.api.libs.json.JsObject

class JwtUserExtractorTests(parent: PluginsTestSpec) {
  import parent._

  val verifier = GlobalJwtVerifier(
    id = IdGenerator.uuid,
    name = "verifier",
    desc = "verifier",
    strict = true,
    source = InHeader(name = "foo"),
    algoSettings = HSAlgoSettings(256, "secret"),
    strategy = PassThrough(
      verificationSettings = VerificationSettings(Map("iss" -> "foo"))
    )
  )
  createOtoroshiVerifier(verifier).futureValue

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
        password = "$2a$10$uCFLbo3TtK9VJvP5jO4REeN5ccfM/EZ9inPo6H4pNndSGUDCFPRzi",
        email = "stefanie.koss@oto.tools",
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

  val route = createRequestOtoroshiIORoute(
    Seq(
      NgPluginInstance(NgPluginHelper.pluginId[OverrideHost]),
      NgPluginInstance(
        NgPluginHelper.pluginId[NgJwtUserExtractor],
        config = NgPluginInstanceConfig(
          NgJwtUserExtractorConfig(
            verifier = verifier.id
          ).json.as[JsObject]
        )
      ),
      NgPluginInstance(
        NgPluginHelper.pluginId[AuthModule],
        config = NgPluginInstanceConfig(
          NgAuthModuleConfig(
            module = authenticationModule.id.some
          ).json.as[JsObject]
        )
      )
    )
  )

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain,
        "foo"  -> "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJmb28iLCJpYXQiOjE3NjAxMDM1MzN9.TAj08m-Ax3dUFrZ2NU3oG3tPdIFOGvJdpO3Yhas63rw"
      )
      .get()
      .futureValue

    resp.status mustBe Status.OK
  }

  {
    val resp = ws
      .url(s"http://127.0.0.1:$port/api")
      .withHttpHeaders(
        "Host" -> route.frontend.domains.head.domain,
        "foo"  -> "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJmb28iLCJpYXQiOjE3NjAxMDM1MzN9.TAj08m-Ax3dUFrZ2NU3oG3tPdIFOGvJdpOYhas63rw"
      )
      .get()
      .futureValue

    resp.status mustBe Status.UNAUTHORIZED
  }

  deleteAuthModule(authenticationModule).futureValue
  deleteOtoroshiVerifier(verifier).futureValue
  deleteOtoroshiRoute(route).futureValue
}
