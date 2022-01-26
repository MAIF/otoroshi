package functional

import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import otoroshi.next.models.NgTreeRouter_Test
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

