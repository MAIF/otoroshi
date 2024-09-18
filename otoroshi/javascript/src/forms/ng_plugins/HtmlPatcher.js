export default {
  id: 'cp:otoroshi.next.plugins.NgHtmlPatcher',
  config_schema: {
    prepend_head: {
      label: 'Prepend to document head',
      array: true,
      type: 'code',
      props: {
        mode: 'html',
        editorOnly: true,
      },
    },
    append_head: {
      label: 'Append to document head',
      array: true,
      type: 'code',
      props: {
        mode: 'html',
        editorOnly: true,
      },
    },
    prepend_body: {
      label: 'Prepend to document body',
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
  config_flow: ['prepend_head', 'append_head', 'prepend_body', 'append_body'],
};
