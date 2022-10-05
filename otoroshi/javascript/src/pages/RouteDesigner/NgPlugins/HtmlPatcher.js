export default {
  "id": "cp:otoroshi.next.plugins.NgHtmlPatcher",
  "config_schema": {
    "append_head": {
      "label": "Append to document head",
      "type": "array",
      "array": true,
      "format": null
    },
    "append_body": {
      "label": "Append to document body",
      "type": "array",
      "type": "array",
      "array": true,
      "format": null
    }
  },
  "config_flow": [
    "append_head",
    "append_body"
  ]
}