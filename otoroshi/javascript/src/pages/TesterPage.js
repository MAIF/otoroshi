import React, { Suspense, useEffect, useState } from 'react';
import { NgSelectRenderer } from '../components/nginputs';
import { EntitiesSearchBar } from '../components/EntitiesSearchBar';
import { useQuery, QueryClient, QueryClientProvider } from 'react-query';
import { nextClient } from '../services/BackOfficeServices';
import { Row } from '../components/Row';

const TryIt = React.lazy(() => import('./RouteDesigner/TryIt'));

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
});

function TesterPage() {
  const [entityId, setEntityId] = useState()
  const [entity, setEntity] = useState()
  const [route, setRoute] = useState()

  const isAPI = entity && entityId && entityId.startsWith('api')

  useQuery(['getEntity', entityId],
    () => {
      return (entityId.startsWith('route') ?
        nextClient.forEntityNext(nextClient.ENTITIES.ROUTES) :
        nextClient.forEntityNext(nextClient.ENTITIES.APIS)).findById(entityId)
    },
    {
      enabled: !!entityId,
      onSuccess: data => {
        setEntity(data)
        if (entityId.startsWith('route')) {
          setRoute(data)
        }
      },
    }
  )

  console.log(route)

  return <>
    <Row title="Entity">
      <EntitiesSearchBar value={entityId} setValue={newEntityId => {
        setEntityId(newEntityId)
        setEntity(undefined)
        setRoute(undefined)
      }} />
    </Row>
    {isAPI && <Row title="Route">
      <NgSelectRenderer
        id="routes"
        ngOptions={{ spread: true }}
        value={route}
        options={entity.routes?.map(r => ({ label: r.name, value: r }))}
        onChange={route => {
          return fetch(`/bo/api/proxy/apis/apis.otoroshi.io/v1/apis/${entity.id}/routes/${route.id}`, {
            credentials: 'include',
            headers: {
              Accept: 'application/json',
            }
          })
            .then((r) => r.json())
            .then(setRoute)
        }}
      />
    </Row>}

    {route && <Suspense fallback={null}>
      <TryIt route={route} hideTitle />
    </Suspense>}
  </>
}

export default ({ setTitle }) => {

  useEffect(() => {
    setTitle('Tester');
  }, []);

  return <QueryClientProvider client={queryClient}>
    <TesterPage />
  </QueryClientProvider>
}
