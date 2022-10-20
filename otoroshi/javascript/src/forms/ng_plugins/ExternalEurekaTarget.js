export default {
  "id": "cp:otoroshi.next.plugins.ExternalEurekaTarget",
  "config_schema": {
    "eureka_server": {
      "label": "eureka_server",
      "type": "string"
    },
    "eureka_app": {
      "label": "eureka_app",
      "type": "string"
    }
  },
  "config_flow": [
    "eureka_app",
    "eureka_server"
  ]
}