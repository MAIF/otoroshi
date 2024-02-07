export default {
  id: 'cp:otoroshi.next.plugins.WebsocketTypeValidator',
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
    allowed_format: {
      type: 'select',
      label: 'Allowed format',
      props: {
        defaultValue: 'all',
        options: [
          { value: 'all', label: 'Accept all types' },
          { value: 'json', label: 'JSON only' },
          { value: 'text', label: 'Text' },
          { value: 'binary', label: 'Binary' },
        ],
      },
    },
  },
  config_flow: ['reject_strategy', 'allowed_format'],
};
