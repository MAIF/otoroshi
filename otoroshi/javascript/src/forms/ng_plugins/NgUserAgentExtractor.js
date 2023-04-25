export default {
  id: 'cp:otoroshi.next.plugins.NgUserAgentExtractor',
  config_schema: {
    log: {
      type: 'bool',
      label: 'Log user agent informations',
    },
  },
  config_flow: ['log'],
};
