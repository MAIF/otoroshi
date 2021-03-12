package functional

import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import otoroshi.openapi.OpenApiGenerator

import java.io.File

class OpenApiSpec extends WordSpec with MustMatchers with OptionValues {

  val generator = new OpenApiGenerator(
    "./conf/routes",
    "./app/openapi/openapi-cfg.json",
    Seq("./public/openapi.json", "../manual/src/main/paradox/code/openapi.json"),
    write = true
  )

  generator.run()
}
