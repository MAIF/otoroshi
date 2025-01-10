export default {
  id: 'cp:otoroshi.next.plugins.NgCustomThrottling',
  config_schema: {
    throttling_quota: {
      label: 'Allowed calls per window',
      type: 'number',
    },
    per_route: {
      label: 'Per route',
      type: 'bool',
    },
    global: {
      label: 'Global',
      type: 'bool',
    },
    group: {
      label: 'Per group',
      type: 'string',
    },
    expression: {
      label: 'Expression',
      type: 'string',
    },
  },
  config_flow: ['per_route', 'global', 'group', 'expression', 'throttling_quota'],
};
