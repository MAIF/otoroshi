import React from 'react'

export default {
  id: 'cp:otoroshi.next.plugins.FixedWindow',
  config_schema: {
    windowDurationMs: {
      type: 'number',
      label: 'Window Duration (ms)',
      help: 'The time span of each fixed window in milliseconds. Requests are counted within each window, and throttling limits are applied per window.'
    },
    bucketKey: {
      label: 'Bucket key',
      type: 'string'
    },
    quota: {
      type: 'form',
      collapsable: false,
      label: 'Allowed Quota',
      schema: {
        window: {
          type: 'number',
          label: 'Request Quota',
          help: 'The maximum number of requests allowed within each fixed window. Once this limit is reached, additional requests will be blocked until the next window.'
        },
        daily: {
          type: 'number',
          label: 'Daily Request Quota',
          help: 'The maximum number of requests allowed per day. Once this limit is reached, further requests are blocked until the next day.'
        },
        monthly: {
          type: 'number',
          label: 'Monthly Request Quota',
          help: 'The maximum number of requests allowed per month. Once this limit is reached, further requests are blocked until the next month.'
        }
      },
      flow: ['window', 'daily', 'monthly']
    }
  },
  config_flow: ['windowDurationMs', 'bucketKey', 'quota'],
};
