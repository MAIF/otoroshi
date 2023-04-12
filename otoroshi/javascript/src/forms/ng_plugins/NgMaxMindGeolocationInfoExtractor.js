export default {
  id: 'cp:otoroshi.next.plugins.NgMaxMindGeolocationInfoExtractor',
  config_schema: {
    path: {
      type: 'string',
      label: 'Path of the maxmind db'
    },
    log: {
      type: 'bool',
      label: 'log geolocation infos.'
    }
  },
  config_flow: [
    'path',
    'log',
  ],
};