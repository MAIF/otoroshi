export default {
  "id": "cp:otoroshi.next.plugins.NgAuthModuleUserExtractor",
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
    }
  },
  "config_flow": [
    "module"
  ]
}