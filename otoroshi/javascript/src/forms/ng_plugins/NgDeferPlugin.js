export default {
  id: 'cp:otoroshi.next.plugins.NgDeferPlugin',
  config_schema: {
    duration: {
      type: 'number',
      label: 'Duration (ms)',
    },
  },
  config_flow: ['duration'],
};
