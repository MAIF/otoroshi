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
    '<<<Biscuit',
    'biscuit.privkey',
    'biscuit.checks',
    'biscuit.facts',
    'biscuit.rules',
  ],
};
