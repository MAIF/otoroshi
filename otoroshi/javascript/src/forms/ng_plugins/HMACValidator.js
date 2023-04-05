export default {
  id: 'cp:otoroshi.next.plugins.HMACValidator',
  config_schema: {
    secret: {
      label: 'Secret',
      type: 'string',
    }
  },
  config_flow: ['secret']
};
