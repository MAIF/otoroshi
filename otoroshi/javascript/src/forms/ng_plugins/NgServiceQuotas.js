export default {
  id: 'cp:otoroshi.next.plugins.NgServiceQuotas',
  config_schema: {
    throttling_quota: {
      type: 'number',
      label: 'Throttling',
    },
    daily_quota: {
      type: 'number',
      label: 'Daily quotas',
    },
    monthly_quota: {
      type: 'number',
      label: 'Monthly quotas',
    },
  },
  config_flow: ['throttling_quota', 'daily_quota', 'monthly_quota'],
};
