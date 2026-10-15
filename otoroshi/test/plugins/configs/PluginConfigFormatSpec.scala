package plugins.configs

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import otoroshi.next.plugins.api.{NgNamedPlugin, NgPluginConfig}
import play.api.libs.json.*

/**
 * Base of the plugin configuration format specs.
 *
 * A plugin configuration lives as json in the route, is edited by the UI form and the admin api, and
 * is read again on every request by the plugin. These specs pin the json shape of each configuration
 * and check that nothing gets lost between `writes` and `reads`.
 *
 * Pure logic: no otoroshi instance, no datastore, no network. The specs are run by `UnitTests`.
 */
trait PluginConfigFormatSpec extends AnyWordSpec with Matchers {

  /** reads the json, fails the test with the json errors if it cannot */
  def readOk[A](format: Reads[A], json: JsValue): A = format.reads(json) match {
    case JsSuccess(value, _) => value
    case JsError(errors)     => fail(s"could not read ${Json.stringify(json)}: ${errors.mkString(", ")}")
  }

  /** checks that the json cannot be read */
  def readError[A](format: Reads[A], json: JsValue): Unit = {
    val result = format.reads(json)
    withClue(s"${Json.stringify(json)} was read as $result: ") {
      result.isError mustBe true
    }
  }

  /** writes the value, reads it back, and checks that the value and its json did not move */
  def roundTrip[A](format: Format[A], value: A): A = {
    val json = format.writes(value)
    val back = readOk(format, json)
    withClue(s"${Json.stringify(json)}: ") {
      back mustBe value
      format.writes(back) mustBe json
    }
    back
  }

  /** the json written for the value, as an object */
  def written[A](format: Writes[A], value: A): JsObject = format.writes(value) match {
    case obj: JsObject => obj
    case other         => fail(s"expected a json object, got ${Json.stringify(other)}")
  }

  /** the top level keys of the json written for the value */
  def writtenKeys[A](format: Writes[A], value: A): Set[String] = written(format, value).keys.toSet

  /** `NgPluginConfig.json` is what the plugin hands to the UI, it must be what the format writes */
  def jsonMatchesFormat[A <: NgPluginConfig](format: Writes[A], value: A): Unit = {
    value.json mustBe format.writes(value)
  }

  /**
   * the default configuration of a plugin is serialized for the UI and the admin api through
   * `defaultConfig`. reading it back must give the default configuration object again.
   */
  def defaultConfigReadsBack[A](plugin: NgNamedPlugin, format: Reads[A]): A = {
    val obj  = plugin.defaultConfigObject.getOrElse(fail(s"${plugin.getClass.getName} has no default config"))
    val json = plugin.defaultConfig.getOrElse(fail(s"${plugin.getClass.getName} has no default config json"))
    val back = readOk(format, json)
    back mustBe obj
    back
  }

  /** for a plugin that takes no configuration */
  def hasNoDefaultConfig(plugin: NgNamedPlugin): Unit = {
    plugin.defaultConfigObject mustBe None
    plugin.defaultConfig mustBe None
  }
}
