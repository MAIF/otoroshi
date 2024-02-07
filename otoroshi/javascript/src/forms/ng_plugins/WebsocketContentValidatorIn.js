export default {
  id: 'cp:otoroshi.next.plugins.WebsocketContentValidatorIn',
  config_schema: {
    reject_strategy: {
      type: 'select',
      label: 'Strategy used when bad format is detected',
      props: {
        defaultValue: 'drop',
        options: [
          { value: 'drop', label: 'Drop message' },
          { value: 'close', label: 'Close connection' },
        ],
      },
    },
    validator: {
      label: 'validator',
      type: 'object',
      format: 'form',
      schema: {
        path: {
          label: 'path',
          type: 'string',
          props: {
            subTitle: 'Example: $.message',
          },
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
  config_flow: ['reject_strategy', 'validator'],
};
