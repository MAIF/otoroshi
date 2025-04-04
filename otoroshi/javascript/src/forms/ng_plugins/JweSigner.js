export default {
  id: 'cp:otoroshi.next.plugins.JweSigner',
  config_schema: {
    key_management_algorithm: {
      type: 'select',
      label: 'Key management algorithm',
      props: {
        options: [
          { label: 'RSA_OAEP_256', value: 'RSA_OAEP_256' },
          { label: 'RSA_OAEP_384', value: 'RSA_OAEP_384' },
          { label: 'RSA_OAEP_512', value: 'RSA_OAEP_512' },
        ],
      },
    },
    content_encryption_algorithm: {
      type: 'select',
      label: 'Content encryption algorithm',
      props: {
        options: [
          { label: 'A128CBC_HS256', value: 'A128CBC_HS256' },
          { label: 'A192CBC_HS384', value: 'A192CBC_HS384' },
          { label: 'A256CBC_HS512', value: 'A256CBC_HS512' },
          { label: 'A128GCM', value: 'A128GCM' },
          { label: 'A192GCM', value: 'A192GCM' },
          { label: 'A256GCM', value: 'A256GCM' },
        ],
      },
    },
    certId: {
      type: 'select',
      label: 'KeyPair',
      props: {
        optionsFrom: '/bo/api/proxy/api/certificates?keypair=true',
        optionsTransformer: {
          label: 'name',
          value: 'id',
        },
      },
    },
    source: {
      type: 'form',
      // collapsable: true,
      label: 'Entry Token location',
      props: {
        // showSummary: true,
        ngOptions: {
          spread: true,
        },
      },
      schema: {
        type: {
          type: 'select',
          label: 'Type',
          props: {
            ngOptions: {
              spread: true,
            },
            options: [
              { value: 'InHeader', label: 'Header' },
              { value: 'InQueryParam', label: 'Query string' },
              { value: 'InCookie', label: 'Cookie' },
            ],
          },
        },
        name: {
          type: 'string',
          label: 'Name',
        },
      },
      flow: [
        'type',
        {
          type: 'group',
          collapsable: false,
          visible: (props) => props?.type === 'InHeader',
          name: 'Header informations',
          fields: ['name'],
        },
        {
          type: 'group',
          collapsable: false,
          visible: (props) => props?.type === 'InQueryParam',
          name: 'Query param name',
          fields: ['name'],
        },
        {
          type: 'group',
          collapsable: false,
          visible: (props) => props?.type === 'InCookie',
          name: 'Cookie name',
          fields: ['name'],
        },
      ],
    },
    payload: {
      label: 'Claims',
      type: 'object',
    },
  },
  config_flow: [
    'key_management_algorithm',
    'content_encryption_algorithm',
    'payload',
    'certId',
    'source',
  ],
};
