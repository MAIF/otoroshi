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
    max_body_size: {
      type: 'number',
      label: 'Max body size (bytes)',
      props: {
        help: 'A parsed body bigger than this is rejected with a 413',
      },
    },
  },
  config_flow: ['status', 'body', 'parse_body', 'max_body_size'],
};
