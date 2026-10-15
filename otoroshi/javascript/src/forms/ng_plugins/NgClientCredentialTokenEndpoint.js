export default {
  id: 'cp:otoroshi.next.plugins.NgClientCredentialTokenEndpoint',
  config_schema: {
    default_key_pair: {
      label: 'Default keypair id',
      type: 'select',
      props: {
        optionsFrom: '/bo/api/proxy/api/certificates?keypair=true',
        optionsTransformer: {
          label: 'name',
          value: 'id',
        },
      },
    },
    expiration: {
      type: 'number',
      label: 'Token lifetime (ms)',
    },
    max_body_size: {
      type: 'number',
      label: 'Max body size (bytes)',
      props: {
        help: 'A token request body bigger than this is rejected with a 413',
      },
    },
  },
  config_flow: ['default_key_pair', 'expiration', 'max_body_size'],
};
