export default {
  id: 'cp:otoroshi.next.plugins.GzipResponseCompressor',
  config_schema: {
    chunked_threshold: {
      label: 'chunked_threshold',
      type: 'number',
    },
    white_list: {
      label: 'white_list',
      type: 'array',
      array: true,
      format: null,
    },
    excluded_patterns: {
      label: 'excluded_patterns',
      type: 'array',
      array: true,
      format: null,
    },
    black_list: {
      label: 'black_list',
      type: 'array',
      array: true,
      format: null,
    },
    compression_level: {
      label: 'compression_level',
      type: 'select',
      props: {
        options: [
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
        ],
      },
    },
    buffer_size: {
      label: 'buffer_size',
      type: 'number',
    },
  },
  config_flow: [
    'black_list',
    'white_list',
    'excluded_patterns',
    'chunked_threshold',
    'buffer_size',
    'compression_level',
  ],
};
