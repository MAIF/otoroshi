export default {
  id: 'cp:otoroshi.next.plugins.HMACValidator',
  config_schema: {
    secret: {
      label: 'Secret',
      type: 'string',
    },
    authorizationHeader: {
      type: 'string',
      label: 'Header used to send HMAC signature',
      placeholder: 'Authorization, Proxy-Authorization or custom header',
    },
  },
  config_flow: ['secret', 'authorizationHeader'],
};
