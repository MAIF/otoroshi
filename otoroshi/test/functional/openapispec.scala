package functional

import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import otoroshi.openapi.OpenApiGenerator

import java.io.File

class OpenApiSpec extends WordSpec with MustMatchers with OptionValues {

  val generator = new OpenApiGenerator(
    "./conf/routes",
    "./app/openapi/openapi.cfg",
    "./app/openapi/openapi.json",
    "../release-1.5.0-alpha.8/swagger.json"
  )

  generator.run()
  // generator.readOldSpec()
}