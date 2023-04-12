export default {
  id: 'cp:otoroshi.next.plugins.NgGeolocationInfoHeader',
  config_schema: {
    header_name: {
      type: 'string',
      label: 'Header name'
    }
  },
  config_flow: [
    'header_name'
  ],
};