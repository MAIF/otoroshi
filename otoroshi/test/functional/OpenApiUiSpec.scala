package functional

import play.api.Configuration

// the openapi ui page is served on the backoffice origin and writes the url of the openapi document into a <script>
// block. nothing coming from the request may close that block.
class OpenApiUiSpec extends OtoroshiSpec {

  def openapiUi(core: String): String = {
    val response = ws
      .url(s"http://localhost:${port}/apis/openapi/ui")
      .withQueryStringParameters("core" -> core)
      .withHttpHeaders("Host" -> "otoroshi.oto.tools")
      .get()
      .futureValue
    response.status mustBe 200
    response.body
  }

  s"OpenApi ui" should {
    "warm up" in {
      startOtoroshi()
    }
    "not reflect the core query parameter" in {
      val payload = "</script><script>window.injected = true</script>"
      val body    = openapiUi(payload)
      body must not include payload
      body must include("/apis/openapi?core=false")
    }
    "keep the core flag" in {
      openapiUi("true") must include("/apis/openapi?core=true")
    }
    "not let the openapi url close the script block" in {
      val html = otoroshi.views.html.oto.openapiFrame("http://localhost/apis/openapi?core=</script><b>&x=1").body
      html must not include "</script><b>"
      html must include("core=\\u003c/script\\u003e\\u003cb\\u003e\\u0026x=1")
    }
    "shutdown" in {
      stopAll()
    }
  }

  override def getTestConfiguration(configuration: Configuration) = configuration
}
