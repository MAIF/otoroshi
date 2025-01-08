export default {
  id: 'cp:otoroshi.next.plugins.JQResponse',
  config_schema: {
    filter: {
      label: 'Filter',
      type: 'code',
      help: 'Example: {user: .user, title: .title}',
      props: {
        label: 'Filter',
        type: 'javascript',
        editorOnly: true,
      },
    },
  },
  config_flow: ['filter'],
};
