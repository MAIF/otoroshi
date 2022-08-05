package otoroshi.openapi

import otoroshi.next.plugins.{ContextValidation, GraphQLBackend, GraphQLQuery}
import otoroshi.next.tunnel.TunnelPlugin
import otoroshi.utils.syntax.implicits.BetterJsReadable
import play.api.libs.json.Json

object CustomForms {

  val forms: Map[String, Form] = Map(
    classOf[ContextValidation].getName -> Form(
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
    classOf[GraphQLQuery].getName      -> Form(
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
    ),
    classOf[GraphQLBackend].getName    -> Form(
      flow = Seq("schema", "permissions", "initialData", "maxDepth"),
      schema = Json
        .parse("""{
          |  "schema" : {
          |    "label" : "Schema",
          |    "type" : "string",
          |    "format": "code"
          |  },
          |  "permissions": {
          |     "label": "Permissions",
          |     "type": "string",
          |     "array": true
          |   },
          |  "initialData": {
          |     "label": "Predefined data",
          |     "type": "object",
          |     "format": "code"
          |  },
          |  "maxDepth": {
          |     "label": "Max depth",
          |     "type": "number"
          |  }
          |}""".stripMargin)
        .asObject
    ),
    classOf[TunnelPlugin].getName      -> Form(
      flow = Seq("tunnel_id"),
      schema = Json
        .parse("""{
                 |  "tunnel_id" : {
                 |    "label" : "Tunnel ID",
                 |    "type" : "string"
                 |  }
                 |}""".stripMargin)
        .asObject
    )
  )
}
