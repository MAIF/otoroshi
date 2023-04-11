export default {
  id: 'cp:otoroshi.next.plugins.ResponseCache',
  config_schema: {
    ttl: {
      type: 'number',
      label: 'TTL'
    },
    maxSize: {
      type: 'number',
      label: 'Max cache size'
    },
    autoClean: {
      type: 'bool',
      label: 'Auto clean'
    },
    filter: {
      type: 'form',
      collapsable: true,
      label: 'Filters',
      props: {
        showSummary: true,
        ngOptions: {
          spread: true,
        },
      },
      schema: {
        statuses: {
          type: 'array',
          array: true,
          label: 'Statuses'
        },
        methods: {
          type: 'array',
          array: true,
          label: 'Methods'
        },
        paths: {
          type: 'array',
          array: true,
          label: 'Paths'
        },
        notStatuses: {
          type: 'array',
          array: true,
          label: 'Not statuses'
        },
        notMethods: {
          type: 'array',
          array: true,
          label: 'Not methods'
        },
        notPaths: {
          type: 'array',
          array: true,
          label: 'Not paths'
        }
      },
      flow: [
        'statuses',
        'methods',
        'paths',
        'notStatuses',
        'notMethods',
        'notPaths'
      ]
    }
  },
  config_flow: ['ttl', 'maxSize', 'autoClean', 'filter']
};
