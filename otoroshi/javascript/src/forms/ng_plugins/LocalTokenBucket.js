import React from 'react'

export default {
  id: 'cp:otoroshi.next.plugins.LocalTokenBucket',
  config_schema: {
    bucketKey: {
      label: 'Bucket key',
      type: 'string'
    },
    capacity: {
      label: 'Capacity',
      type: 'number'
    },
    refillRequestIntervalMs: {
      label: 'Refill Request Interval Ms',
      type: 'number'
    },
    refillRequestedTokens: {
      label: 'Refill Requested Tokens',
      type: 'number'
    },
    example: {
      renderer: props => {
        const { capacity = 300, refillRequestIntervalMs = 50, refillRequestedTokens = 50 } = props.rootValue || {};
        const refillsPerSecond = 1000 / refillRequestIntervalMs;
        const tokensPerSecond = refillsPerSecond * refillRequestedTokens;
        const timeToFillBucket = capacity / tokensPerSecond;
        return (
          <div className='row mb-3'>
            <label className='col-xs-12 col-sm-2 col-form-label'>Example</label>
            <div className='col-sm-10'>
              <p className='m-1'>With these settings, the bucket refills <b>{refillRequestedTokens}</b> tokens every <b>{refillRequestIntervalMs}ms</b></p>
              <p className='m-1'>which means <b>{tokensPerSecond.toFixed(2)}</b> requests/second.</p>
              <p className='m-1'>Starting at full capacity, the bucket fills to capacity ({capacity}) in <b>{timeToFillBucket.toFixed(2)}s</b>.</p>
            </div>
          </div>
        );
      }
    },
    quota: {
      type: 'form',
      collapsable: false,
      label: 'Allowed Quota',
      schema: {
        daily: {
          type: 'number',
          label: 'Daily',
        },
        monthly: {
          type: 'number',
          label: 'Monthly',
        }
      },
      flow: ['daily', 'monthly']
    }
  },
  config_flow: ['bucketKey', 'capacity', 'refillRequestIntervalMs', 'refillRequestedTokens', 'example', 'quota'],
};
