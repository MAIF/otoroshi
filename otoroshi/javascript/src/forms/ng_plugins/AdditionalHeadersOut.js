export default {
  id: 'cp:otoroshi.next.plugins.AdditionalHeadersOut',
  config_schema: {
    headers: {
      label: 'headers',
      type: 'object',
      help: 'Specify headers that will be added to each client response (from Otoroshi to client).'
    },
  },
  config_flow: ['headers'],
};
