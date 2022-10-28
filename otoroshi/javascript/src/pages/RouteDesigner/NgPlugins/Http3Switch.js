export default {
  id: 'cp:otoroshi.next.plugins.Http3Switch',
  config_schema: {
    ma: {
      label: 'Expires',
      type: 'number',
    },
    domain: {
      label: 'Domain',
      type: 'string',
    },
    protocols: {
      label: 'Protocols',
      type: 'array',
    },
  },
  config_flow: ['ma', 'domain', 'protocols'],
};
