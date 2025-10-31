import React from 'react'

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
    pathStyleAccess: {
      label: 'Path Style Access',
      type: 'box-bool',
      props: {
        description: <div>
          <div>Virtual-hosted-style</div>
          <pre>https://my-bucket.s3.amazonaws.com/photos/cat.jpg</pre>

          <div>Path-style</div>
          <pre>https://s3.amazonaws.com/my-bucket/photos/cat.jpg</pre>
        </div>
      }
    }
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
    'pathStyleAccess'
  ],
};
