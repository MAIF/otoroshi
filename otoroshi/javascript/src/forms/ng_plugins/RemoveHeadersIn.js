export default {
  id: 'cp:otoroshi.next.plugins.RemoveHeadersIn',
  config_schema: {
    names: {
      label: 'names',
      type: 'array',
      array: true,
      format: null,
    },
  },
  config_flow: ['names'],
};
