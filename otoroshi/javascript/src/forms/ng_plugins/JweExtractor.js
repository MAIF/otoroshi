export default {
  id: 'cp:otoroshi.next.plugins.JweExtractor',
  config_schema: {
    certId: {
      type: 'select',
      label: "KeyPair",
      props: {
        optionsFrom: "/bo/api/proxy/api/certificates?keypair=true",
        optionsTransformer: {
          label: 'name',
          value: 'id',
        },
      }
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
        }
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
    forward_location: {
      type: 'form',
      label: 'Output Token location',
      props: {
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
        }
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
    }
  },
  config_flow: ['certId', 'source', 'forward_location'],
};
