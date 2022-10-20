export default {
  "id": "cp:otoroshi.next.plugins.JwtVerification",
  "config_schema": {
    "verifiers": {
      "type": "array-select",
      "props": {
        "optionsFrom": "/bo/api/proxy/api/verifiers",
        "optionsTransformer": {
          "label": "name",
          "value": "id"
        }
      }
    }
  },
  "config_flow": [
    "verifiers"
  ]
}