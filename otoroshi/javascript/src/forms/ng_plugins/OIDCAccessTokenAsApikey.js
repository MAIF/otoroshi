export default {
  id: 'cp:otoroshi.next.plugins.OIDCAccessTokenAsApikey',
  config_flow: [
    "enabled",
    "atLeastOne",
    "config"
  ],
  config_schema: {
    enabled: {
      type: "bool",
      label: "Enabled"
    },
    atLeastOne: {
      type: "bool",
      label: "At least one"
    }, 
    config: {
      ngOptions: {
        spread: true,
      },
      type: 'json',
      props: {
        editorOnly: true
      },
    }
  }
};