export default {
  id: 'cp:otoroshi.next.plugins.ZipFileBackend',
  config_schema: {
    url: {
      label: 'Zip file URL',
      type: 'string',
    },
    prefix: {
      label: 'Path prefix',
      type: 'string',
    },
    ttl: {
      label: 'Zip File ttl',
      type: 'number',
    },
    dir: {
      label: 'Zip File directory',
      type: 'string',
    },
    headers: {
      label: 'Headers',
      type: 'object',
    },
  },
  config_flow: ['url', 'headers', 'prefix', 'dir', 'ttl'],
};
