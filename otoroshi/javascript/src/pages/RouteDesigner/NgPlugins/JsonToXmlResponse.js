export default {
  id: 'cp:otoroshi.next.plugins.JsonToXmlResponse',
  config_schema: {
    filter: {
      label: 'filter',
      type: 'string',
    },
  },
  config_flow: ['filter'],
};
