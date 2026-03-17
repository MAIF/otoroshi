export default {
  id: 'cp:otoroshi.next.plugins.JQ',
  config_schema: {
    request: {
      label: 'Request',
      type: 'code',
      help: 'Example: {username: .user.name}',
      props: {
        label: 'Request',
        type: 'javascript',
        editorOnly: true,
      },
    },
    response: {
      label: 'Response',
      type: 'code',
      help: 'Example: {username: .user.name}',
      props: {
        label: 'Response',
        type: 'javascript',
        editorOnly: true,
      },
    },
  },
  config_flow: ['response', 'request'],
};
