export default {
  id: 'cp:otoroshi.next.plugins.NgCertificateAsApikey',
  config_schema: {
    read_only: {
      type: 'bool',
      label: 'Apikey read only',
    },
    allow_client_id_only: {
      type: 'bool',
      label: 'Apikey allow client id only',
    },
    throttling_quota: {
      type: 'number',
      label: 'Apikey throttling',
    },
    daily_quota: {
      type: 'number',
      label: 'Apikey daily quotas',
    },
    monthly_quota: {
      type: 'number',
      label: 'Apikey monthly quotas',
    },
    constrained_services_only: {
      type: 'bool',
      label: 'Apikey constrained service only',
    },
    tags: {
      type: 'array',
      array: true,
      format: null,
      label: 'Apikey tags',
    },
    metadata: {
      type: 'object',
      label: 'Apikey metadata',
    },
  },
  config_flow: [
    'read_only',
    'allow_client_id_only',
    'throttling_quota',
    'daily_quota',
    'monthly_quota',
    'constrained_services_only',
    'tags',
    'metadata',
  ],
};
