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
      renderer: () => {
        return <div className="bg-gradient-to-br from-purple-50 to-blue-50 rounded-lg p-4 border border-purple-200">
          <h3 className="text-sm font-semibold text-slate-700 mb-3">Rate Limit Behavior</h3>

          <div className="space-y-3">
            <div className="flex justify-between items-center">
              <span className="text-sm text-slate-600">Capacity</span>
              <span className="text-lg font-bold text-slate-800">{capacity} tokens</span>
            </div>

            <div className="flex justify-between items-center">
              <span className="text-sm text-slate-600">Refill Rate</span>
              <span className="text-lg font-bold text-purple-600">{refillRate.toFixed(2)} req/s</span>
            </div>

            <div className="flex justify-between items-center">
              <span className="text-sm text-slate-600">Refill Interval</span>
              <span className="text-lg font-bold text-slate-800">{(refillInterval / 1000).toFixed(1)}s</span>
            </div>

            <div className="flex justify-between items-center">
              <span className="text-sm text-slate-600">Tokens per Refill</span>
              <span className="text-lg font-bold text-slate-800">{refillAmount}</span>
            </div>

            <div className="flex justify-between items-center">
              <span className="text-sm text-slate-600">Burst Duration</span>
              <span className="text-lg font-bold text-slate-800">{burstDuration.toFixed(1)}s</span>
            </div>

            <div className="pt-3 border-t border-purple-200">
              <p className="text-xs text-slate-600 leading-relaxed">
                <strong className="text-slate-800">Allows:</strong> {capacity} immediate requests (burst),
                then sustains <strong className="text-purple-600">{refillRate.toFixed(2)}</strong> requests/second
              </p>
            </div>

            <div className="pt-2 bg-slate-100 rounded p-2">
              <p className="text-xs font-mono text-slate-600">
                refillRate = ({refillAmount} × 1000) ÷ {refillInterval} = {refillRate.toFixed(2)} tokens/s
              </p>
            </div>
          </div>
        </div>
      }
    }
  },
  config_flow: ['bucketKey', 'capacity', 'refillRequestIntervalMs', 'refillRequestedTokens', 'example'],
};
