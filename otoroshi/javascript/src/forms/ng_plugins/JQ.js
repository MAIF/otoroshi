export default {
  id: 'cp:otoroshi.next.plugins.JQ',
  config_schema: {
    request: {
      label: 'Request',
      type: 'code',
      help: 'Example: {user: .user, title: .title}',
      props: {
        label: 'Request',
        type: 'javascript',
        editorOnly: true,
      },
    },
    response: {
      label: 'Response',
      type: 'code',
      help: 'Example: {user: .user, title: .title}',
      props: {
        label: 'Response',
        type: 'javascript',
        editorOnly: true,
      },
    },
  },
  config_flow: ['response', 'request'],
};
