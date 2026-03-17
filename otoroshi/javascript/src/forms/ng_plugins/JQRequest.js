export default {
  id: 'cp:otoroshi.next.plugins.JQRequest',
  config_schema: {
    filter: {
      label: 'Filter',
      type: 'code',
      help: 'Example: {username: .user.name}',
      props: {
        label: 'Filter',
        type: 'javascript',
        editorOnly: true,
      },
    },
  },
  config_flow: ['filter'],
};
