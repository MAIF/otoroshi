export default {
  id: 'cp:otoroshi.next.plugins.NgHttpClientCache',
  config_schema: {
    max_age_seconds: {
      type: 'number',
      label: 'Max age',
      placeholder: '86400',
      suffix: 'seconds'
    },
    methods: {
      label: "HTTP methods",
      type: 'array',
      array: true,
      format: null,
    },
    status: {
      label: "HTTP status",
      type: 'array',
      array: true,
      format: 'number',
    },
    mime_types: {
      label: "Mime types",
      type: 'array',
      array: true,
      format: null,
    },
  },
  config_flow: ['max_age_seconds', 'methods', 'status', 'mime_types'],
};
