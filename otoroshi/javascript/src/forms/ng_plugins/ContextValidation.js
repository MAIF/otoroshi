export default {
  id: 'cp:otoroshi.next.plugins.ContextValidation',
  config_schema: {
    validators: {
      label: 'validators',
      type: 'object',
      array: true,
      format: 'form',
      schema: {
        path: {
          label: 'path',
          type: 'string',
        },
        value: {
          type: 'code',
          props: {
            label: 'Value',
            type: 'json',
            editorOnly: true,
          },
        },
      },
      flow: ['path', 'value'],
    },
  },
  config_flow: ['validators'],
};
