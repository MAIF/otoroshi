import React from 'react';

export function LoadBalancingSelector({ onChange, value }) {
  return (
    <div>
      <div className="d-flex flex-column gap-2 m-2">
        {[
          {
            key: 'RoundRobin',
            text: 'Rotates requests evenly across all backend targets in order.',
            advice:
              'Ensures a fair distribution of requests but does not consider target load or response time.',
          },
          {
            key: 'PowerOfTwoRandomChoices',
            text: 'Choose two random targets amongst healthy targets and finally uses the one with less inflight requests',
            advice:
              'Ensures a very simple random approach while making the best choice when choosing the final target.',
          },
          {
            key: 'BestResponseTime',
            text: 'Selects the target with the lowest recorded response time from the list of backend targets.',
            advice: 'Helps optimize performance by prioritizing the fastest target.',
          },
          {
            key: 'IpAddressHash',
            text: "Distributes requests based on a hash of the client's IP address across the backend targets.",
            advice:
              'Ensures the same client IP consistently hits the same target, which can help with session persistence.',
          },
          {
            key: 'Random',
            text: 'Selects a target at random from the list of backend targets.',
            advice:
              'Simple and useful for even distribution when there are no major performance differences between targets.',
          },
          {
            key: 'Sticky',
            text: 'Routes requests from the same client to the same target based on a session identifier (e.g., a cookie or IP hash).',
            advice: 'Helps maintain session persistence, useful for stateful applications.',
          },
          {
            key: 'WeightedBestResponseTime',
            text: 'Similar to BestResponseTime but assigns weights to backend targets, allowing some to handle more traffic than others.',
            advice:
              'Prioritizes targets with lower response times while considering configured weights.',
          },
          {
            key: 'LeastConnections',
            text: 'Least connections from the list of backend targets.',
            advice: 'Request is passed to the server with the least number of active connections.'
          }
        ].map(({ key, text, advice }) => (
          <button
            type="button"
            className={`btn d-flex flex-column ${value === key ? 'btn-primaryColor' : 'btn-quiet'} pb-3`}
            onClick={() => onChange(key)}
            key={key}
          >
            <div style={{ fontWeight: 'bold', textAlign: 'left' }} className="py-2">
              {key}
            </div>
            <p className="m-0" style={{ textAlign: 'left', fontSize: '.9rem' }}>
              {text}
            </p>

            <p
              className="m-0"
              style={{ textAlign: 'left', fontStyle: 'italic', fontSize: '.8rem' }}
            >
              {advice}
            </p>
          </button>
        ))}
      </div>
    </div>
  );
}
