export default {
  id: 'cp:otoroshi.next.plugins.AdditionalHeadersIn',
  config_schema: {
    headers: {
      label: 'headers',
      type: 'object',
      help: 'Specify headers that will be added to each client request (from Otoroshi to target).',
    },
  },
  config_flow: ['headers'],
};
