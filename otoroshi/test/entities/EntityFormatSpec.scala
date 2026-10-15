package entities

import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import otoroshi.models.{Entity, EntityLocation, TeamId, TenantId}
import play.api.libs.json.*

/**
 * Base of the entity format specs.
 *
 * An entity is stored as json, exported, imported, synced between cluster nodes and edited through the
 * admin api and the UI. These specs pin the json shape of the core entities and check that nothing gets
 * lost between `writes` and `reads`.
 *
 * Pure logic: no otoroshi instance, no datastore, no network. The specs are run by `UnitTests`.
 */
trait EntityFormatSpec extends AnyWordSpec with Matchers {

  /** a location that is not the default one */
  val customLocation: EntityLocation = EntityLocation(TenantId("tenant-1"), Seq(TeamId("team-1"), TeamId("team-2")))

  /** the json of the custom location */
  val customLocationJson: JsObject = Json.obj("tenant" -> "tenant-1", "teams" -> Json.arr("team-1", "team-2"))

  /** the json of the default location */
  val defaultLocationJson: JsObject = Json.obj("tenant" -> "default", "teams" -> Json.arr("default"))

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
  def writtenKeys[A](format: Writes[A], value: A): Set[String] = written(format, value).keys.toSeq.toSet

  /** `Entity.json` is what the admin api and the exports serve, it must be what the format writes */
  def entityJsonMatchesFormat[A <: Entity](format: Writes[A], entity: A): Unit = {
    entity.json mustBe format.writes(entity)
  }

  /** the generic accessors used by the admin api, the search and the UI tables */
  def hasAccessors(
      entity: Entity,
      id: String,
      name: String,
      description: String,
      tags: Seq[String],
      metadata: Map[String, String]
  ): Unit = {
    entity.theId mustBe id
    entity.internalId mustBe id
    entity.theName mustBe name
    entity.theDescription mustBe description
    entity.theTags mustBe tags
    entity.theMetadata mustBe metadata
  }
}
