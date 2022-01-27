package functional

import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import otoroshi.next.models._
import play.api.Configuration

import scala.util.Failure

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
      
      // import otoroshi.utils.syntax.implicits._
      // NgRoutesComposition.fromOpenApi("api.oto.tools", "https://raw.githubusercontent.com/MAIF/otoroshi/master/otoroshi/public/openapi.json")(otoroshiComponents.env.otoroshiExecutionContext, otoroshiComponents.env).map { route =>
      //   java.nio.file.Files.writeString(new java.io.File("./routescomp-debug.json").toPath(), route.json.prettify)
      // }.futureValue
    }

    "shutdown" in {
      stopAll()
    }
  }
}

