export default {
  id: 'cp:otoroshi.next.plugins.NgGenericBlockList',
  config_schema: {
    expression: {
      label: 'expression',
      type: 'string',
    },
    values: {
      label: 'values',
      type: 'array',
      array: true,
      format: null,
    },
  },
  config_flow: ['expression', 'values'],
};
