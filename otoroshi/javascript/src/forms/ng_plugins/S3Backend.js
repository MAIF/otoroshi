export default {
  id: 'cp:otoroshi.next.plugins.S3Backend',
  config_schema: {
    bucket: {
      label: 'bucket',
      type: 'string',
    },
    v4auth: {
      label: 'v4auth',
      type: 'bool',
    },
    endpoint: {
      label: 'endpoint',
      type: 'string',
    },
    access: {
      label: 'access',
      type: 'string',
    },
    writeEvery: {
      label: 'writeEvery',
      type: 'number',
    },
    chunkSize: {
      label: 'chunkSize',
      type: 'number',
    },
    secret: {
      label: 'secret',
      type: 'string',
    },
    region: {
      label: 'region',
      type: 'string',
    },
    key: {
      label: 'key',
      type: 'string',
    },
  },
  config_flow: [
    'region',
    'secret',
    'access',
    'v4auth',
    'endpoint',
    'key',
    'bucket',
    'chunkSize',
    'writeEvery',
  ],
};
