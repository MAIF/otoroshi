export default {
  id: 'Frontend',
  icon: 'user',
  plugin_steps: [],
  description: null,
  field: 'frontend',
  schema: {
    headers: {
      label: 'headers',
      type: 'object',
    },
    methods: {
      type: 'dots',
      label: 'Methods',
      props: {
        options: [
          { value: 'GET', label: 'GET', color: 'rgb(89, 179, 255)' },
          { value: 'POST', label: 'POST', color: 'rgb(74, 203, 145)' },
          { value: 'PUT', label: 'PUT', color: 'rgb(251, 161, 47)' },
          { value: 'DELETE', label: 'DELETE', color: 'rgb(249, 63, 62)' },
          { value: 'HEAD', label: 'HEAD', color: 'rgb(155, 89, 182)' },
          { value: 'OPTIONS', label: 'OPTIONS', color: 'rgb(155, 89, 182)' },
          { value: 'PATCH', label: 'PATCH', color: 'rgb(155, 89, 182)' },
        ],
      },
    },
    query: {
      label: 'query',
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
      label: 'domains',
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
