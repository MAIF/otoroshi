export default {
  id: 'cp:otoroshi.next.plugins.ImageReplacer',
  config_schema: {
    url: {
      label: 'Image url',
      type: 'string',
    },
  },
  config_flow: ['url'],
};
