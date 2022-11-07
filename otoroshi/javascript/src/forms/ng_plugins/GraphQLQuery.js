export default {
  id: 'cp:otoroshi.next.plugins.GraphQLQuery',
  config_schema: {
    headers: {
      label: 'headers',
      type: 'object',
    },
    method: {
      type: 'select',
      props: {
        label: 'method',
        options: ['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'OPTIONS', 'PATCH'],
      },
    },
    query: {
      type: 'code',
      props: {
        editorOnly: true,
        label: 'Graphql query',
        help: 'The graphql query that will be sent to the graphql endpoint',
      },
    },
    response_filter: {
      label: 'response_filter',
      type: 'string',
    },
    response_path: {
      label: 'response_path',
      type: 'string',
    },
    url: {
      label: 'url',
      type: 'string',
    },
    timeout: {
      label: 'timeout',
      type: 'number',
    },
  },
  config_flow: ['url', 'method', 'headers', 'timeout', 'query', 'response_filter', 'response_path'],
};
