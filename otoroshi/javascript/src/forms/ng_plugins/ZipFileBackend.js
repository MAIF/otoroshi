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
      }
    },
    config_flow: ['url', 'prefix', 'dir', 'ttl'],
  };
  