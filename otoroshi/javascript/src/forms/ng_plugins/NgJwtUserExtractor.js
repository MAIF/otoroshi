export default {
  id: 'cp:otoroshi.next.plugins.NgJwtUserExtractor',
  config_schema: {
    verifier: {
      type: 'string',
      label: 'JWT verifier id',
    },
    strict: {
      type: 'bool',
      label: 'Strict validation',
    },
    strip: {
      type: 'bool',
      label: 'Stripped token',
    },
    name_path: {
      type: 'string',
      label: 'Path to extract username',
    },
    email_path: {
      type: 'string',
      label: 'Path to extract user email',
    },
    meta_path: {
      type: 'string',
      label: 'Path to extract user metadata',
    },
  },
  config_flow: [
    'verifier',
    'strict',
    'strip',
    'name_path',
    'email_path',
    'meta_path',
  ],
};