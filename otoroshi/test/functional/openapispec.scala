package functional

import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import otoroshi.openapi.{FormsGenerator, OpenApiGenerator, OpenApiGeneratorRunner}
import play.api.libs.json.{JsObject, JsValue, Json}

import java.io.File
import scala.collection.concurrent.TrieMap

class OpenApiSpec extends WordSpec with MustMatchers with OptionValues {

  //val runner = new OpenApiGeneratorRunner()
  //runner.generate()

  val generator = new OpenApiGenerator(
    "./conf/routes",
    "./app/openapi/openapi-cfg.json",
    Seq("./public/openapi.json", "../manual/src/main/paradox/code/openapi.json"),
    write = false,
    classNames = Seq("otoroshi.next.plugins", "otoroshi.next.models")
  )
  val spec: JsValue = generator.run()

  val formsGenerator = new FormsGenerator(spec)
  formsGenerator.run()

  /*val generator = new OpenApiGenerator(
    "./conf/routes",
    "./app/openapi/openapi-cfg.json",
    Seq("./public/openapi.json", "../manual/src/main/paradox/code/openapi.json"),
    write = true
  )

  generator.run()*/
}
