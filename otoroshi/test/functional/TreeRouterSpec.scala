package functional

import com.typesafe.config.ConfigFactory
import org.scalatest.OptionValues
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import otoroshi.env.Env
import otoroshi.next.models._
import otoroshi.utils.TypedMap
import play.api.Configuration

import scala.util.Failure

class NgTreeRouterSpec extends AnyWordSpec with Matchers with OptionValues with ScalaFutures with IntegrationPatience {
  "NgTreeRouter" should {
    "find routes fast" in {
      NgTreeRouter_Test.testFindRoutes()
    }
  }
}

class NgTreeRouterPathParamsSpec
    extends AnyWordSpec
    with Matchers
    with OptionValues
    with ScalaFutures
    with IntegrationPatience {
  "NgTreeRouter" should {
    "be able to use path params" in {
      NgTreeRouter_Test.testPathParams()
    }
  }
}

//class NgTreeRouterRealLifeSpec
//    extends AnyWordSpec
//    with Matchers
//    with OptionValues
//    with ScalaFutures
//    with IntegrationPatience {
//  "NgTreeRouter" should {
//    "be able to handle real life" in {
//      NgTreeRouter_Test.testRealLifeRouter()
//    }
//  }
//}

class NgTreeRouterWildcardSpec
    extends AnyWordSpec
    with Matchers
    with OptionValues
    with ScalaFutures
    with IntegrationPatience {
  "NgTreeRouter" should {
    "be able to handle wildcard domains" in {
      NgTreeRouter_Test.testWildcardDomainsRouter()
    }
  }
}

class NgTreeRouterWithEnvSpec(configurationSpec: => Configuration) extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(
      ConfigFactory
        .parseString("{}")
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)
  }

  "NgTreeRouter" should {

    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().andThen { case Failure(e) =>
        e.printStackTrace()
      }.futureValue // WARM UP
    }

    "find route fast" in {
      NgTreeRouter_Test.testFindRoute(otoroshiComponents.env)
    }

    "shutdown" in {
      stopAll()
    }
  }
}

class NgTreeRouterOpenapiWithEnvSpec(configurationSpec: => Configuration) extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = {
    Configuration(
      ConfigFactory
        .parseString("{}")
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)
  }

  "NgTreeRouter" should {

    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().andThen { case Failure(e) =>
        e.printStackTrace()
      }.futureValue // WARM UP
    }

    "find route fast" in {
      import otoroshi.utils.syntax.implicits._
      NgRouteComposition
        .fromOpenApi(
          "api.oto.tools",
          "https://raw.githubusercontent.com/MAIF/otoroshi/master/otoroshi/public/openapi.json"
        )(otoroshiComponents.env.otoroshiExecutionContext, otoroshiComponents.env)
        .map { route =>
          val routes = route.toRoutes
          val router       = NgTreeRouter.build(routes)
          val attrs        = TypedMap.empty.put(otoroshi.plugins.Keys.SnowFlakeKey -> "1")
          implicit val env: Env = otoroshiComponents.env

          // Test with a GET endpoint
          router
            .find("api.oto.tools", "/apis/proxy.otoroshi.io/v1/routes/_count")
            .map(_.routes.map(_.name))
            .debugPrintln
            .exists(_.size == 1)
            .mustBe(true)
          
          // Test with a parameterized GET endpoint
          router
            .find("api.oto.tools", "/api/cluster/sessions/123")
            .map(_.routes.map(_.name))
            .debugPrintln
            .exists(_.size == 1)
            .mustBe(true)

          // Test findRoute with GET endpoints
          router
            .findRoute(new NgTreeRouter_Test.NgFakeRequestHeader("api.oto.tools", "/apis/proxy.otoroshi.io/v1/routes/_count"), attrs)
            .map(_.route.name)
            .debugPrintln
            .isDefined
            .mustBe(true)
          
          // Test non-existent path
          router
            .findRoute(new NgTreeRouter_Test.NgFakeRequestHeader("api.oto.tools", "/api/cluster/sessions/123/foo"), attrs)
            .map(_.route.name)
            .debugPrintln
            .isDefined
            .mustBe(false)
          
          // Test parameterized path
          router
            .findRoute(new NgTreeRouter_Test.NgFakeRequestHeader("api.oto.tools", "/api/cluster/sessions/123"), attrs)
            .map(_.route.name)
            .debugPrintln
            .isDefined
            .mustBe(true)

        // java.nio.file.Files.writeString(new java.io.File("./routescomp-debug.json").toPath(), route.json.prettify)
        }
        .futureValue
    }

    "shutdown" in {
      stopAll()
    }
  }
}
