export default {
  id: 'cp:otoroshi.next.plugins.NgHtmlPatcher',
  config_schema: {
    append_head: {
      label: 'Append to document head',
      array: true,
      type: 'code',
      props: {
        mode: 'html',
        editorOnly: true,
      },
    },
    append_body: {
      label: 'Append to document body',
      array: true,
      type: 'code',
      props: {
        mode: 'html',
        editorOnly: true,
      },
    },
  },
  config_flow: ['append_head', 'append_body'],
};
