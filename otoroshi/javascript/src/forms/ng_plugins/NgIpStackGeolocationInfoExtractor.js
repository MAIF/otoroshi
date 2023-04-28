export default {
  id: 'cp:otoroshi.next.plugins.NgIpStackGeolocationInfoExtractor',
  config_schema: {
    apikey: {
      type: 'string',
      label: 'Ip Stack apikey',
    },
    timeout: {
      type: 'number',
      label: 'Ip Stack call timeout',
    },
    log: {
      type: 'bool',
      label: 'log geolocation infos.',
    },
  },
  config_flow: ['apikey', 'timeout', 'log'],
};
