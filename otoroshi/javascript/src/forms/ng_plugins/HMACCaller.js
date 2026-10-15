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
      type: 'string',
      label: 'Header used to send HMAC signature',
      placeholder: 'Authorization, Proxy-Authorization, ...',
    },
    signRequestTarget: {
      type: 'bool',
      label: 'Sign the request target',
      props: {
        help: 'Also sign the (request-target) pseudo header, the method and the path of the request',
      },
    },
  },
  config_flow: ['secret', 'algo', 'authorizationHeader', 'signRequestTarget'],
};
