package otoroshi.openapi

import otoroshi.next.plugins.{ContextValidation, EurekaTarget, GraphQLBackend, GraphQLQuery, JwtVerification, MockFormData, NgAuthModuleConfig}
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
          |        "type" : "code",
          |        "props": {
          |         "label": "Value",
          |         "type": "json",
          |         "editorOnly": true
          |        }
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
          |    "type" : "code",
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
          |    "type" : "code",
          |    "props": {
          |       "editorOnly": true
          |    }
          |  },
          |  "permissions": {
          |     "label": "Permissions",
          |     "type": "array"
          |   },
          |  "initialData": {
          |     "label": "Predefined data",
          |     "type": "json"
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
    ),
    classOf[JwtVerification].getName      -> Form(
      flow = Seq("verifiers"),
      schema = Json
        .parse("""{
                 |  "verifiers" : {
                 |    "type": "array-select",
                 |    "props": {
                 |      "optionsFrom": "/bo/api/proxy/api/verifiers",
                 |      "optionsTransformer": {
                 |          "label": "name",
                 |          "value": "id"
                 |      }
                 |    }
                 |  }
                 |}""".stripMargin)
        .asObject
    ),
    classOf[NgAuthModuleConfig].getName      -> Form(
      schema = Json
        .parse("""{
                 |  "module" : {
                 |    "type": "array-select",
                 |    "props": {
                 |      "optionsFrom": "/bo/api/proxy/api/auths",
                 |      "optionsTransformer": {
                 |          "label": "name",
                 |          "value": "id"
                 |      }
                 |    }
                 |  },
                 |  "pass_with_apikey": {
                 |    "type": "boolean"
                 |  }
                 |}""".stripMargin)
        .asObject,
      flow = Seq("module", "pass_with_apikey")
    ),
    classOf[MockFormData].getName      -> Form(
      schema = Json
        .parse("""{
                 |  "endpoints" : {
                 |    "type" : "object",
                 |    "array" : true,
                 |    "format" : "form",
                 |    "flow": ["method", "path", "status", "body", "resource", "resourceList", "headers", "length"],
                 |    "schema": {
                 |       "method": {
                 |          "type": "string"
                 |       },
                 |       "path": {
                 |          "type": "string"
                 |       },
                 |       "status": {
                 |          "type": "number"
                 |       },
                 |       "body": {
                 |          "type": "code",
                 |          "props": {
                 |              "label": "Body",
                 |              "editorOnly": true,
                 |              "mode": "json"
                 |          }
                 |       },
                 |       "resource": {
                 |          "type": "string"
                 |       },
                 |       "resourceList": {
                 |          "type": "bool"
                 |       },
                 |       "headers": {
                 |          "type": "object",
                 |          "props": {
                 |            "label": "Headers"
                 |          }
                 |       },
                 |       "length": {
                 |          "type": "number"
                 |       }
                 |    }
                 |  },
                 |  "resources": {
                 |    "type" : "object",
                 |    "array" : true,
                 |    "format" : "form",
                 |    "flow": ["name", "schema", "additional_data"],
                 |    "schema": {
                 |       "name": {
                 |          "type": "string"
                 |       },
                 |       "schema": {
                 |          "type": "object",
                 |          "format" : "form",
                 |          "array": true,
                 |          "flow": ["field_name", "field_type", "value"],
                 |          "schema": {
                 |             "field_name": {
                 |                "type": "string",
                 |                "props": {
                 |                  "label": "Field name"
                 |                 }
                 |             },
                 |             "field_type": {
                 |                "type": "select",
                 |                "props": {
                 |                  "label": "Field type",
                 |                  "options": [
                 |                      "String",
                 |                      "Number",
                 |                      "Boolean",
                 |                      "Object",
                 |                      "Array",
                 |                      "Date",
                 |                      "Child"
                 |                   ]
                 |                 }
                 |             },
                 |             "value":{
                 |                "type": "code",
                 |                "props": {
                 |                  "label": "Value",
                 |                  "mode": "json",
                 |                  "editorOnly": true
                 |                }
                 |             }
                 |          },
                 |          "props": {
                 |             "label": "Schema"
                 |          }
                 |       },
                 |       "additional_data": {
                 |          "type": "code",
                 |          "props": {
                 |            "label": "Additional data",
                 |            "mode": "json",
                 |            "editorOnly": true
                 |          }
                 |       }
                 |    },
                 |    "props": {
                 |      "label": "Resources"
                 |    }
                 |  }
                 |}""".stripMargin)
        .asObject,
      flow = Seq("endpoints", "resources")
    )
  )
}
