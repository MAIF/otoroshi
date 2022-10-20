export default {
  "id": "cp:otoroshi.next.plugins.StaticResponse",
  "config_schema": {
    "headers": {
      "label": "headers",
      "type": "object"
    },
    "body": {
      "type": "code",
      "props": {
        "label": "body",
        "editorOnly": true,
        "mode": "json"
      }
    },
    "status": {
      "label": "status",
      "type": "number"
    }
  },
  "config_flow": [
    "body",
    "headers",
    "status"
  ]
}