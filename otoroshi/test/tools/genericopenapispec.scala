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
        // Try to find the correct path - either from root or from sub-project
        val f = new File(file)
        val prefixedFile = new File(s"otoroshi/$file")

        val actualFile = if (f.getParentFile != null && f.getParentFile.exists()) {
          f
        } else if (prefixedFile.getParentFile != null && prefixedFile.getParentFile.exists()) {
          prefixedFile
        } else {
          // Create the file at the original path if neither exists
          val parentDir = f.getParentFile
          if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
          }
          f
        }

        actualFile.delete()
        Files.writeString(actualFile.toPath, spec)
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