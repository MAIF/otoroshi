export default {
  "id": "cp:otoroshi.next.plugins.AuthModule",
  "config_schema": {
    "module": {
      "type": "array-select",
      "props": {
        "optionsFrom": "/bo/api/proxy/api/auths",
        "optionsTransformer": {
          "label": "name",
          "value": "id"
        }
      }
    },
    "pass_with_apikey": {
      "type": "boolean"
    }
  },
  "config_flow": [
    "module",
    "pass_with_apikey"
  ]
}