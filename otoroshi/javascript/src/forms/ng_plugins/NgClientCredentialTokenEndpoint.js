export default {
  id: 'cp:otoroshi.next.plugins.NgClientCredentialTokenEndpoint',
  config_schema: {
    default_key_pair: {
      label: 'Default keypair id',
      type: 'select',
      props: {
        optionsFrom: "/bo/api/proxy/api/certificates?keypair=true",
        optionsTransformer: {
          label: 'name',
          value: 'id',
        },
      }
    },
    expiration: {
      type: 'number',
      label: 'Token lifetime (ms)',
    },
  },
  config_flow: ['default_key_pair', 'expiration'],
};
