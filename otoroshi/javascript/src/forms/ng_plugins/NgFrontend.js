export default {
  id: 'Frontend',
  icon: 'user',
  plugin_steps: [],
  description: null,
  field: 'frontend',
  schema: {
    headers: {
      label: 'Headers',
      type: 'object',
    },
    methods: {
      type: 'dots',
      label: 'Methods',
      props: {
        options: [
          { value: 'GET', label: 'GET', color: 'var(--http_color-get)' },
          { value: 'POST', label: 'POST', color: 'var(--http_color-post)' },
          { value: 'PUT', label: 'PUT', color: 'var(--http_color-put)' },
          { value: 'DELETE', label: 'DELETE', color: 'var(--http_color-delete)' },
          { value: 'HEAD', label: 'HEAD', color: 'var(--http_color-others)' },
          { value: 'OPTIONS', label: 'OPTIONS', color: 'var(--http_color-others)' },
          { value: 'PATCH', label: 'PATCH', color: 'var(--http_color-others)' },
        ],
      },
    },
    query: {
      label: 'Query',
      type: 'object',
    },
    exact: {
      type: 'box-bool',
      label: 'Exact',
      props: {
        description: 'Match exact request path.',
      },
    },
    domains: {
      label: 'Domains',
      type: 'array',
      array: true,
      format: null,
    },
    strip_path: {
      type: 'box-bool',
      label: 'Strip path',
      props: {
        description:
          'When matching, strip the matching prefix from the upstream request URL. Defaults to true',
      },
    },
  },
  flow: ['domains', 'strip_path', 'exact', 'methods', 'headers', 'query'],
};
