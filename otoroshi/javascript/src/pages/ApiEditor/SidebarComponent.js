import React, { useEffect, useState } from 'react';
import { useLocation, useParams } from 'react-router-dom';
import { useQuery } from 'react-query';
import { nextClient } from '../../services/BackOfficeServices';
import Sidebar from './Sidebar';
import { signalVersion } from './VersionSignal';

function SidebarWithVersion({ params, state }) {
  const queryVersion =
    state === 'staging'
      ? 'staging'
      : new URLSearchParams(window.location.search).get('version') || 'Published';

  if (signalVersion.peek() !== queryVersion) {
    signalVersion.value = queryVersion;
  }

  useEffect(() => {
    const queryParams = new URLSearchParams(window.location.search);
    queryParams.set('version', queryVersion);
    history.replaceState(null, null, '?' + queryParams.toString());
  }, [queryVersion]);

  return <Sidebar params={params} />;
}

export function SidebarComponent(props) {
  const params = useParams();
  const location = useLocation();

  const [state, setState] = useState();

  useQuery(
    ['getAPI', params.apiId],
    () => nextClient.forEntityNext(nextClient.ENTITIES.APIS).findById(params.apiId),
    {
      onSuccess: (api) => {
        setState(api.state);
      },
    }
  );

  useEffect(() => {
    if (location.pathname !== '/apis') {
      props.setSidebarContent(<SidebarWithVersion params={params} state={state} />);
    }
    return () => props.setSidebarContent(null);
  }, [params, state]);

  return null;
}
