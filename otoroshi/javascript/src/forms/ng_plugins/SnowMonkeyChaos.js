export default {
  id: 'cp:otoroshi.next.plugins.SnowMonkeyChaos',
  config_schema: {
    bad_responses_fault_config: {
      label: 'bad_responses_fault_config',
      type: 'form',
      collapsable: true,
      collapsed: true,
      schema: {
        responses: {
          label: 'responses',
          type: 'array',
          array: true,
          format: 'form',
          schema: {
            status: {
              label: 'status',
              type: 'number',
            },
            headers: {
              label: 'headers',
              type: 'object',
            },
            body: {
              type: 'code',
              props: {
                label: 'body',
                editorOnly: true,
                mode: 'json',
              },
            },
          },
          flow: ['status', 'body', 'headers'],
        },
        ratio: {
          label: 'ratio',
          type: 'number',
        },
      },
      flow: ['responses', 'ratio'],
    },
    latency_injection_fault_config: {
      label: 'latency_injection_fault_config',
      type: 'form',
      collapsable: true,
      collapsed: true,
      schema: {
        ratio: {
          label: 'ratio',
          type: 'number',
        },
        from: {
          label: 'from',
          type: 'number',
        },
        to: {
          label: 'to',
          type: 'number',
        },
      },
      flow: ['ratio', 'from', 'to'],
    },
    large_response_fault_config: {
      label: 'large_response_fault_config',
      type: 'form',
      collapsable: true,
      collapsed: true,
      schema: {
        ratio: {
          label: 'ratio',
          type: 'number',
        },
        additional_response_size: {
          label: 'additional_response_size',
          type: 'number',
        },
      },
      flow: ['ratio', 'additional_response_size'],
    },
    large_request_fault_config: {
      label: 'large_request_fault_config',
      type: 'form',
      collapsable: true,
      collapsed: true,
      schema: {
        ratio: {
          label: 'ratio',
          type: 'number',
        },
        additional_request_size: {
          label: 'additional_request_size',
          type: 'number',
        },
      },
      flow: ['ratio', 'additional_request_size'],
    },
  },
  config_flow: [
    'bad_responses_fault_config',
    'latency_injection_fault_config',
    'large_response_fault_config',
    'large_request_fault_config',
  ],
};
