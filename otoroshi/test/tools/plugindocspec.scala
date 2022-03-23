package tools

import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import otoroshi.next.doc.NgPluginDocumentationGenerator
import otoroshi.script.PluginDocumentationGenerator

class PluginDocSpec extends WordSpec with MustMatchers with OptionValues {

  val generator = new PluginDocumentationGenerator("../manual")
  generator.runOnePage()
}

class PluginDocNextSpec extends WordSpec with MustMatchers with OptionValues {

  val generator = new PluginDocumentationGenerator("../manual")
  generator.runOnePage()
}

class PluginDocNgSpec extends WordSpec with MustMatchers with OptionValues {

  val generator = new NgPluginDocumentationGenerator("../manual")
  generator.runOnePage()
}

