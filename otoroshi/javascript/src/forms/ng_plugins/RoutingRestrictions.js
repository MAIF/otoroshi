export default {
  id: 'cp:otoroshi.next.plugins.RoutingRestrictions',
  config_schema: {
    allowed: {
      label: 'allowed',
      type: 'array',
      array: true,
      format: 'form',
      schema: {
        method: {
          label: 'method',
          type: 'string',
        },
        path: {
          label: 'path',
          type: 'string',
        },
      },
      flow: ['method', 'path'],
    },
    forbidden: {
      label: 'forbidden',
      type: 'array',
      array: true,
      format: 'form',
      schema: {
        method: {
          label: 'method',
          type: 'string',
        },
        path: {
          label: 'path',
          type: 'string',
        },
      },
      flow: ['method', 'path'],
    },
    not_found: {
      label: 'not_found',
      type: 'array',
      array: true,
      format: 'form',
      schema: {
        method: {
          label: 'method',
          type: 'string',
        },
        path: {
          label: 'path',
          type: 'string',
        },
      },
      flow: ['method', 'path'],
    },
    allow_last: {
      label: 'allow_last',
      type: 'bool',
    },
  },
  config_flow: ['allow_last', 'not_found', 'forbidden', 'allowed'],
};
