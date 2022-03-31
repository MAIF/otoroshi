package tools

import io.github.classgraph.ClassGraph
import org.scalatest.OptionValues
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import otoroshi.openapi.{CrdsGenerator, OpenApiGenerator}

class OpenApiSpec extends AnyWordSpec with Matchers with OptionValues {

  val scanResult = new ClassGraph()
    .addClassLoader(this.getClass.getClassLoader)
    .enableAllInfo()
    .acceptPackages(Seq("otoroshi", "otoroshi_plugins", "play.api.libs.ws"): _*)
    .scan

  val generator = new OpenApiGenerator(
    "./conf/routes",
    "./app/openapi/openapi-cfg.json",
    Seq(
      "./public/openapi.json",
      "./app/openapi/openapi.json",
      "../manual/src/main/paradox/code/openapi.json"
    ),
    scanResult = scanResult,
    write = true
  )

  val spec = generator.run()

  new CrdsGenerator(spec).run()
}
