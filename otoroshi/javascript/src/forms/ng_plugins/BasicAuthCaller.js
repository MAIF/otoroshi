export default {
  id: 'cp:otoroshi.next.plugins.BasicAuthCaller',
  config_schema: {
    username: {
      type: "string",
      label: 'Username'
    },
    password: {
      type: "string",
      label: 'Password'
    },
    headerName: {
      type: "string",
      label: 'Header name'
    },
    headerValueFormat: {
      type: "string",
      label: 'Header value formatter'
    },
  },
  config_flow: [
    "username",
    "password",
    "headerName",
    "headerValueFormat"
  ]
};