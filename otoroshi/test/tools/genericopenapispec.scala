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
      val spec = otoroshi.api.OpenApi.generate(otoroshiComponents.env, None)
      files.foreach { file =>
        val f = new File(file)
        f.delete()
        Files.writeString(f.toPath, spec)
      }
    }
    "shutdown" in {
      stopAll()
    }
  }

  override def getTestConfiguration(configuration: Configuration) = {
    Configuration(
      ConfigFactory.parseString(s"""app.env = dev""".stripMargin).resolve()
    ).withFallback(configuration)
  }
}
