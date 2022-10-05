export default {
  "id": "cp:otoroshi.next.plugins.JQ",
  "config_schema": {
    "request": {
      "label": "request",
      "type": "string"
    },
    "response": {
      "label": "response",
      "type": "string"
    }
  },
  "config_flow": [
    "response",
    "request"
  ]
}