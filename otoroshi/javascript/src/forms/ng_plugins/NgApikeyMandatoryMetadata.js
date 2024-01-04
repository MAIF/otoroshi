export default {
  id: 'cp:otoroshi.next.plugins.NgApikeyMandatoryMetadata',
  config_schema: {
    metadata: {
      label: 'metadata',
      type: 'object',
    },
  },
  config_flow: ['metadata'],
};
