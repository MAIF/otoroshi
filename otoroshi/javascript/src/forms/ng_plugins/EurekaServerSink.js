export default {
  "id": "cp:otoroshi.next.plugins.EurekaServerSink",
  "config_schema": {
    "eviction_timeout": {
      "label": "eviction_timeout",
      "type": "number"
    }
  },
  "config_flow": [
    "eviction_timeout"
  ]
}