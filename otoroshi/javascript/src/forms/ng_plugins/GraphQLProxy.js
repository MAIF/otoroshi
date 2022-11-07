export default {
  id: 'cp:otoroshi.next.plugins.GraphQLProxy',
  config_schema: {
    schema: {
      label: 'schema',
      type: 'string',
    },
    path: {
      label: 'path',
      type: 'string',
    },
    headers: {
      label: 'headers',
      type: 'object',
    },
    endpoint: {
      label: 'endpoint',
      type: 'string',
    },
    max_depth: {
      label: 'max_depth',
      type: 'number',
    },
    max_complexity: {
      label: 'max_complexity',
      type: 'number',
    },
  },
  config_flow: ['max_complexity', 'max_depth', 'endpoint', 'headers', 'path', 'schema'],
};
