export default {
  id: 'cp:otoroshi.next.plugins.MissingHeadersIn',
  config_schema: {
    headers: {
      label: 'headers',
      type: 'object',
    },
  },
  config_flow: ['headers'],
};
