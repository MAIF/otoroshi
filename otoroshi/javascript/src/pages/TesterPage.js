import React, { Suspense, useEffect, useState } from 'react';
import { NgSelectRenderer } from '../components/nginputs';
import { EntitiesSearchBar } from '../components/EntitiesSearchBar';
import { useQuery, QueryClient, QueryClientProvider } from 'react-query';
import { nextClient, searchNextServices } from '../services/BackOfficeServices';
import { Row } from '../components/Row';
import { useLocation, useParams } from 'react-router-dom';

const TryIt = React.lazy(() => import('../components/TryIt'));

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: false,
    },
  },
});

function TesterPage() {
  const location = useLocation();
  const queryParams = new URLSearchParams(location.search);

  const queryParamEntity = queryParams.get('entity');

  const [loading, setLoading] = useState(true);
  const [entityFromQueryParams, setEntityFromQueryParams] = useState();

  const [entityId, setEntityId] = useState(queryParamEntity);
  const [entity, setEntity] = useState();
  const [route, setRoute] = useState();

  const isAPI = entity && entityId && entityId.startsWith('api');

  useEffect(() => {
    if (queryParamEntity) {
      searchNextServices(queryParamEntity)
        .then((r) => r.json())
        .then((results) => {
          if (results?.length > 0) {
            const ressource = {
              type: results[0].type,
              label: results[0].name,
              value: results[0].serviceId,
              env: results[0].env,
              action: () => setValue(results[0].serviceId),
            };

            setEntityFromQueryParams(ressource);
            setLoading(false);
          }
        });
    } else {
      setLoading(false);
    }
  }, []);

  useQuery(
    ['getEntity', entityId],
    () => {
      return (
        entityId.startsWith('route')
          ? nextClient.forEntityNext(nextClient.ENTITIES.ROUTES)
          : nextClient.forEntityNext(nextClient.ENTITIES.APIS)
      ).findById(entityId);
    },
    {
      enabled: !!entityId,
      onSuccess: (data) => {
        setEntity(data);
        if (entityId.startsWith('route')) {
          setRoute(data);
        }
      },
    }
  );

  const addEntityToURL = (newEntityId) => {
    queryParams.delete('entity');

    if (newEntityId) queryParams.append('entity', newEntityId);

    window.history.replaceState(null, null, '?' + queryParams.toString());
  };

  const handleSelectEntity = (newEntityId) => {
    addEntityToURL(newEntityId);
    setEntityId(newEntityId);
    setEntity(undefined);
    setRoute(undefined);
  };

  return (
    <div style={{ maxWidth: 1440 }}>
      <Row title="Entity">
        {!loading && (
          <EntitiesSearchBar
            entityFromQueryParams={entityFromQueryParams}
            value={entityId}
            setValue={handleSelectEntity}
          />
        )}
      </Row>
      {isAPI && (
        <Row title="Route">
          <NgSelectRenderer
            id="routes"
            ngOptions={{ spread: true }}
            value={route}
            options={entity.routes?.map((r) => ({ label: r.name, value: r }))}
            onChange={(route) => {
              return fetch(
                `/bo/api/proxy/apis/apis.otoroshi.io/v1/apis/${entity.id}/routes/${route.id}`,
                {
                  credentials: 'include',
                  headers: {
                    Accept: 'application/json',
                  },
                }
              )
                .then((r) => r.json())
                .then(setRoute);
            }}
          />
        </Row>
      )}

      {route && (
        <Suspense fallback={null}>
          <TryIt route={route} />
        </Suspense>
      )}
    </div>
  );
}

export default ({ setTitle }) => {
  useEffect(() => {
    setTitle('Tester');
  }, []);

  return (
    <QueryClientProvider client={queryClient}>
      <TesterPage />
    </QueryClientProvider>
  );
};
