export default {
  "id": "cp:otoroshi.next.plugins.JwtVerification",
  "config_schema": {
    "verifiers": {
      "isMulti": true,
      "format": "select",
      "transformer": {
        "value": "id",
        "label": "name"
      },
      "type": "string",
      "props": {
        "optionsFrom": "/bo/api/proxy/api/verifiers",
        "optionsTransformer": {
          "label": "name",
          "value": "id"
        }
      },
      "optionsFrom": "/bo/api/proxy/api/verifiers"
    }
  },
  "config_flow": [
    "verifiers"
  ]
}