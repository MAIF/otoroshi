export default {
  id: 'cp:otoroshi.next.plugins.WebsocketSizeValidator',
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
    client_max_payload: {
      label: 'Client max payload',
      type: 'number'
    }
  },
  config_flow: ['reject_strategy', 'client_max_payload'],
};
