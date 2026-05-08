import React, { useEffect, useState } from 'react';
import { Link, useHistory, useLocation, useParams } from 'react-router-dom';
import { useQuery } from 'react-query';
import { v4 } from 'uuid';
import { nextClient } from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs';
import { NgForm, NgSelectRenderer } from '../../components/nginputs';
import NgFrontend from '../../forms/ng_plugins/NgFrontend';
import PageTitle from '../../components/PageTitle';
import { FeedbackButton } from '../RouteDesigner/FeedbackButton';
import InfoCollapse from '../../components/InfoCollapse';
import { Row } from '../../components/Row';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI, historyPush, linkWithQuery } from './hooks';
import { DraftOnly, VersionBadge } from './DraftOnly';
import { MAX_WIDTH } from './constants';

const ROUTE_FORM_SETTINGS = {
  schema: (item, backends) => {
    return {
      name: {
        type: 'string',
        label: 'Endpoint name',
        placeholder: 'My users endpoint',
      },
      enabled: {
        type: 'box-bool',
        label: 'Enabled',
        props: {
          description: 'When enabled, this option allows traffic on.',
        },
      },
      frontend: {
        type: 'form',
        label: ' ',
        schema: {
          ...NgFrontend.schema,
          domains: {
            ...NgFrontend.schema.domains,
            label: 'Paths',
          },
        },
        flow: NgFrontend.flow,
      },
      flow_ref: {
        type: 'select',
        label: 'Plugin chain',
        props: {
          options: item.flows.map((r) => ({ value: r.id, label: r.name })),
        },
      },
      backend: {
        renderer: (props) => (
          <Row title="Backend">
            <div>
              <p>
                A backend represents the HTTP service that will be called when requests match a
                endpoint. You can create a backend specifically for a single API and reuse it across
                multiple endpoints, or create separate backends for each endpoint via the{' '}
                <em>Backends</em> menu.
              </p>
              <p>
                In short, the backend defines where your requests are sent and how Otoroshi forwards
                them.
              </p>

              <p>Usage:</p>
              <ul>
                <li>
                  <span className="badge bg-success me-2">LOCAL</span>
                  defined directly in this API, only visible here, can be reused across this API's
                  routes
                </li>
                <li>
                  <span className="badge bg-warning me-2">GLOBAL</span>
                  defined outside this API, shared across multiple APIs and routes in Otoroshi
                </li>
              </ul>

              <p>Example:</p>
              <ul>
                <li>
                  You create a LOCAL backend for your "Users API" pointing to{' '}
                  <code>https://users.example.com</code>
                </li>
                <li>
                  You can then assign this backend to all endpoints of your Users API, avoiding
                  duplication
                </li>
                <li>
                  Or, if you have different upstream services per endpoint, you can create a
                  dedicated backend for each
                </li>
              </ul>
            </div>
            <NgSelectRenderer
              id="backend_select"
              value={props.rootValue.backend_ref || props.rootValue.backend}
              placeholder="Select an existing backend"
              label={' '}
              ngOptions={{
                spread: true,
              }}
              isClearable
              onChange={(backend_ref) => {
                props.rootOnChange({
                  ...props.rootValue,
                  usingExistingBackend: true,
                  backend: backend_ref,
                });
              }}
              components={{
                Option: (props) => {
                  return (
                    <div
                      className="d-flex align-items-center m-0 p-2"
                      style={{ gap: '.5rem' }}
                      onClick={() => {
                        props.selectOption(props.data);
                      }}
                    >
                      <span
                        className={`badge ${props.data.value?.startsWith('backend_') ? 'bg-warning' : 'bg-success'}`}
                      >
                        {props.data.value?.startsWith('backend_') ? 'GLOBAL' : 'LOCAL'}
                      </span>
                      {props.data.label}
                    </div>
                  );
                },
                SingleValue: (props) => {
                  return (
                    <div className="d-flex align-items-center m-0" style={{ gap: '.5rem' }}>
                      <span
                        className={`badge ${props.data.value?.startsWith('backend_') ? 'bg-warning' : 'bg-success'}`}
                      >
                        {props.data.value?.startsWith('backend_') ? 'GLOBAL' : 'LOCAL'}
                      </span>
                      {props.data.label}
                    </div>
                  );
                },
              }}
              options={[...item.backends, ...backends]}
              optionsTransformer={(arr) =>
                arr.map((item) => ({ label: item.name, value: item.id }))
              }
            />
          </Row>
        ),
      },
    };
  },
  flow: [
    {
      type: 'group',
      collapsable: false,
      name: '1. Global Information',
      fields: ['enabled', 'name'],
      summaryFields: ['enabled', 'name'],
    },
    {
      type: 'group',
      collapsable: false,
      name: '2. Endpoint Gateway',
      fields: ['frontend'],
    },
    {
      type: 'group',
      collapsable: false,
      name: '3. Add plugins to your endpoint by selecting a plugin chains',
      fields: ['flow_ref'],
      summaryFields: ['flow_ref'],
    },
    {
      type: 'group',
      collapsable: false,
      name: '4. Configure the backend',
      fields: ['backend'],
      summaryFields: ['backend'],
    },
  ],
};

