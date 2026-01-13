export default {
  id: 'cp:otoroshi.next.plugins.GrpcWebProxyPlugin',
  config_schema: {
    allow_services: {
      type: 'array',
      label: 'Allowed Services',
      help: 'List of allowed gRPC services (e.g., helloworld.Greeter). Empty = allow all',
      array: true,
    },
    allow_methods: {
      type: 'array',
      label: 'Allowed Methods',
      help: 'List of allowed gRPC methods (e.g., SayHello, GetUser). Empty = allow all',
      array: true,
    },
    blocked_methods: {
      type: 'array',
      label: 'Blocked Methods',
      help: 'List of blocked gRPC methods (highest priority, overrides allowed lists)',
      array: true,
    },
  },
  config_flow: [
    {
      type: 'group',
      name: 'gRPC Service Authorization',
      fields: [
        'allow_services',
        'allow_methods',
        'blocked_methods',
      ],
    },
  ],
};