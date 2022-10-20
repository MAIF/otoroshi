export default {
  "id": "cp:otoroshi.next.plugins.GzipResponseCompressor",
  "config_schema": {
    "chunked_threshold": {
      "label": "chunked_threshold",
      "type": "number"
    },
    "white_list": {
      "label": "white_list",
      "type": "array",
      "array": true,
      "format": null
    },
    "excluded_patterns": {
      "label": "excluded_patterns",
      "type": "array",
      "array": true,
      "format": null
    },
    "black_list": {
      "label": "black_list",
      "type": "array",
      "array": true,
      "format": null
    },
    "compression_level": {
      "label": "compression_level",
      "type": "number"
    },
    "buffer_size": {
      "label": "buffer_size",
      "type": "number"
    }
  },
  "config_flow": [
    "black_list",
    "white_list",
    "excluded_patterns",
    "chunked_threshold",
    "buffer_size",
    "compression_level"
  ]
}