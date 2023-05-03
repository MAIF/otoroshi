export default {
  id: 'cp:otoroshi.next.plugins.NgUserAgentInfoHeader',
  config_schema: {
    header_name: {
      type: 'string',
      label: 'Name of the header',
    },
  },
  config_flow: ['header_name'],
};
