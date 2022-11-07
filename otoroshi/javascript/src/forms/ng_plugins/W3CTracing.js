export default {
  id: 'cp:otoroshi.next.plugins.W3CTracing',
  config_schema: {
    endpoint: {
      label: 'endpoint',
      type: 'string',
    },
    baggage: {
      label: 'baggage',
      type: 'object',
    },
    kind: {
      type: 'select',
      props: {
        label: 'kind',
        options: ['jaeger', 'zipkin', 'logger', 'noop'],
      },
    },
    timeout: {
      label: 'timeout',
      type: 'number',
    },
  },
  config_flow: ['timeout', 'kind', 'baggage', 'endpoint'],
};
