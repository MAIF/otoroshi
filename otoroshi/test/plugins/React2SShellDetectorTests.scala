package plugins

import functional.PluginsTestSpec
import otoroshi.next.models.{NgPluginInstance, NgPluginInstanceConfig}
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{
  NgRedirectionSettings,
  OverrideHost,
  React2SShellDetector,
  React2SShellDetectorConfig,
  Redirection
}
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.http.Status
import play.api.libs.json.{JsObject, Json}

class React2SShellDetectorTests(parent: PluginsTestSpec) {

  import parent._

  val route = createRouteWithExternalTarget(
    Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[React2SShellDetector],
        config = NgPluginInstanceConfig(
          React2SShellDetectorConfig(
            block = true
          ).json.as[JsObject]
        )
      )
    )
  )

  val passingResp1 = ws
    .url(s"http://127.0.0.1:$port/api")
    .withFollowRedirects(false)
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .get()
    .futureValue

  val passingResp2 = ws
    .url(s"http://127.0.0.1:$port/api")
    .withFollowRedirects(false)
    .withHttpHeaders(
      "Host" -> route.frontend.domains.head.domain
    )
    .post(Json.obj("foo" -> "bar"))
    .futureValue

  val blockedResp = ws
    .url(s"http://127.0.0.1:$port/api")
    .withFollowRedirects(false)
    .withHttpHeaders(
      "Host"                     -> route.frontend.domains.head.domain,
      "User-Agent"               -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/60.0.3112.113 Safari/537.36 Assetnote/1.0.0",
      "Next-Action"              -> "x",
      "X-Nextjs-Request-Id"      -> "b5dce965",
      "Content-Type"             -> "multipart/form-data; boundary=----WebKitFormBoundaryx8jO2oVc6SWP3Sad",
      "X-Nextjs-Html-Request-Id" -> "SSTMXm7OJ_g0Ncx6jpQt9"
    )
    .post(s"""
         |------WebKitFormBoundaryx8jO2oVc6SWP3Sad
         |Content-Disposition: form-data; name="0"
         |
         |{
         |  "then": "$$1:__proto__:then",
         |  "status": "resolved_model",
         |  "reason": -1,
         |  "value": "{\"then\":\"$$B1337\"}",
         |  "_response": {
         |    "_prefix": "var res=process.mainModule.require('child_process').execSync('id',{'timeout':5000}).toString().trim();;throw Object.assign(new Error('NEXT_REDIRECT'), {digest:`$${res}`});",
         |    "_chunks": "$$Q2",
         |    "_formData": {
         |      "get": "$$1:constructor:constructor"
         |    }
         |  }
         |}
         |------WebKitFormBoundaryx8jO2oVc6SWP3Sad
         |Content-Disposition: form-data; name="1"
         |
         |"$$@0"
         |------WebKitFormBoundaryx8jO2oVc6SWP3Sad
         |Content-Disposition: form-data; name="2"
         |
         |[]
         |------WebKitFormBoundaryx8jO2oVc6SWP3Sad--
         |""".stripMargin)
    .futureValue

  passingResp1.status mustBe Status.OK
  println(s"passingResp1.body: ${passingResp2.status} - ${passingResp1.body}")
  passingResp2.status mustBe Status.OK
  println(s"passingResp2.body: ${passingResp2.status} - ${passingResp2.body}")
  blockedResp.status mustBe Status.UNAUTHORIZED
  println(s"blockedResp.body: ${blockedResp.status} - ${blockedResp.body}")

  deleteOtoroshiRoute(route).futureValue
}
