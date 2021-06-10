package functional

import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import otoroshi.script.PluginDocumentationGenerator

class PluginDocSpec extends WordSpec with MustMatchers with OptionValues {

  val generator = new PluginDocumentationGenerator("../manual")
  generator.run()
}
