export default {
  id: 'cp:otoroshi.next.plugins.NgCustomQuotas',
  config_schema: {
    daily_quota: {
      label: 'Daily allowed calls',
      type: 'number',
    },
    monthly_quota: {
      label: 'Monthly allowed calls',
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
  config_flow: ['per_route', 'global', 'group', 'expression', 'daily_quota', 'monthly_quota'],
};
