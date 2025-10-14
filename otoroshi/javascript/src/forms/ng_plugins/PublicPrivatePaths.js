export default {
  id: 'cp:otoroshi.next.plugins.PublicPrivatePaths',
  config_schema: {
    private_patterns: {
      label: 'private_patterns',
      type: 'array',
      array: true,
      format: null,
    },
    public_patterns: {
      label: 'public_patterns',
      type: 'array',
      array: true,
      format: null,
    },
    strict: {
      type: 'box-bool',
      props: {
        label: 'strict',
        description: 'Strict mode = only an API key is accepted.'
      },
    },
  },
  config_flow: ['private_patterns', 'public_patterns', 'strict'],
};
