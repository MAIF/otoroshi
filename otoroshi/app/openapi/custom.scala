package otoroshi.openapi

import otoroshi.utils.syntax.implicits.BetterJsReadable
import play.api.libs.json.Json

object CustomForms {

  val forms: Map[String, Form] = Map(
    "otoroshi.next.plugins.ContextValidation" -> Form(
      flow = Seq("validators"),
      schema = Json
        .parse("""{
          |  "validators" : {
          |    "label" : "validators",
          |    "type" : "object",
          |    "array" : true,
          |    "format" : "form",
          |    "schema" : {
          |      "path" : {
          |        "label" : "path",
          |        "type" : "string"
          |      },
          |      "value" : {
          |        "label" : "value",
          |        "type" : "object",
          |        "format": "code",
          |        "type": "json"
          |      }
          |    },
          |    "flow" : [ "path", "value" ]
          |  }
          |}
          |""".stripMargin)
        .asObject
    ),
    "otoroshi.next.plugins.GraphQLQuery"      -> Form(
      flow = Seq("url", "method", "headers", "timeout", "query", "response_filter", "response_path"),
      schema = Json
        .parse("""{
          |  "headers" : {
          |    "label" : "headers",
          |    "type" : "object"
          |  },
          |  "method" : {
          |    "label" : "method",
          |    "type" : "string"
          |  },
          |  "query" : {
          |    "label" : "GraphQL query",
          |    "type" : "string",
          |    "format": "code",
          |    "help": "The graphql query that will be sent to the graphql endpoint"
          |  },
          |  "response_filter" : {
          |    "label" : "response_filter",
          |    "type" : "string"
          |  },
          |  "response_path" : {
          |    "label" : "response_path",
          |    "type" : "string"
          |  },
          |  "url" : {
          |    "label" : "url",
          |    "type" : "string"
          |  },
          |  "timeout" : {
          |    "label" : "timeout",
          |    "type" : "number"
          |  }
          |}""".stripMargin)
        .asObject
    )
  )
}
