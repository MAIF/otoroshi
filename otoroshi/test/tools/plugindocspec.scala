package tools

import org.scalatest.{OptionValues}
import otoroshi.next.doc.NgPluginDocumentationGenerator
import otoroshi.script.PluginDocumentationGenerator

class PluginDocSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers with OptionValues {

  val generator = new PluginDocumentationGenerator("../manual")
  generator.runOnePage()
}

class PluginDocNextSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers with OptionValues {

  val generator = new PluginDocumentationGenerator("../manual")
  generator.runOnePage()
}

class PluginDocNgSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers with OptionValues {

  val generator = new NgPluginDocumentationGenerator("../manual")
  generator.runOnePage()
}
