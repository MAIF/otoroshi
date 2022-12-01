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
          props: {
            subTitle: "Example: $.apikey.metadata.foo"
          }
        },
        value: {
          type: 'code',
          help: 'Example: Contains(bar)',
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
