export default {
  id: 'cp:otoroshi.next.plugins.NgErrorRewriter',
  config_schema: {
    templates: {
      label: 'Templates',
      type: 'object',
    },
    ranges: {
      label: 'ranges',
      type: 'array',
      array: true,
      format: 'form',
      schema: {
        from: {
          label: 'From',
          type: 'number',
        },
        to: {
          label: 'To',
          type: 'number',
        },
      }
    },
    export: {
      label: 'Export error',
      help: 'Generate event that can be exported using data exporters',
      type: 'bool',
    },
    log: {
      label: 'Log error',
      help: 'Log the error response in otoroshi logs',
      type: 'bool',
    },
  },
  config_flow: ['log', 'export', 'ranges', 'templates'],
};
