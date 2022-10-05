export default {
  "id": "cp:otoroshi.next.plugins.Redirection",
  "config_schema": {
    "code": {
      "label": "code",
      "type": "number"
    },
    "to": {
      "label": "to",
      "type": "string"
    }
  },
  "config_flow": [
    "to",
    "code"
  ]
}