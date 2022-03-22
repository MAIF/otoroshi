package functional

import io.github.classgraph.ClassGraph
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.{MustMatchers, OptionValues}
import otoroshi.openapi.{CrdsGenerator, FormsGenerator, OpenApiGenerator}
import play.api.libs.json.JsValue

class OpenApiSpec extends AnyWordSpec with Matchers with OptionValues {
  // val runner = new OpenApiGeneratorRunner()
  // runner.generate()

 val scanResult = new ClassGraph()
    .addClassLoader(this.getClass.getClassLoader)
    .enableAllInfo()
    .acceptPackages(Seq("otoroshi", "otoroshi_plugins", "play.api.libs.ws"): _*)
    .scan

  val generator = new OpenApiGenerator(
    "./conf/routes",
    "./app/openapi/openapi-cfg.json",
    Seq("./public/openapi.json", "../manual/src/main/paradox/code/openapi.json"),
    scanResult = scanResult,
    write = false
  )

  val spec = generator.run()

  new CrdsGenerator(spec).run()
  // new FormsGenerator(spec).run()
}
