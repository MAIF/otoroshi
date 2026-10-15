export default {
  id: 'cp:otoroshi.next.plugins.NgClientCredentials',
  config_schema: {
    default_key_pair: {
      type: 'string',
      label: 'Default keypair id',
    },
    expiration: {
      type: 'number',
      label: 'Token lifetime (ms)',
    },
    domain: {
      type: 'string',
      label: 'Domain name',
    },
    secure: {
      type: 'string',
      label: 'Secure',
    },
    jwks_cache_ttl: {
      type: 'number',
      label: 'JWKS cache TTL (ms)',
      props: {
        help: 'How long the JWKS keys are cached. Leave empty to use the global setting, 0 disables the cache',
      },
    },
    max_body_size: {
      type: 'number',
      label: 'Max body size (bytes)',
      props: {
        help: 'A token request body bigger than this is rejected with a 413',
      },
    },
    'biscuit.privkey': {
      type: 'array',
      array: true,
      format: null,
      label: 'Private key',
    },
    'biscuit.checks': {
      type: 'array',
      array: true,
      format: null,
      label: 'Checks',
    },
    'biscuit.facts': {
      type: 'array',
      array: true,
      format: null,
      label: 'Facts',
    },
    'biscuit.rules': {
      type: 'array',
      array: true,
      format: null,
      label: 'Rules',
    },
  },
  config_flow: [
    'default_key_pair',
    'expiration',
    'domain',
    'secure',
    'jwks_cache_ttl',
    'max_body_size',
    '<<<Biscuit',
    'biscuit.privkey',
    'biscuit.checks',
    'biscuit.facts',
    'biscuit.rules',
  ],
};
