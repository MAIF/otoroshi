package tools

import com.typesafe.config.ConfigFactory
import functional.OtoroshiSpec
import play.api.Configuration

import java.io.File
import java.nio.file.Files

class GenericOpenApiSpec extends OtoroshiSpec {
  val files = Seq(
    "./public/openapi.json",
    "./app/openapi/openapi.json",
    "../manual/src/main/paradox/code/openapi.json"
  )
  s"OpenApi" should {
    "warm up" in {
      startOtoroshi()
    }
    "generate" in {
      val spec = otoroshi.api.OpenApi.generate(otoroshiComponents.env)
      files.foreach(file => Files.writeString(new File(file).toPath, spec))
    }
    "shutdown" in {
      stopAll()
    }
  }

  override def getTestConfiguration(configuration: Configuration) = {
    Configuration(
      ConfigFactory.parseString(s"""{}""".stripMargin).resolve()
    ).withFallback(configuration)
  }
}
