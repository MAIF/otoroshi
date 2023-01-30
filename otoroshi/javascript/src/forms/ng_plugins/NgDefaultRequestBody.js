export default {
  id: 'cp:otoroshi.next.plugins.NgDefaultRequestBody',
  config_schema: {
    bodyStr: {
      label: 'Body (string)',
      type: 'code',
      help: 'The value of the default body as a string',
      props: {
        type: 'text',
        editorOnly: true,
      },
    },
    bodyBinary: {
      label: 'Body (binary)',
      type: 'code',
      help: 'The value of the default body encoded as base64',
      props: {
        type: 'text',
        editorOnly: true,
      },
    },
    contentType: {
      label: 'Content type',
      help: 'the content-type header value of the default body',
      type: 'string',
      placeholder: 'ie: application/json',
    },
    contentEncoding: {
      label: 'Content encoding',
      help: 'the content-encoding header value of the default body',
      type: 'string',
      placeholder: 'ie: gzip',
    },
  },
  config_flow: ['contentType', 'contentEncoding', 'bodyStr', 'bodyBinary'],
};
