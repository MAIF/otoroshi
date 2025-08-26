export default {
  id: 'cp:otoroshi.next.plugins.StaticResponse',
  config_schema: {
    headers: {
      label: 'headers',
      type: 'object',
    },
    body: {
      type: 'code',
      props: {
        label: 'body',
        editorOnly: true,
        mode: 'text',
      },
    },
    status: {
      label: 'status',
      type: 'number',
    },
    apply_el: {
      label: 'Apply EL.',
      type: 'boolean',
    },
  },
  config_flow: ['body', 'headers', 'status', 'apply_el'],
};
