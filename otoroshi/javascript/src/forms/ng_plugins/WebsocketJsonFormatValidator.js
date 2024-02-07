export default {
  id: 'cp:otoroshi.next.plugins.WebsocketJsonFormatValidator',
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
    schema: {
      label: 'schema',
      type: 'code',
      props: {
        editorOnly: true,
        label: 'Validation schema'
      },
    },
    specification: {
      label: 'JSON specification used',
      type: 'select',
      props: {
        defaultValue: 'https://json-schema.org/draft/2020-12/schema',
        options: [
          { value: "http://json-schema.org/draft-04/schema#", label: 'V4' },
          { value: "http://json-schema.org/draft-06/schema#", label: 'V6' },
          { value: "http://json-schema.org/draft-07/schema#", label: 'V7' },
          { value: "https://json-schema.org/draft/2019-09/schema", label: 'V201909' },
          { value: "https://json-schema.org/draft/2020-12/schema", label: 'V202012' }
        ],
      },
    }
  },
  config_flow: ['reject_strategy', 'schema', 'specification'],
};
