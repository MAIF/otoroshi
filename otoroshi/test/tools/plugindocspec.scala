package tools

import org.scalatest.matchers.must.Matchers
import org.scalatest.OptionValues
import org.scalatest.wordspec.AnyWordSpec
import otoroshi.next.doc.NgPluginDocumentationGenerator
import otoroshi.script.PluginDocumentationGenerator

class PluginDocSpec extends AnyWordSpec with Matchers with OptionValues {

  val generator = new PluginDocumentationGenerator("../manual")
  generator.runOnePage()
}

class PluginDocNextSpec extends AnyWordSpec with Matchers with OptionValues {

  val generator = new PluginDocumentationGenerator("../manual")
  generator.runOnePage()
}

class PluginDocNgSpec extends AnyWordSpec with Matchers with OptionValues {

  val generator = new NgPluginDocumentationGenerator("../manual")
  generator.runOnePage()
}
