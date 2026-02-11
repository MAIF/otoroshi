export default {
  id: 'cp:otoroshi.next.plugins.NgTrafficMirroring',
  config_schema: {
    to: {
      type: 'string',
      label: 'Target URL',
    },
    headers: {
      label: 'headers',
      type: 'object',
      help: 'Specify headers',
    },
    enabled: {
      type: 'bool',
      label: 'Enabled',
    },
    capture_response: {
      type: 'bool',
      label: 'Capture response',
    },
    generate_events: {
      type: 'bool',
      label: 'Generate mirroring event',
    },
    percentage: {
      type: 'number',
      label: 'Percentage of requests impacted',
    },
    salt: {
      type: 'string',
      label: 'Salt for percentage impact',
    },
  },
  config_flow: ['enabled', 'capture_response', 'generate_events', 'to', 'headers', 'percentage', 'salt'],
};
