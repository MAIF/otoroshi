export default {
  "id": "cp:otoroshi.next.plugins.NgAuthModuleExpectedUser",
  "config_schema": {
    "only_from": {
      "type": "array-select",
      "props": {
        "optionsFrom": "/bo/api/proxy/api/auths",
        "optionsTransformer": {
          "label": "name",
          "value": "id"
        }
      }
    }
  },
  "config_flow": [
    "only_from"
  ]
}