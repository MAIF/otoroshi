package plugins.configs

import otoroshi.next.plugins.*
import play.api.libs.json.*

class QueryPluginConfigsSpec extends PluginConfigFormatSpec {

  "QueryTransformerConfig" should {

    val format = QueryTransformerConfig.format

    val full = QueryTransformerConfig(
      remove = Seq("debug", "token"),
      rename = Map("q" -> "query", "p" -> "page"),
      add = Map("source" -> "otoroshi", "version" -> "2")
    )

    "write the default config as empty collections" in {
      format.writes(QueryTransformerConfig()) mustBe Json.obj(
        "remove" -> Json.arr(),
        "rename" -> Json.obj(),
        "add"    -> Json.obj()
      )
    }

    "round trip the default config" in {
      roundTrip(format, QueryTransformerConfig())
    }

    "round trip a config where every field is set" in {
      roundTrip(format, full)
    }

    "write a config where every field is set" in {
      format.writes(full) mustBe Json.obj(
        "remove" -> Json.arr("debug", "token"),
        "rename" -> Json.obj("q" -> "query", "p" -> "page"),
        "add"    -> Json.obj("source" -> "otoroshi", "version" -> "2")
      )
    }

    "round trip removed params and keep their order" in {
      roundTrip(format, QueryTransformerConfig(remove = Seq("c", "a", "b"))).remove mustBe Seq("c", "a", "b")
    }

    "round trip renamed params alone" in {
      roundTrip(format, QueryTransformerConfig(rename = Map("old" -> "new")))
    }

    "round trip added params alone" in {
      roundTrip(format, QueryTransformerConfig(add = Map("foo" -> "bar")))
    }

    "round trip added params holding expression language and empty values" in {
      roundTrip(format, QueryTransformerConfig(add = Map("user" -> "${user.email}", "empty" -> "")))
    }

    "round trip param names with special characters" in {
      roundTrip(format, QueryTransformerConfig(remove = Seq("a[]", "b c", "é"), rename = Map("x-y" -> "x_y")))
    }

    "read an empty object as the default config" in {
      readOk(format, Json.obj()) mustBe QueryTransformerConfig()
    }

    "read a json null as the default config" in {
      readOk(format, JsNull) mustBe QueryTransformerConfig()
    }

    "read a json string as the default config" in {
      readOk(format, JsString("debug")) mustBe QueryTransformerConfig()
    }

    "read a json array as the default config" in {
      readOk(format, Json.arr("debug")) mustBe QueryTransformerConfig()
    }

    "read remove alone and default the rest" in {
      readOk(format, Json.obj("remove" -> Json.arr("debug"))) mustBe QueryTransformerConfig(remove = Seq("debug"))
    }

    "read add alone and default the rest" in {
      readOk(format, Json.obj("add" -> Json.obj("a" -> "b"))) mustBe QueryTransformerConfig(add = Map("a" -> "b"))
    }

    "ignore a remove written as a single string" in {
      readOk(format, Json.obj("remove" -> "debug")).remove mustBe empty
    }

    "ignore a remove array holding something else than strings" in {
      readOk(format, Json.obj("remove" -> Json.arr("debug", 1))).remove mustBe empty
    }

    "ignore a rename written as an array" in {
      readOk(format, Json.obj("rename" -> Json.arr("q", "query"))).rename mustBe empty
    }

    "ignore the whole rename map as soon as a value is not a string" in {
      readOk(format, Json.obj("rename" -> Json.obj("q" -> "query", "p" -> 1))).rename mustBe empty
    }

    "ignore the whole add map as soon as a value is not a string" in {
      readOk(format, Json.obj("add" -> Json.obj("a" -> "b", "n" -> 2))).add mustBe empty
    }

    "keep the valid fields when another one has a wrong type" in {
      readOk(format, Json.obj("remove" -> 42, "add" -> Json.obj("a" -> "b"))) mustBe QueryTransformerConfig(
        add = Map("a" -> "b")
      )
    }

    "ignore unknown keys" in {
      readOk(format, Json.obj("remove" -> Json.arr("a"), "replace" -> Json.obj("b" -> "c"))) mustBe
        QueryTransformerConfig(remove = Seq("a"))
    }

    "expose through json what the format writes" in {
      jsonMatchesFormat(format, full)
    }

    "be the default config of QueryTransformer" in {
      defaultConfigReadsBack(new QueryTransformer(), format) mustBe QueryTransformerConfig()
    }
  }
}
