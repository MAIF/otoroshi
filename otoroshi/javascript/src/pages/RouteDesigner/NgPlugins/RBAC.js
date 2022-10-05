export default {
  "id": "cp:otoroshi.next.plugins.RBAC",
  "config_schema": {
    "allow": {
      "label": "allow",
      "type": "array",
      "array": true,
      "format": null
    },
    "deny": {
      "label": "deny",
      "type": "array",
      "array": true,
      "format": null
    },
    "role_prefix": {
      "label": "role_prefix",
      "type": "string"
    },
    "user_path": {
      "label": "user_path",
      "type": "string"
    },
    "roles": {
      "label": "roles",
      "type": "string"
    },
    "apikey_path": {
      "label": "apikey_path",
      "type": "string"
    },
    "allow_all": {
      "label": "allow_all",
      "type": "bool"
    },
    "deny_all": {
      "label": "deny_all",
      "type": "bool"
    },
    "jwt_path": {
      "label": "jwt_path",
      "type": "string"
    }
  },
  "config_flow": [
    "jwt_path",
    "deny_all",
    "allow_all",
    "apikey_path",
    "roles",
    "user_path",
    "role_prefix",
    "deny",
    "allow"
  ]
}