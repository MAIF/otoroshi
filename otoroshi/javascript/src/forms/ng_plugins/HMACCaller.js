export default {
  id: 'cp:otoroshi.next.plugins.HMACCaller',
  config_schema: {
    secret: {
      type: 'string',
      label: 'Secret',
      help: 'Secret to sign and verify signed content of headers. By default, the secret of the api key is used.',
    },
    algo: {
      type: 'select',
      label: 'Algo to sign requests',
      props: {
        defaultValue: 'HmacSHA512',
        options: ['HMAC-SHA1', 'HMAC-SHA256', 'HMAC-SHA384', 'HMAC-SHA512'],
      },
    },
    authorizationHeader: {
      type: 'select',
      label: 'Header used to send HMAC signature',
      props: {
        defaultValue: 'Authorization',
        options: ['Authorization', 'Proxy-Authorization'],
      },
    },
  },
  config_flow: ['secret', 'algo', 'authorizationHeader'],
};
