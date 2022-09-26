package otoroshi.openapi

import otoroshi.next.plugins.{AuthModule, ContextValidation, GraphQLBackend, GraphQLQuery, JwtVerification, MockFormData, NgAuthModuleConfig, NgAuthModuleExpectedUser, NgAuthModuleExpectedUserConfig, NgAuthModuleUserExtractor, NgAuthModuleUserExtractorConfig, NgJwtVerificationConfig, OtoroshiChallenge}
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
    classOf[NgJwtVerificationConfig].getName      -> Form(
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
    classOf[NgAuthModuleUserExtractor].getName      -> Form(
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
                 |  }
                 |}""".stripMargin)
        .asObject,
      flow = Seq("module")
    ),
    classOf[AuthModule].getName      -> Form(
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
    ),
    classOf[NgAuthModuleExpectedUser].getName      -> Form(
    schema = Json
      .parse("""{
               |  "only_from" : {
               |    "type": "array-select",
               |    "props": {
               |      "optionsFrom": "/bo/api/proxy/api/auths",
               |      "optionsTransformer": {
               |          "label": "name",
               |          "value": "id"
               |      }
               |    }
               |  }
               |}""".stripMargin)
      .asObject,
    flow = Seq("only_from")
  ),
    classOf[OtoroshiChallenge].getName      -> Form(
    schema = Json
      .parse("""{
               |    "version": { "type": "string" },
               |    "ttl": { "type": "number" },
               |    "request_header_name": { "type": "string" },
               |    "response_header_name": { "type": "string" },
               |    "algo_to_backend": {
               |  "format": "form",
               |  "array": true,
               |  "type": "object",
               |  "flow": [
               |    "type"
               |  ],
               |  "schema": {
               |    "type": {
               |      "format": "form",
               |      "label": "Algo settings",
               |      "flow": {
               |        "field": "type.type",
               |        "flow": {
               |          "HSAlgoSettings": [
               |            "type",
               |            "size",
               |            "secret",
               |            "base64"
               |          ],
               |          "RSAlgoSettings": [
               |            "type",
               |            "size",
               |            "publicKey",
               |            "privateKey"
               |          ],
               |          "ESAlgoSettings": [
               |            "type",
               |            "size",
               |            "publicKey",
               |            "privateKey"
               |          ],
               |          "JWKSAlgoSettings": [
               |            "type",
               |            "url",
               |            "headers",
               |            "timeout",
               |            "ttl",
               |            "kty",
               |            "proxy",
               |            "mtlsConfig"
               |          ],
               |          "RSAKPAlgoSettings": [
               |            "type",
               |            "size",
               |            "certId"
               |          ],
               |          "ESKPAlgoSettings": [
               |            "type",
               |            "size",
               |            "certId"
               |          ],
               |          "KidAlgoSettings": [
               |            "type",
               |            "onlyExposedCerts"
               |          ]
               |        }
               |      },
               |      "schema": {
               |        "type": {
               |      "type": "select",
               |      "props": {
               |        "label": "Type",
               |        "options": [
               |          "HSAlgoSettings",
               |          "RSAlgoSettings",
               |          "ESAlgoSettings",
               |          "JWKSAlgoSettings",
               |          "RSAKPAlgoSettings",
               |          "ESKPAlgoSettings",
               |          "KidAlgoSettings"
               |        ]
               |      }
               |    },
               |        "size": {
               |          "type": "number"
               |        },
               |        "secret": {
               |          "type": "string"
               |        },
               |        "base64": {
               |          "type": "boolean"
               |        },
               |        "publicKey": {
               |          "type": "string"
               |        },
               |        "privateKey": {
               |          "type": "string"
               |        },
               |        "url": {
               |          "type": "string"
               |        },
               |        "headers": {
               |          "type": "object",
               |          "props": {
               |            "label": "Headers"
               |          }
               |        },
               |        "timeout": {
               |          "type": "number"
               |        },
               |        "ttl": {
               |          "type": "number"
               |        },
               |        "kty": {
               |          "type": "string"
               |        },
               |        "mtlsConfig": {
               |          "type": "object",
               |          "format": "form",
               |          "flow": [
               |            "certs",
               |            "trustedCerts",
               |            "mtls",
               |            "loose",
               |            "trustAll"
               |          ],
               |          "schema": {
               |            "certs": {
               |              "type": "array-select",
               |              "props": {
               |                "label": "Certificates",
               |                "optionsFrom": "/bo/api/proxy/api/certificates",
               |                "optionsTransformer": {
               |                "label": "name",
               |                  "value": "id"
               |                }
               |             }
               |            },
               |            "trustedCerts": {
               |              "type": "array-select",
               |              "props": {
               |                "label": "Trusted certificates",
               |                "optionsFrom": "/bo/api/proxy/api/certificates",
               |                "optionsTransformer": {
               |                  "label": "name",
               |                 "value": "id"
               |                }
               |              }
               |            },
               |            "mtls": {
               |              "type": "boolean"
               |            },
               |            "loose": {
               |              "type": "boolean"
               |            },
               |            "trustAll": {
               |              "type": "boolean"
               |            }
               |          }
               |        },
               |        "proxy": {
               |          "format": "form",
               |          "flow": [
               |            "host",
               |            "port",
               |            "protocol",
               |            "principal",
               |            "password",
               |            "ntlmDomain",
               |            "encoding",
               |            "nonProxyHosts"
               |          ],
               |          "schema": {
               |            "host": {
               |              "type": "string"
               |            },
               |            "port": {
               |              "type": "number"
               |            },
               |            "protocol": {
               |              "type": "string"
               |            },
               |            "principal": {
               |              "type": "string"
               |            },
               |            "password": {
               |              "type": "string"
               |            },
               |            "ntlmDomain": {
               |              "type": "string"
               |            },
               |            "encoding": {
               |              "type": "string"
               |            },
               |            "nonProxyHosts": {
               |              "type": "array",
               |              "props": {
               |                "label": "Non proxy hosts"
               |              }
               |            }
               |          }
               |        },
               |        "certId": {
               |          "type": "array-select",
               |          "props": {
               |            "label": "Certificates",
               |            "optionsFrom": "/bo/api/proxy/api/certificates",
               |            "optionsTransformer": {
               |              "label": "name",
               |              "value": "id"
               |            }
               |          }
               |        },
               |        "onlyExposedCerts": {
               |          "type": "boolean"
               |        }
               |      }
               |    }
               |  }
               |},
               |    "algo_from_backend": {
               |  "format": "form",
               |  "array": true,
               |  "type": "object",
               |  "flow": [
               |    "type"
               |  ],
               |  "schema": {
               |    "type": {
               |      "format": "form",
               |      "label": "Algo settings",
               |      "flow": {
               |        "field": "type.type",
               |        "flow": {
               |          "HSAlgoSettings": [
               |            "type",
               |            "size",
               |            "secret",
               |            "base64"
               |          ],
               |          "RSAlgoSettings": [
               |            "type",
               |            "size",
               |            "publicKey",
               |            "privateKey"
               |          ],
               |          "ESAlgoSettings": [
               |            "type",
               |            "size",
               |            "publicKey",
               |            "privateKey"
               |          ],
               |          "JWKSAlgoSettings": [
               |            "type",
               |            "url",
               |            "headers",
               |            "timeout",
               |            "ttl",
               |            "kty",
               |            "proxy",
               |            "mtlsConfig"
               |          ],
               |          "RSAKPAlgoSettings": [
               |            "type",
               |            "size",
               |            "certId"
               |          ],
               |          "ESKPAlgoSettings": [
               |            "type",
               |            "size",
               |            "certId"
               |          ],
               |          "KidAlgoSettings": [
               |            "type",
               |            "onlyExposedCerts"
               |          ]
               |        }
               |      },
               |      "schema": {
               |        "type": {
               |      "type": "select",
               |      "props": {
               |        "label": "Type",
               |        "options": [
               |          "HSAlgoSettings",
               |          "RSAlgoSettings",
               |          "ESAlgoSettings",
               |          "JWKSAlgoSettings",
               |          "RSAKPAlgoSettings",
               |          "ESKPAlgoSettings",
               |          "KidAlgoSettings"
               |        ]
               |      }
               |    },
               |        "size": {
               |          "type": "number"
               |        },
               |        "secret": {
               |          "type": "string"
               |        },
               |        "base64": {
               |          "type": "boolean"
               |        },
               |        "publicKey": {
               |          "type": "string"
               |        },
               |        "privateKey": {
               |          "type": "string"
               |        },
               |        "url": {
               |          "type": "string"
               |        },
               |        "headers": {
               |          "type": "object",
               |          "props": {
               |            "label": "Headers"
               |          }
               |        },
               |        "timeout": {
               |          "type": "number"
               |        },
               |        "ttl": {
               |          "type": "number"
               |        },
               |        "kty": {
               |          "type": "string"
               |        },
               |        "mtlsConfig": {
               |          "type": "object",
               |          "format": "form",
               |          "flow": [
               |            "certs",
               |            "trustedCerts",
               |            "mtls",
               |            "loose",
               |            "trustAll"
               |          ],
               |          "schema": {
               |            "certs": {
               |              "type": "array-select",
               |              "props": {
               |                "label": "Certificates",
               |                "optionsFrom": "/bo/api/proxy/api/certificates",
               |                "optionsTransformer": {
               |                "label": "name",
               |                  "value": "id"
               |                }
               |             }
               |            },
               |            "trustedCerts": {
               |              "type": "array-select",
               |              "props": {
               |                "label": "Trusted certificates",
               |                "optionsFrom": "/bo/api/proxy/api/certificates",
               |                "optionsTransformer": {
               |                  "label": "name",
               |                 "value": "id"
               |                }
               |              }
               |            },
               |            "mtls": {
               |              "type": "boolean"
               |            },
               |            "loose": {
               |              "type": "boolean"
               |            },
               |            "trustAll": {
               |              "type": "boolean"
               |            }
               |          }
               |        },
               |        "proxy": {
               |          "format": "form",
               |          "flow": [
               |            "host",
               |            "port",
               |            "protocol",
               |            "principal",
               |            "password",
               |            "ntlmDomain",
               |            "encoding",
               |            "nonProxyHosts"
               |          ],
               |          "schema": {
               |            "host": {
               |              "type": "string"
               |            },
               |            "port": {
               |              "type": "number"
               |            },
               |            "protocol": {
               |              "type": "string"
               |            },
               |            "principal": {
               |              "type": "string"
               |            },
               |            "password": {
               |              "type": "string"
               |            },
               |            "ntlmDomain": {
               |              "type": "string"
               |            },
               |            "encoding": {
               |              "type": "string"
               |            },
               |            "nonProxyHosts": {
               |              "type": "array",
               |              "props": {
               |                "label": "Non proxy hosts"
               |              }
               |            }
               |          }
               |        },
               |        "certId": {
               |          "type": "array-select",
               |          "props": {
               |            "label": "Certificates",
               |            "optionsFrom": "/bo/api/proxy/api/certificates",
               |            "optionsTransformer": {
               |              "label": "name",
               |              "value": "id"
               |            }
               |          }
               |        },
               |        "onlyExposedCerts": {
               |          "type": "boolean"
               |        }
               |      }
               |    }
               |  }
               |},
               |    "state_resp_leeway": {
               |      "type": "number"
               |    }
               |}
               |""".stripMargin)
      .asObject,
    flow = Seq("version",
          "ttl",
          "request_header_name",
          "response_header_name",
          "algo_to_backend",
          "algo_from_backend",
          "state_resp_leeway"
    )
  )
  )
}
