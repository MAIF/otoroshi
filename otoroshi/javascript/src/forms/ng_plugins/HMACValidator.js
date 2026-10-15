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
    maxDateSkewSeconds: {
      type: 'number',
      label: 'Max date skew (seconds)',
      props: {
        help: 'When set, the signature has to cover a Date header that close to now. Leave empty to disable',
      },
    },
    signRequestTarget: {
      type: 'bool',
      label: 'Require a signed request target',
      props: {
        help: 'The signature has to cover the (request-target) pseudo header, the method and the path of the request',
      },
    },
  },
  config_flow: ['secret', 'authorizationHeader', 'maxDateSkewSeconds', 'signRequestTarget'],
};
