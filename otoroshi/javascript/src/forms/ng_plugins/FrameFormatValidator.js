export default {
  id: 'cp:otoroshi.next.plugins.FrameFormatValidator',
  config_schema: {
    validator: {
      label: 'validator',
      type: 'object',
      format: 'form',
      schema: {
        path: {
          label: 'path',
          type: 'string',
          props: {
            subTitle: 'Example: $.apikey.metadata.foo',
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
  config_flow: ['validator'],
};
