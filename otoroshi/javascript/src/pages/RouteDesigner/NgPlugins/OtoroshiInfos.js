export default {
  "id": "cp:otoroshi.next.plugins.OtoroshiInfos",
  "config_schema": {
    "header_name": {
      "type": "string",
      "props": {
        "label": "Header name"
      }
    },
    "version": {
      "type": "string",
      "props": {
        "label": "Version"
      }
    },
    "ttl": {
      "type": "number",
      "props": {
        "label": "Time to live"
      }
    },
    "algo": {
      "format": "form",
      "type": "object",
      "flow": [
        "type"
      ],
      "props": {
        "label": " "
      },
      "schema": {
        "type": {
          "format": "form",
          "label": "Algo settings",
          "flow": {
            "field": "type.type",
            "flow": {
              "HSAlgoSettings": [
                "type",
                "size",
                "secret",
                "base64"
              ],
              "RSAlgoSettings": [
                "type",
                "size",
                "publicKey",
                "privateKey"
              ],
              "ESAlgoSettings": [
                "type",
                "size",
                "publicKey",
                "privateKey"
              ],
              "JWKSAlgoSettings": [
                "type",
                "url",
                "headers",
                "timeout",
                "ttl",
                "kty",
                "proxy",
                "mtlsConfig"
              ],
              "RSAKPAlgoSettings": [
                "type",
                "size",
                "certId"
              ],
              "ESKPAlgoSettings": [
                "type",
                "size",
                "certId"
              ],
              "KidAlgoSettings": [
                "type",
                "onlyExposedCerts"
              ]
            }
          },
          "schema": {
            "type": {
              "type": "select",
              "props": {
                "label": "Type",
                "options": [
                  "HSAlgoSettings",
                  "RSAlgoSettings",
                  "ESAlgoSettings",
                  "JWKSAlgoSettings",
                  "RSAKPAlgoSettings",
                  "ESKPAlgoSettings",
                  "KidAlgoSettings"
                ]
              }
            },
            "size": {
              "type": "number"
            },
            "secret": {
              "type": "string"
            },
            "base64": {
              "type": "boolean"
            },
            "publicKey": {
              "type": "string"
            },
            "privateKey": {
              "type": "string"
            },
            "url": {
              "type": "string"
            },
            "headers": {
              "type": "object",
              "props": {
                "label": "Headers"
              }
            },
            "timeout": {
              "type": "number"
            },
            "ttl": {
              "type": "number"
            },
            "kty": {
              "type": "string"
            },
            "mtlsConfig": {
              "type": "object",
              "format": "form",
              "flow": [
                "certs",
                "trustedCerts",
                "mtls",
                "loose",
                "trustAll"
              ],
              "schema": {
                "certs": {
                  "type": "array-select",
                  "props": {
                    "label": "Certificates",
                    "optionsFrom": "/bo/api/proxy/api/certificates",
                    "optionsTransformer": {
                      "label": "name",
                      "value": "id"
                    }
                  }
                },
                "trustedCerts": {
                  "type": "array-select",
                  "props": {
                    "label": "Trusted certificates",
                    "optionsFrom": "/bo/api/proxy/api/certificates",
                    "optionsTransformer": {
                      "label": "name",
                      "value": "id"
                    }
                  }
                },
                "mtls": {
                  "type": "boolean"
                },
                "loose": {
                  "type": "boolean"
                },
                "trustAll": {
                  "type": "boolean"
                }
              }
            },
            "proxy": {
              "format": "form",
              "flow": [
                "host",
                "port",
                "protocol",
                "principal",
                "password",
                "ntlmDomain",
                "encoding",
                "nonProxyHosts"
              ],
              "schema": {
                "host": {
                  "type": "string"
                },
                "port": {
                  "type": "number"
                },
                "protocol": {
                  "type": "string"
                },
                "principal": {
                  "type": "string"
                },
                "password": {
                  "type": "string"
                },
                "ntlmDomain": {
                  "type": "string"
                },
                "encoding": {
                  "type": "string"
                },
                "nonProxyHosts": {
                  "type": "array",
                  "props": {
                    "label": "Non proxy hosts"
                  }
                }
              }
            },
            "certId": {
              "type": "array-select",
              "props": {
                "label": "Certificates",
                "optionsFrom": "/bo/api/proxy/api/certificates",
                "optionsTransformer": {
                  "label": "name",
                  "value": "id"
                }
              }
            },
            "onlyExposedCerts": {
              "type": "boolean"
            }
          }
        }
      }
    }
  },
  "config_flow": [
    "version",
    "ttl",
    "header_name",
    "algo"
  ]
}