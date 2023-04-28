export default {
  id: 'cp:otoroshi.next.plugins.OIDCHeaders',
  config_flow: [
    {
      type: 'group',
      name: 'Profile',
      collapsable: false,
      fields: ['profile.send', 'profile.headerName'],
    },
    {
      type: 'group',
      name: 'Id token',
      collapsable: false,
      fields: ['idToken.send', 'idToken.name', 'idToken.headerName', 'idToken.jwt'],
    },
    {
      type: 'group',
      name: 'Access token',
      collapsable: false,
      fields: ['accessToken.send', 'accessToken.name', 'accessToken.headerName', 'accessToken.jwt'],
    },
  ],
  config_schema: {
    profile: {
      send: {
        type: 'bool',
        label: 'Send',
      },
      headerName: {
        type: 'string',
        label: 'Header name',
      },
    },
    idToken: {
      send: {
        type: 'bool',
        label: 'Send',
      },
      name: {
        type: 'string',
        label: 'Name',
      },
      headerName: {
        type: 'string',
        label: 'Header name',
      },
      jwt: {
        type: 'bool',
        label: 'JWT',
      },
    },
    accessToken: {
      send: {
        type: 'bool',
        label: 'Send',
      },
      name: {
        type: 'string',
        label: 'Name',
      },
      headerName: {
        type: 'string',
        label: 'Header name',
      },
      jwt: {
        type: 'bool',
        label: 'JWT',
      },
    },
  },
};
