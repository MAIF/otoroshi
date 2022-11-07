export default {
  id: 'cp:otoroshi.next.plugins.AllowHttpMethods',
  config_schema: {
    allowed: {
      label: 'allowed',
      type: 'array',
      array: true,
      format: null,
    },
    forbidden: {
      label: 'forbidden',
      type: 'array',
      array: true,
      format: null,
    },
  },
  config_flow: ['forbidden', 'allowed'],
};