export function RouteDesigner(props) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  const [route, setRoute] = useState();
  const [schema, setSchema] = useState();

  const { item, updateItem } = useDraftOfAPI();

  const [backends, setBackends] = useState([]);

  const backendsQuery = useQuery(
    ['getBackends'],
    () => nextClient.forEntityNext(nextClient.ENTITIES.BACKENDS).findAll(),
    {
      enabled: backends.length <= 0,
      onSuccess: setBackends,
    }
  );

  useEffect(() => {
    props.setTitle(undefined);
  }, []);

  useEffect(() => {
    if (item && backendsQuery.data !== undefined) {
      setRoute(item.routes.find((route) => route.id === params.routeId));
      setSchema(ROUTE_FORM_SETTINGS.schema(item, backends));
    }
  }, [item, backendsQuery.data]);

  const updateRoute = () => {
    return updateItem({
      ...item,
      routes: item.routes.map((item) => {
        if (item.id === route.id) return route;
        return item;
      }),
    }).then(() => historyPush(history, location, `/apis/${params.apiId}/endpoints`));
  };

  if (!route || !item || !schema) return <SimpleLoader />;

  return (
    <div className="page">
      <PageTitle title="Route settings" {...props} />

      <DraftOnly>
        <div className="displayGroupBtn">
          <FeedbackButton
            type="success"
            onPress={updateRoute}
            disabled={!route.flow_ref}
            text={
              <div className="d-flex align-items-center">
                Save <VersionBadge size="xs" />
              </div>
            }
          />
        </div>
      </DraftOnly>

      <NgForm
        value={route}
        flow={ROUTE_FORM_SETTINGS.flow}
        schema={schema}
        onChange={(newValue) => setRoute(newValue)}
      />
    </div>
  );
}

export function NewRoute(props) {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();

  const [route, setRoute] = useState();
  const [schema, setSchema] = useState();

  const [backends, setBackends] = useState([]);

  const backendsQuery = useQuery(
    ['getBackends'],
    () => nextClient.forEntityNext(nextClient.ENTITIES.BACKENDS).findAll(),
    {
      enabled: backends.length <= 0,
      onSuccess: setBackends,
    }
  );

  const { item, updateItem } = useDraftOfAPI();

  useEffect(() => {
    props.setTitle(undefined);
  }, []);

  useEffect(() => {
    if (item && !backendsQuery.isLoading && !schema) {
      setSchema(ROUTE_FORM_SETTINGS.schema(item, backends));
    }
  }, [item, backendsQuery]);

  const saveRoute = () => {
    return updateItem({
      ...item,
      routes: [
        ...item.routes,
        {
          ...route,
          id: v4(),
        },
      ],
    }).then(() => historyPush(history, location, `/apis/${params.apiId}`));
  };

  useEffect(() => {
    if (item) {
      console.log(item);
      nextClient
        .forEntityNext(nextClient.ENTITIES.ROUTES)
        .template()
        .then(({ frontend }) => {
          setRoute({
            ...route,
            name: 'My first endpoint',
            enabled: true,
            frontend: {
              ...frontend,
              domains: ['/foo'],
            },
            backend: item.backends.length && item.backends[0].id,
            usingExistingBackend: true,
            flow_ref: item.flows.length && item.flows[0].id,
          });
        });
    }
  }, [item]);

  if (!schema || !route) return <SimpleLoader />;

  return (
    <div className="page">
      <PageTitle title="New Route" {...props} style={{ paddingBottom: 0 }} />
      <InfoCollapse title="What is an Endpoint ?">
        <ul>
          <li>
            <strong>Path & HTTP method</strong> — define the operation (e.g. <code>GET /users</code>
            , <code>POST /orders</code>).
          </li>
          <li>
            <strong>Plugin chains</strong> — apply a dedicated chain of plugins executed for this
            endpoint (validation, transformation, rate limiting, custom logic, etc.)
          </li>
          <li>
            <strong>Backend target</strong> — forward traffic to a specific backend service. The
            endpoint ultimately routes the request to its configured backend after all plugins have
            been executed.
          </li>
        </ul>
      </InfoCollapse>
      <NgForm flow={ROUTE_FORM_SETTINGS.flow} schema={schema} value={route} onChange={setRoute} />
      <div className="displayGroupBtn">
        <FeedbackButton
          type="success"
          onPress={saveRoute}
          disabled={!route.flow_ref}
          text={
            <div className="d-flex align-items-center">
              Create <VersionBadge size="xs" />
            </div>
          }
        />
      </div>
    </div>
  );
}

