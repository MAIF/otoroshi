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
  },
  config_flow: ['reject_strategy'],
};
