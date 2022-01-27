package functional

import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import otoroshi.next.models._
import play.api.Configuration

import scala.util.Failure
import otoroshi.utils.TypedMap

class NgTreeRouterSpec extends WordSpec with MustMatchers with OptionValues with ScalaFutures with IntegrationPatience {
  "NgTreeRouter" should {
    "find routes fast" in {
      NgTreeRouter_Test.testFindRoutes()
    }
  }
}

class NgTreeRouterPathParamsSpec extends WordSpec with MustMatchers with OptionValues with ScalaFutures with IntegrationPatience {
  "NgTreeRouter" should {
    "be able to use path params" in {
      NgTreeRouter_Test.testPathParams()
    }
  }
}

class NgTreeRouterRealLifeSpec extends WordSpec with MustMatchers with OptionValues with ScalaFutures with IntegrationPatience {
  "NgTreeRouter" should {
    "be able to handle real life" in {
      NgTreeRouter_Test.testRealLifeRouter()
    }
  }
}

class NgTreeRouterWildcardSpec extends WordSpec with MustMatchers with OptionValues with ScalaFutures with IntegrationPatience {
  "NgTreeRouter" should {
    "be able to handle wildcard domains" in {
      NgTreeRouter_Test.testWildcardDomainsRouter()
    }
  }
}

class NgTreeRouterWithEnvSpec(configurationSpec: => Configuration) extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration) = {
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

  override def getTestConfiguration(configuration: Configuration) = {
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
      NgRoutesComposition.fromOpenApi("api.oto.tools", "https://raw.githubusercontent.com/MAIF/otoroshi/master/otoroshi/public/openapi.json")(otoroshiComponents.env.otoroshiExecutionContext, otoroshiComponents.env).map { route =>
        val router = NgTreeRouter.build(route.toRoutes.debug(r => println(r.size)))
        val attrs = TypedMap.empty.put(otoroshi.plugins.Keys.SnowFlakeKey -> "1")
        implicit val env = otoroshiComponents.env

        router.find("api.oto.tools", "/api/services").map(_.routes.map(_.name)).debugPrintln
        router.find("api.oto.tools", "/api/apikeys/123/foo").map(_.routes.map(_.name)).debugPrintln

        router.findRoute(new NgTreeRouter_Test.NgFakeRequestHeader("api.oto.tools", "/api/services"), attrs).map(_.route.name).debugPrintln
        router.findRoute(new NgTreeRouter_Test.NgFakeRequestHeader("api.oto.tools", "/api/apikeys/123/foo"), attrs).map(_.route.name).debugPrintln
        router.findRoute(new NgTreeRouter_Test.NgFakeRequestHeader("api.oto.tools", "/api/apikeys/123"), attrs).map(_.route.name).debugPrintln
        
        // java.nio.file.Files.writeString(new java.io.File("./routescomp-debug.json").toPath(), route.json.prettify)
      }.futureValue
    }

    "shutdown" in {
      stopAll()
    }
  }
}

