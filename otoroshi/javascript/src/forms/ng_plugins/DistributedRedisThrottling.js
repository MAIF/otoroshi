import React from 'react';

export default {
  id: 'cp:otoroshi.next.plugins.DistributedRedisThrottling',
  config_schema: {
    bucketKey: {
      label: 'Bucket key',
      type: 'string',
      help: 'EL expression evaluated to the throttling bucket key (e.g. ${apikey.id}, ${req.ip}, ${user.email}).',
    },
    quota: {
      type: 'form',
      collapsable: false,
      label: 'Allowed Quota',
      schema: {
        window: {
          type: 'number',
          label: 'Request Quota',
          help: 'The maximum number of requests allowed within the throttling window. Once this limit is reached, additional requests will be blocked until the window resets.',
        },
        daily: {
          type: 'number',
          label: 'Daily Request Quota',
          help: 'The maximum number of requests allowed per day. Once this limit is reached, further requests are blocked until the next day.',
        },
        monthly: {
          type: 'number',
          label: 'Monthly Request Quota',
          help: 'The maximum number of requests allowed per month. Once this limit is reached, further requests are blocked until the next month.',
        },
      },
      flow: ['window', 'daily', 'monthly'],
    },
  },
  config_flow: ['bucketKey', 'quota'],
};
