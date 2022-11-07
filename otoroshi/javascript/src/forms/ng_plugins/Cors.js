export default {
  id: 'cp:otoroshi.next.plugins.Cors',
  config_schema: {
    max_age: {
      label: 'max_age',
      type: 'string',
    },
    allow_headers: {
      label: 'allow_headers',
      type: 'array',
      array: true,
      format: null,
    },
    allow_methods: {
      label: 'allow_methods',
      type: 'array',
      array: true,
      format: null,
    },
    excluded_patterns: {
      label: 'excluded_patterns',
      type: 'array',
      array: true,
      format: null,
    },
    allow_credentials: {
      label: 'allow_credentials',
      type: 'bool',
    },
    expose_headers: {
      label: 'expose_headers',
      type: 'array',
      array: true,
      format: null,
    },
    allow_origin: {
      label: 'allow_origin',
      type: 'string',
    },
  },
  config_flow: [
    'allow_origin',
    'expose_headers',
    'allow_credentials',
    'excluded_patterns',
    'allow_methods',
    'allow_headers',
    'max_age',
  ],
};
