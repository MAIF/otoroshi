export default {
  id: 'cp:otoroshi.next.plugins.NgClientCredentialTokenEndpoint',
  config_schema: {
    default_key_pair: {
      type: 'string',
      label: 'Default keypair id',
    },
    expiration: {
      type: 'number',
      label: 'Token lifetime (ms)',
    }
  },
  config_flow: [
    'default_key_pair',
    'expiration',
  ],
};
