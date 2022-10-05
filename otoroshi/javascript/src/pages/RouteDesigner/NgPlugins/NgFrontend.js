export default {
  id: 'Frontend',
  icon: 'user',
  plugin_steps: [],
  description: null,
  field: 'frontend',
  schema: {
    headers: {
      label: "headers",
      type: "object"
    },
    methods: {
      type: 'array-select',
      props: {
        label: 'Methods',
        options: ['GET', 'HEAD', 'POST', 'PUT', 'DELETE', 'OPTIONS', 'PATCH']
      }
    },
    query: {
      label: "query",
      type: "object"
    },
    exact: {
      type: 'bool',
      props: {
        label: 'Exact',
        labelColumn: 6,
      }
    },
    domains: {
      label: "domains",
      type: "string",
      "array": true,
    },
    "strip_path": {
      type: 'bool',
      props: {
        label: 'Strip path',
        labelColumn: 6,
      }
    }
  },
  flow: [
    'domains',
    {
      type: 'grid',
      name: 'Flags',
      fields: ['strip_path', 'exact'],
    },
    'headers',
    'methods',
    'query',
  ]
}