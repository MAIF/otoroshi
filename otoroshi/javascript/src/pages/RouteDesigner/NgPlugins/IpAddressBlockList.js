export default {
  "id": "cp:otoroshi.next.plugins.IpAddressBlockList",
  "config_schema": {
    "addresses": {
      "label": "addresses",
      "type": "array",
      "array": true,
      "format": null
    }
  },
  "config_flow": [
    "addresses"
  ]
}