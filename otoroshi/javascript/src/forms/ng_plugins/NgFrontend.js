import React from 'react'

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
    cookies: {
      label: 'Cookies',
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
        description: <div>
          <p>Match the exact request path.</p>

          <p>Example:</p>
          <ul>
            <li>Endpoint path: /api/users</li>
            <li>Incoming request: /api/users → matches if exact match is true</li>
            <li>Incoming request: /api/users/123 → does not match if exact match is true</li>
            <li>Incoming request: /api/users/123 → matches if exact match is false</li>
          </ul>
        </div>

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
        description: (<div>
          <p>When matching, strip the matching prefix from the upstream request URL.</p>

          <p>Example:</p>
          <ul>
            <li>Upstream URL: https://api.example.com/v1/users</li>
            <li>API context path: /v1</li>
            <li>If strip is true → Request forwarded as /</li>
            <li>If strip is false → Request forwarded as /users</li>
          </ul>
        </div>
        )
      },
    },
  },
  flow: ['domains', 'strip_path', 'exact', 'methods', 'headers', 'query', 'cookies'],
};
