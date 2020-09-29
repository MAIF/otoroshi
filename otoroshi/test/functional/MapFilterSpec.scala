package functional

import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import play.api.libs.json.{JsObject, Json}

class MapFilterSpec extends WordSpec with MustMatchers with OptionValues {

  val source = Json.parse(
    """
      |{
      |  "foo": "bar",
      |  "type": "AlertEvent",
      |  "alert": "big-alert",
      |  "status": 200,
      |  "inner": {
      |    "foo": "bar",
      |    "bar": "foo"
      |  }
      |}
      |""".stripMargin).as[JsObject]

  "Match and Project utils" should {
    "match objects" in {
      utils.Match.matches(source, Json.obj("foo" -> "bar")) mustBe true
      utils.Match.matches(source, Json.obj("foo" -> "baz")) mustBe false
      utils.Match.matches(source, Json.obj("foo" -> "bar", "type" -> Json.obj("$wildcard" -> "Alert*"))) mustBe true
      utils.Match.matches(source, Json.obj("foo" -> "bar", "type" -> Json.obj("$wildcard" -> "Foo*"))) mustBe false
      utils.Match.matches(source, Json.obj("foo" -> "bar", "inner" -> Json.obj("foo" -> "bar"), "type" -> Json.obj("$wildcard" -> "Alert*"))) mustBe true
      utils.Match.matches(source, Json.obj("foo" -> "bar", "inner" -> Json.obj("foo" -> "baz"), "type" -> Json.obj("$wildcard" -> "Alert*"))) mustBe false
      utils.Match.matches(source, Json.obj("status" -> 200)) mustBe true
      utils.Match.matches(source, Json.obj("status" -> 201)) mustBe false
      utils.Match.matches(source, Json.obj("status" -> Json.obj("$gt" -> 100))) mustBe true
      utils.Match.matches(source, Json.obj("status" -> Json.obj("$gt" -> 200))) mustBe false
      utils.Match.matches(source, Json.obj("status" -> Json.obj("$gte" -> 200))) mustBe true
      utils.Match.matches(source, Json.obj("status" -> Json.obj("$lt" -> 201))) mustBe true
      utils.Match.matches(source, Json.obj("status" -> Json.obj("$lt" -> 200))) mustBe false
      utils.Match.matches(source, Json.obj("status" -> Json.obj("$lte" -> 200))) mustBe true
      utils.Match.matches(source, Json.obj("status" -> Json.obj("$between" -> Json.obj("min" -> 100, "max" -> 300)))) mustBe true
      utils.Match.matches(source, Json.obj("inner" -> Json.obj("$and" -> Json.arr(Json.obj("foo" -> "bar"), Json.obj("bar" -> "foo" ))))) mustBe true
      utils.Match.matches(source, Json.obj("inner" -> Json.obj("$and" -> Json.arr(Json.obj("foo" -> "bar"), Json.obj("bar" -> "fooo" ))))) mustBe false
      utils.Match.matches(source, Json.obj("inner" -> Json.obj("$or" -> Json.arr(Json.obj("foo" -> "bar"), Json.obj("bar" -> "fooo" ))))) mustBe true
    }
    "project objects" in {
      utils.Project.project(source, Json.obj("foo" -> true, "status" -> true)) mustBe Json.obj("foo" -> "bar", "status" -> 200)
      utils.Project.project(source, Json.obj("foo" -> true, "inner" -> true)) mustBe Json.obj("foo" -> "bar", "inner" -> Json.obj("foo" -> "bar", "bar" -> "foo"))
      utils.Project.project(source, Json.obj("foo" -> true, "inner" -> Json.obj("foo" -> true))) mustBe Json.obj("foo" -> "bar", "inner" -> Json.obj("foo" -> "bar"))
    }
  }

}
