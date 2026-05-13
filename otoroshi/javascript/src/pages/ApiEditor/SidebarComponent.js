import React, { useEffect, useState } from 'react';
import { useLocation, useParams } from 'react-router-dom';
import { useQuery } from 'react-query';
import { nextClient } from '../../services/BackOfficeServices';
import Sidebar from './Sidebar';
import { signalVersion } from './VersionSignal';

function SidebarWithVersion({ params, state }) {
  const queryParams = new URLSearchParams(window.location.search);
  const queryVersion =
    state === 'staging'
      ? 'staging'
      : queryParams.get('version')
        ? queryParams.get('version')
        : 'Published';

  useEffect(() => {
    if (queryVersion) {
      updateQueryParams(queryVersion);
      updateSignal(queryVersion);
    }
  }, [queryVersion]);

  const updateSignal = (version) => {
    signalVersion.value = version;
  };

  const updateQueryParams = (version) => {
    const queryParams = new URLSearchParams(window.location.search);
    queryParams.set('version', version);
    history.replaceState(null, null, '?' + queryParams.toString());
  };

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
