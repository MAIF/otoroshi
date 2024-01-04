export default {
  id: 'cp:otoroshi.next.plugins.NgApikeyMandatoryTags',
  config_schema: {
    tags: {
      label: 'tags',
      type: 'array',
      array: true,
      format: null,
    },
  },
  config_flow: ['tags'],
};
