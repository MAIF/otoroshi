const METHODS = [
  { value: 'GET', label: 'GET' },
  { value: 'POST', label: 'POST' },
  { value: 'PUT', label: 'PUT' },
  { value: 'DELETE', label: 'DELETE' },
  { value: 'HEAD', label: 'HEAD' },
  { value: 'OPTIONS', label: 'OPTIONS' },
  { value: 'PATCH', label: 'PATCH' },
  { value: 'CONNECT', label: 'CONNECT' },
  { value: 'TRACE', label: 'TRACE' },
];

export default {
  id: 'cp:otoroshi.next.plugins.AllowHttpMethods',
  config_schema: {
    allowed: {
      label: 'allowed',
      type: 'dots',
      props: {
        options: METHODS,
      },
    },
    forbidden: {
      label: 'forbidden',
      type: 'dots',
      props: {
        options: METHODS,
      },
    },
  },
  config_flow: ['forbidden', 'allowed'],
};
