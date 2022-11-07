export default {
  id: 'cp:otoroshi.next.plugins.XmlToJsonRequest',
  config_schema: {
    filter: {
      label: 'filter',
      type: 'string',
    },
  },
  config_flow: ['filter'],
};