export function Endpoints(props) {
  const history = useHistory();
  const params = useParams();
  const location = useLocation();

  const columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Frontend',
      filterId: 'frontend.domains.0',
      cell: (item, a) => {
        return (
          <>
            {item.frontend.domains[0] || '-'}{' '}
            {item.frontend.domains.length > 1 && (
              <span
                className="badge bg-secondary"
                style={{ cursor: 'pointer' }}
                title={item.frontend.domains.map((v) => ` - ${v}`).join('\n')}
              >
                {item.frontend.domains.length - 1} more
              </span>
            )}
          </>
        );
      },
    },
  ];

  const { item, updateItem, isDraft } = useDraftOfAPI();

  useEffect(() => {
    props.setTitle({
      value: 'Endpoints',
      noThumbtack: true,
      children: <VersionBadge />,
    });
  }, []);

  const client = nextClient.forEntityNext(nextClient.ENTITIES.APIS);

  const deleteItem = (newItem) => {
    return updateItem({
      ...item,
      routes: item.routes.filter((f) => f.id !== newItem.id),
    });
  };

  if (!item) return <SimpleLoader />;

  return (
    <>
      <InfoCollapse title="What is an Endpoint?">
        <p>
          An Endpoint represents a <strong>concrete operation</strong> exposed by your API. While
          the API defines the global governance (domain, context path, security, lifecycle), an
          endpoint defines <strong>how a specific request is matched and processed</strong>.
        </p>
        <p>
          Each endpoint defines a <strong>path</strong> that is automatically concatenated with the
          API domain and context path. The final exposed URL is therefore: API domain + API context
          path + Endpoint path
        </p>
        <p>
          For example, if your API is exposed on <code className="me-1">api.company.com</code>
          with a context path <code className="me-1">/v1</code>, and your endpoint path is
          <code className="me-1">/users</code>, the resulting public route becomes:{' '}
          <code>https://api.company.com/v1/users</code>
        </p>
      </InfoCollapse>

      <Table
        parentProps={{ params }}
        navigateTo={(item) =>
          historyPush(history, location, `/apis/${params.apiId}/endpoints/${item.id}/edit`)
        }
        navigateOnEdit={(item) =>
          historyPush(history, location, `/apis/${params.apiId}/endpoints/${item.id}/edit`)
        }
        selfUrl="endpoints"
        defaultTitle="Endpoint"
        itemName="Endpoint"
        columns={columns}
        deleteItem={deleteItem}
        fetchTemplate={client.template}
        fetchItems={() => Promise.resolve(item.routes || [])}
        defaultSort="name"
        defaultSortDesc="true"
        showActions={isDraft}
        showLink={false}
        extractKey={(item) => item.id}
        rowNavigation={true}
        hideAddItemAction={true}
        itemUrl={(i) => linkWithQuery(`/bo/dashboard/apis/${params.apiId}/endpoints/${i.id}/edit`)}
        rawEditUrl={true}
        injectTopBar={() => (
          <DraftOnly>
            <div className="btn-group input-group-btn">
              <Link
                className="btn btn-success btn-sm"
                to={{
                  pathname: 'endpoints/new',
                  search: location.search,
                }}
              >
                <i className="fas fa-plus-circle" /> Create new endpoint
              </Link>
              {props.injectTopBar}
            </div>
          </DraftOnly>
        )}
      />
    </>
  );
}
