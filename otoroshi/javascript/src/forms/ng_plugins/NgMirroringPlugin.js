export default {
  id: 'cp:otoroshi.next.plugins.NgTrafficMirroring',
  config_schema: {
    to: {
      type: 'String',
      label: 'Target URL',
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
  },
  config_flow: ['to', 'enabled', 'capture_response', 'generate_events'],
};
