import React from 'react';
import { Link } from 'react-router-dom';

export function EventsSetupHint({ intro }) {
  return (
    <>
      <p>{intro}</p>
      <ol>
        <li>an ElasticSearch instance reachable from Otoroshi;</li>
        <li>
          a <Link to="/exporters">data exporter</Link> pushing Otoroshi events into it;
        </li>
        <li>
          the <Link to="/dangerzone">Danger Zone</Link> configured to read events back from that
          same ElasticSearch.
        </li>
      </ol>
      <p>
        If all three are already in place, wait a few minutes — the first events need to be indexed
        before they appear here.
      </p>
    </>
  );
}
