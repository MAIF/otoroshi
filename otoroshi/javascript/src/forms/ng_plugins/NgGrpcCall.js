export default {
  id: 'cp:otoroshi.next.plugins.grpc.NgGrpcCall',
  config_schema: {
    address: {
      label: 'Address',
      type: 'string'
    },
    port: {
      label: 'Port',
      type: 'number'
    },
    secured: {
      label: 'use TLS/SSL',
      type: 'bool'
    },
    clientKind: {
      label: 'Client kind',
      type: 'select',
      props: {
        options: ['AsyncUnary', 'BlockingUnary', 'AsyncBidiStreaming', 'AsyncClientStreaming'].map((label, i) => ({
          label,
          value: i + 1
        })),
      }
    },
    transcodingRequestToGRPC: {
      type: 'box-boolean',
      label: 'Transcoding',
      props: {
        description: 'Transcoding HTTP request informations (path, method, body) to GRPC request'
      }
    },
    httpRuleBody: {
      type: 'string',
      label: 'HttpRuleBody',
      props: {
        description: `If the value is *, there is no URL query parameter, all fields are passed via URL path and HTTP request body. 
        If is omitted, there is no HTTP request body, all fields are passed via URL path and URL query parameters.`
      }
    },
    fullServiceName: {
      label: 'Full service name',
      type: 'string'
    },
    methodName: {
      label: 'Method name',
      type: 'string'
    },
    packageName: {
      label: 'Package name',
      type: 'string'
    },
    serviceName: {
      label: 'Service name',
      type: 'string'
    }
  },
  config_flow: [
    {
      type: 'group',
      name: 'GRPC server',
      fields: ['address', 'port', 'secured', 'clientKind'],
    },
    {
      type: 'group',
      name: 'Transcoding',
      collapsable: false,
      fields: [
        'transcodingRequestToGRPC',
        'httpRuleBody'
      ]
    },
    {
      type: 'group',
      name: 'GRPC service (only use if transcoding is disabled)',
      visible: (props) => !props?.transcodingRequestToGRPC,
      fields: ['fullServiceName', 'methodName', 'packageName', 'serviceName'],
    }
  ],
};
