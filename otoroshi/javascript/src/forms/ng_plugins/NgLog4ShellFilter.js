export default {
  id: 'cp:otoroshi.next.plugins.NgLog4ShellFilter',
  config_schema: {
    status: {
      type: 'number',
      label: 'Response status',
    },
    body: {
      type: 'string',
      label: 'Response body',
    },
    parse_body: {
      type: 'bool',
      label: 'Parse body',
    },
  },
  config_flow: ['status', 'body', 'parse_body'],
};
