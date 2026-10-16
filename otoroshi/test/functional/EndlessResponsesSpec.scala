package functional

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.util.ByteString
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.DefaultBodyWritables.*
import play.api.libs.ws.{readableAsString, WSAuthScheme}

import scala.concurrent.ExecutionContext

// the endless responses the global config sends to some client addresses are made of middle finger emojis when
// middleFingers is set, of zeros otherwise
class EndlessResponsesSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  private val domain = "endless-responses.oto.tools"
  // no trusted proxy: the client address is the leftmost entry of X-Forwarded-For
  private val bot    = "10.66.66.66"

  private val middleFinger = ByteString.fromString("🖕")

  private def endlessFor(addresses: Seq[String], middleFingers: Boolean): Unit = {
    given env: Env            = otoroshiComponents.env
    given ec: ExecutionContext = env.otoroshiExecutionContext
    val config                = env.datastores.globalConfigDataStore.singleton().futureValue
    config.copy(endlessIpAddresses = addresses, middleFingers = middleFingers).save().futureValue
  }

  private def firstChunk(): ByteString = {
    given mat: Materializer = otoroshiComponents.env.otoroshiMaterializer
    val resp                = ws
      .url(s"http://127.0.0.1:$port/")
      .withHttpHeaders("Host" -> domain, "X-Forwarded-For" -> bot)
      .stream()
      .futureValue
    resp.status mustBe 200
    resp.bodyAsSource.take(1).runWith(Sink.head).futureValue
  }

  "An endless response" should {
    "warm up" in {
      startOtoroshi()
      val template = ws
        .url(s"http://127.0.0.1:$port/apis/proxy.otoroshi.io/v1/routes/_template")
        .withHttpHeaders("Host" -> "otoroshi-api.oto.tools")
        .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
        .get()
        .futureValue
        .json
        .as[JsObject]
      val route    = template ++ Json.obj(
        "id"       -> "route-endless-responses",
        "frontend" -> ((template \ "frontend").as[JsObject] ++ Json.obj("domains" -> Json.arr(domain)))
      )
      val created  = ws
        .url(s"http://127.0.0.1:$port/apis/proxy.otoroshi.io/v1/routes")
        .withHttpHeaders("Host" -> "otoroshi-api.oto.tools", "Content-Type" -> "application/json")
        .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
        .post(route.stringify)
        .futureValue
      withClue(created.body[String])(created.status mustBe 201)
      // the proxy states take the route a few seconds after
      Thread.sleep(3000)
    }
    "be made of middle finger emojis when middleFingers is set" in {
      endlessFor(Seq(bot), middleFingers = true)
      val chunk = firstChunk()
      chunk.take(middleFinger.size) mustBe middleFinger
    }
    "be made of zeros when middleFingers is not set" in {
      endlessFor(Seq(bot), middleFingers = false)
      val chunk = firstChunk()
      chunk must not be empty
      chunk.forall(_ == 0) mustBe true
    }
    "shutdown" in {
      endlessFor(Seq.empty, middleFingers = false)
      stopAll()
    }
  }
}
