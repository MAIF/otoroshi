export default {
  id: 'cp:otoroshi.next.plugins.OAuth1Caller',
  config_flow: ['algo', 'consumerKey', 'consumerSecret', 'token', 'tokenSecret'],
  config_schema: {
    consumerKey: {
      type: 'string',
      label: 'Consumer key',
      props: {
        help: 'A value used by the Consumer to identify itself to the Service Provider.',
      },
    },
    consumerSecret: {
      type: 'string',
      label: 'Consumer secret',
      help: 'A secret used by the Consumer to establish ownership of the Consumer Key.',
    },
    token: {
      type: 'string',
      label: 'OAuth token',
      props: {
        help:
          "A value used by the Consumer to gain access to the Protected Resources on behalf of the User, instead of using the User's Service Provider credentials.",
      },
    },
    tokenSecret: {
      type: 'string',
      label: 'OAuth token secret',
      props: {
        help: 'A secret used by the Consumer to establish ownership of a given Token.',
      },
    },
    algo: {
      type: 'select',
      label: 'Algo to sign requests',
      props: {
        defaultValue: 'HmacSHA512',
        options: [
          { value: 'HMAC-SHA1', label: 'HMAC-SHA1' },
          { value: 'HMAC-SHA256', label: 'HMAC-SHA256' },
          { value: 'HMAC-SHA384', label: 'HMAC-SHA384' },
          { value: 'HMAC-SHA512', label: 'HMAC-SHA512' },
          { value: 'TEXTPLAIN', label: 'TEXTPLAIN (not recommended)' },
        ],
      },
    },
  },
};
