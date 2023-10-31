export default {
  id: 'cp:otoroshi.next.plugins.QueryTransformer',
  config_schema: {
    add: {
      label: 'add',
      type: 'object',
    },
    rename: {
      label: 'rename',
      type: 'object',
    },
    remove: {
      label: 'remove',
      type: 'array',
      array: true,
      format: null,
    },
  },
  config_flow: ['add', 'rename', 'remove'],
};
