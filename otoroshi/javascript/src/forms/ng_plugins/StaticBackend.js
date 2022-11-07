export default {
  id: 'cp:otoroshi.next.plugins.StaticBackend',
  config_schema: {
    root_path: {
      label: 'root_path',
      type: 'string',
    },
  },
  config_flow: ['root_path'],
};
