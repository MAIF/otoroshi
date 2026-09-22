export default {
  id: 'cp:otoroshi.next.plugins.BrotliResponseCompressor',
  config_schema: {
    allowed_list: {
      label: 'allowed_list',
      type: 'array',
      array: true,
      format: null,
    },
    blocked_list: {
      label: 'blocked_list',
      type: 'array',
      array: true,
      format: null,
    },
    compression_level: {
      label: 'compression_level',
      type: 'select',
      props: {
        options: [
          { label: '0', value: 0 },
          { label: '1', value: 1 },
          { label: '2', value: 2 },
          { label: '3', value: 3 },
          { label: '4', value: 4 },
          { label: '5', value: 5 },
          { label: '6', value: 6 },
          { label: '7', value: 7 },
          { label: '8', value: 8 },
          { label: '9', value: 9 },
          { label: '10', value: 10 },
          { label: '11', value: 11 },
        ],
      },
    },
    buffer_size: {
      label: 'buffer_size',
      type: 'number',
    },
  },
  config_flow: ['blocked_list', 'allowed_list', 'buffer_size', 'compression_level'],
};
