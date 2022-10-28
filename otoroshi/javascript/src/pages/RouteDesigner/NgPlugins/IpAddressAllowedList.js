export default {
  id: 'cp:otoroshi.next.plugins.IpAddressAllowedList',
  config_schema: {
    addresses: {
      label: 'addresses',
      type: 'array',
      array: true,
      format: null,
    },
  },
  config_flow: ['addresses'],
};
