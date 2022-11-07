export default {
  id: 'cp:otoroshi.next.plugins.EndlessHttpResponse',
  config_schema: {
    finger: {
      label: 'finger',
      type: 'bool',
    },
    addresses: {
      label: 'addresses',
      type: 'array',
      array: true,
      format: null,
    },
  },
  config_flow: ['addresses', 'finger'],
};
