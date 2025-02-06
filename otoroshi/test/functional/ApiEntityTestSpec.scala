package functional

import com.typesafe.config.ConfigFactory
import next.models.ApiConsumerSettings.Apikey
import next.models.{Api, ApiConsumer, ApiConsumerKind, ApiConsumerSettings, ApiConsumerStatus}
import org.joda.time.DateTime
import otoroshi.greenscore.EcoMetrics.MAX_GREEN_SCORE_NOTE
import otoroshi.greenscore._
import otoroshi.models.{EntityLocation, RoundRobin}
import otoroshi.next.models._
import otoroshi.utils.syntax.implicits.{BetterJsValue, BetterSyntax}
import play.api.Configuration
import play.api.libs.json.{JsArray, JsNull, JsObject, JsValue}
import play.api.libs.ws.WSAuthScheme

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, Future}

class ApiEntityTestSpec(name: String, configurationSpec: => Configuration) extends OtoroshiSpec {

  implicit lazy val mat = otoroshiComponents.materializer
  implicit lazy val env = otoroshiComponents.env

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString("")
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  def wait[A](fu: Future[A], duration: Option[FiniteDuration] = Some(10.seconds)): A = Await.result(fu, duration.get)

  def fetch(
      path: String = "",
      method: String = "get",
      body: Option[JsValue] = JsNull.some
  ) = {
    println(s"http://otoroshi-api.oto.tools:$port$path")
    val request = wsClient
      .url(s"http://otoroshi-api.oto.tools:$port$path")
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)

    method match {
      case "post" => wait(request.post(body.get))
      case "put"  => wait(request.put(body.get))
      case _      => wait(request.get())
    }
  }


  s"Api Entity" should {
    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().futureValue // WARM UP
      getOtoroshiRoutes().futureValue

//      wait(createOtoroshiRoute(initialRoute))
    }

    // create an empty group without routes called TEMPLATE_WITHOUT_CHANGE_OR_DATES
    "create api" in {
      val created  = createApi()
      created mustBe 201
    }

    def createApi() = {
      val template = env.datastores.apiDataStore.template(env)
        .copy(id = "template_api")

      fetch(
        path = "/apis/apis.otoroshi.io/v1/apis",
        method = "post",
        body = template.json.some).status
    }

    "add consumer to api" in {
      createApi()
      val result = fetch(path = "/apis/api.otoroshi.io/v1/apis/template_api")
      val api = Api.format.reads(result.json).get
        .copy(consumers = Seq(ApiConsumer(
          id = "api_consumner",
          name = "apikey consumer",
          autoValidation = false,
          description = None,
          consumerKind = ApiConsumerKind.Apikey,
          settings = Apikey("apikey", 1000, 1000, 1000),
          status = ApiConsumerStatus.Staging,
          subscriptions = Seq.empty
        )))

      val patched  = fetch(
        path = "/apis/apis.otoroshi.io/v1/apis/template_api",
        method = "put",
        body = api.json.some).status
      patched mustBe 204
    }

    "shutdown" in {
      stopAll()
    }
  }
}
