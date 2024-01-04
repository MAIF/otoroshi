export default {
  id: 'cp:otoroshi.next.plugins.NgExternalValidator',
  config_schema: {
    cache_expression: {
      label: 'Cache expression',
      type: 'string',
    },
    ttl: {
      label: 'TTL',
      type: 'number',
      suffix: 'milliseconds'
    },
    timeout: {
      label: 'HTTP timeout',
      type: 'number',
      suffix: 'milliseconds'
    },
    url: {
      label: 'URL',
      type: 'string',
    },
    headers: {
      label: 'headers',
      type: 'object',
    },
    error_message: {
      label: 'Error HTTP message',
      type: 'string',
    },
    error_status: {
      label: 'Error HTTP status',
      type: 'number',
    }
  },
  config_flow: ['cache_expression', 'ttl', 'url', 'headers', 'timeout', 'error_status', 'error_message'],
};
