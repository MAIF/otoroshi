import React, { useState, useEffect, useImperativeHandle } from 'react';

import { nextClient } from '../../services/BackOfficeServices';
import { toUpperCaseLabels } from '../../util';
import { FeedbackButton } from './FeedbackButton';
import isEqual from 'lodash/isEqual';
import cloneDeep from 'lodash/cloneDeep';
import { useHistory, useRouteMatch } from 'react-router-dom';
import { NgForm, NgSelectRenderer } from '../../components/nginputs';
import { Backend, Frontend } from './NgPlugins';

export const HTTP_COLORS = {
  GET: 'rgb(52, 170, 182)',
  POST: 'rgb(117, 189, 93)',
  DELETE: 'rgb(238, 106, 86)',
  PATCH: '#9b59b6',
  HEAD: '#9b59b6',
  PUT: 'rgb(230, 195, 0)',
  OPTIONS: '#9b59b6',
};

const Methods = ({ frontend }) => {
  const hasMethods = frontend.methods && frontend.methods.length > 0;
  const methods = hasMethods
    ? frontend.methods.map((m, i) => (
      <span
        key={`frontendmethod-${i}`}
        className={`badge me-1`}
        style={{ backgroundColor: HTTP_COLORS[m] }}>
        {m}
      </span>
    ))
    : [<span className="badge bg-dark">ALL</span>];
  return (
    <div className="d-flex-between">
      {methods.map((method, i) => (
        <div key={`method${i}`} style={{ minWidth: 34 }}>
          {method}
        </div>
      ))}
    </div>
  );
};

const Uri = ({ frontend, domain }) => {
  const exact = frontend.exact;
  const end = exact ? '' : domain.indexOf('/') < 0 ? '/*' : '*';
  const start = 'http://';
  return (
    <div className="d-flex-between">
      <span className="flex ms-2" style={{ fontFamily: 'monospace' }}>
        {start}
        {domain}
        {end}{' '}
        {frontend.exact && (
          <span className="badge me-1" style={{ backgroundColor: '#A3A3A3' }}>
            EXACT
          </span>
        )}
      </span>
    </div>
  );
};

const SaveButton = ({ disabled, saveChanges, disablePadding }) => (
  <div className={`d-flex align-items-center justify-content-end ${disablePadding ? '' : 'pt-3'}`}>
    <FeedbackButton
      text="Update the route"
      // disabled={disabled}
      icon={() => <i className="fas fa-paper-plane" />}
      onPress={saveChanges}
    />
  </div>
);

const BackendSelector = ({ setExistingBackend, usingExistingBackend }) => (
  <div className="d-flex mt-2 mb-3">
    <button
      className="btn btn-sm new-backend-button"
      onClick={(e) => {
        e.stopPropagation();
        setExistingBackend(false);
      }}
      style={{ backgroundColor: usingExistingBackend ? '#494849' : '#f9b000' }}>
      Create a new backend
    </button>
    <button
      className="btn btn-sm new-backend-button"
      onClick={(e) => {
        e.stopPropagation();
        setExistingBackend(true);
      }}
      style={{ backgroundColor: usingExistingBackend ? '#f9b000' : '#494849' }}>
      Select an existing backend
    </button>
  </div>
);

class RouteForms extends React.Component {
  state = {
    schemas: null,
    backends: [],
    usingExistingBackend: false,
    usingJsonView: false,
  };

  componentDidMount() {
    nextClient.find(nextClient.ENTITIES.BACKENDS)
      .then(backends => {
        this.setState({
          schemas: {
            frontend: {
              config_flow: Frontend.flow,
              config_schema: toUpperCaseLabels(Frontend.schema),
            },
            backend: {
              config_flow: Backend.flow,
              config_schema: toUpperCaseLabels(Backend.schema),
            },
          },
          backends,
        });
      });
  }

  saveChanges = (newRoute) => this.props.updateRoute(newRoute);

  disabledSaveButton = () => {
    const { originalRoute } = this.props;
    const { frontend, backend, backendRef } = this.state;

    return (
      isEqual(originalRoute.frontend, frontend) &&
      isEqual(originalRoute.backend, backend) &&
      isEqual(originalRoute.backend_ref, backendRef)
    );
  };

  render() {
    const { schemas, usingJsonView, usingExistingBackend, backends } = this.state;
    const { saveRoute, route } = this.props;

    if (!schemas) return null;

    return (
      <div className="p-2">
        <div className="d-flex justify-content-end">
          <button
            className="btn btn-sm mx-1"
            style={{ backgroundColor: '#f9b000', color: '#fff' }}
            onClick={(e) => {
              e.stopPropagation();
              this.setState({
                usingJsonView: !usingJsonView,
              });
            }}>
            {usingJsonView ? 'Form view' : 'Advanced json'}
          </button>
        </div>
        <div className="d-flex mt-3">
          <div className="flex p-3 route-forms-form">
            <h5 className="route-forms-title">Frontend</h5>
            <RouteForm
              onSubmit={(e) => {
                this.saveChanges(e);
              }}
              dirtyField="frontend"
              value={route}
              schema={schemas.frontend.config_schema}
              flow={schemas.frontend.config_flow}
              usingJsonView={usingJsonView}
            />
            <SaveButton disabled={this.disabledSaveButton()} saveChanges={saveRoute} />
          </div>
          <div className="flex ms-1 p-3 route-forms-form">
            <h5 className="route-forms-title">Backend</h5>
            <BackendSelector
              setExistingBackend={(e) => this.setState({ usingExistingBackend: e })}
              usingExistingBackend={usingExistingBackend}
            />
            {usingExistingBackend && (
              <div className="mt-3">
                <NgSelectRenderer
                  value={route.backend_ref}
                  placeholder="Select an existing backend"
                  ngOptions={{
                    spread: true,
                  }}
                  onChange={(backendRef) =>
                    this.saveChanges({
                      ...route,
                      backend_ref: this.state.usingExistingBackend ? backendRef : null,
                    })
                  }
                  options={backends}
                  optionsTransformer={(arr) =>
                    arr.map((item) => ({ label: item.name, value: item.id }))
                  }
                />
              </div>
            )}

            {!usingExistingBackend && (
              <RouteForm
                onSubmit={(e) => this.saveChanges(e)}
                dirtyField="backend"
                value={route}
                schema={schemas.backend.config_schema}
                flow={schemas.backend.config_flow}
                usingJsonView={usingJsonView}
              />
            )}
          </div>
        </div>
        <div className="d-flex justify-content-end pt-3">
          <SaveButton
            disabled={this.disabledSaveButton()}
            saveChanges={saveRoute}
            disablePadding={true}
          />
        </div>
      </div>
    );
  }
}

const RouteForm = React.memo(
  ({ dirtyField, value, schema, flow, usingJsonView, onSubmit }) => {
    return (
      <NgForm
        useBreadcrumb={true}
        value={value}
        schema={
          usingJsonView
            ? {
              [dirtyField]: {
                type: 'json',
                props: {
                  label: '',
                  editorOnly: true,
                  ngOptions: {
                    spread: true,
                  },
                },
              },
            }
            : schema
        }
        flow={usingJsonView ? [dirtyField] : flow}
        onChange={onSubmit}
      />
    );
  },
  (prev, next) =>
    prev.value === next.value &&
    prev.usingJsonView === next.usingJsonView &&
    prev.flow === next.flow
);

const Route = (props) => {
  const history = useHistory();
  const { url } = useRouteMatch();

  const [open, setOpen] = useState(
    (props.viewPlugins !== undefined && String(props.viewPlugins) === String(props.index)) || false
  );
  const { frontend, plugins } = props.route;

  return (
    <div className="route-item my-2" style={{ minHeight: open ? '200px' : 'initial' }}>
      <div
        className={`d-flex-between ${open ? 'route-forms-header' : ''}`}
        style={{
          padding: '6px',
          paddingBottom: open ? '8px' : '6px',
        }}>
        <div className="d-flex-between">
          <Methods frontend={frontend} />
          <div className="flex-column">
            {frontend.domains.map((domain) => (
              <Uri frontend={frontend} domain={domain} />
            ))}
          </div>
        </div>
        <div className="d-flex-between">
          {plugins?.length > 0 && <span className="badge bg-dark me-2">custom plugins</span>}
          {open && (
            <button
              className="btn btn-sm btn-success me-2"
              onClick={(e) => {
                e.stopPropagation();
                history.replace(`${url}?tab=route_plugins&view_plugins=${props.index}`);
              }}>
              <i className="fas fa-pencil-ruler" />
            </button>
          )}
          {open && (
            <button
              className="btn btn-sm btn-danger me-2"
              onClick={(e) => {
                e.stopPropagation();
                props.removeRoute(e);
              }}>
              <i className="fas fa-trash" />
            </button>
          )}
          <button
            className="btn btn-sm"
            style={{ background: '#f9b000', borderColor: '#f9b000' }}
            onClick={() => {
              const newState = !open;
              setOpen(newState);

              const url = new URL(window.location);
              url.searchParams.set('view_plugins', newState ? props.index : null);
              window.history.pushState({}, '', url);
            }}>
            <i className={`fas fa-chevron-${open ? 'up' : 'down'}`} />
          </button>
        </div>
      </div>
      {open && <RouteForms {...props} />}
    </div>
  );
};

export default ({ service, setSaveButton, setService, viewPlugins, ref }) => {
  const [routes, setRoutes] = useState([]);
  const [templates, setTemplates] = useState({});
  const [shouldUpdateRoutes, setUpdatesRoutes] = useState(false);
  const history = useHistory();

  useEffect(() => {
    nextClient.template(nextClient.ENTITIES.SERVICES).then(setTemplates);
  }, []);

  useEffect(() => {
    setRoutes(cloneDeep([...(service.routes || [])]));
  }, [service.id]);

  useImperativeHandle(ref, () => ({
    onTestingButtonClick() {
      history.push(`/routes/${service.id}?tab=flow`, { showTryIt: true });
    },
  }));

  useEffect(() => {
    setSaveButton(
      <FeedbackButton
        className="ms-2"
        _disabled={!shouldUpdateRoutes}
        text="Save route composition"
        icon={() => <i className="fas fa-paper-plane" />}
        onPress={saveRoute}
      />
    );
  }, [shouldUpdateRoutes, routes]);

  const updateRoute = (index, item) => {
    let r = routes.map((route, i) => {
      if (i === index) return item;
      return route;
    });

    if (routes.length === 0) r = [item];
    else if (index >= routes.length) r = [...routes, item];

    setUpdatesRoutes(!isEqual(r, service.routes));
    setRoutes([...r]);
  };

  const saveRoute = () => {
    return nextClient
      .update(nextClient.ENTITIES.SERVICES, {
        ...service,
        routes: routes,
      })
      .then((res) => {
        setUpdatesRoutes(false);
        setService(res);
      });
  };

  const removeRoute = (idx) => {
    const newRoutes = routes.filter((_, i) => i !== idx);
    setRoutes([...newRoutes]);
    setUpdatesRoutes(!isEqual(newRoutes, service.routes));
  };

  const importOpenApi = () => {
    function OpenapiImport(props) {
      const [state, setState] = useState({ openapi: '', domain: '' });
      return (
        <>
          <div className="modal-body">
            <form className="form-horizontal">
              <div>
                <div className="row mb-3">
                  <label className="col-xs-12 col-sm-2 col-form-label">Openapi URL</label>
                  <div className="col-sm-10">
                    <input
                      type="text"
                      className="form-control"
                      value={state.openapi}
                      onChange={(e) => setState({ ...state, openapi: e.target.value })}
                    />
                  </div>
                </div>
              </div>
              <div>
                <div className="row mb-3">
                  <label className="col-xs-12 col-sm-2 col-form-label">Exposed domain</label>
                  <div className="col-sm-10">
                    <input
                      type="text"
                      className="form-control"
                      value={state.domain}
                      onChange={(e) => setState({ ...state, domain: e.target.value })}
                    />
                  </div>
                </div>
              </div>
            </form>
          </div>
          <div className="modal-footer">
            <button
              type="button"
              className="btn btn-danger"
              onClick={(e) => {
                e.stopPropagation();
                props.cancel(e);
              }}>
              Close
            </button>
            <button
              type="button"
              className="btn btn-success"
              onClick={(e) => {
                e.stopPropagation();
                props.ok(state);
              }}>
              Ok
            </button>
          </div>
        </>
      );
    }

    window
      .popup(
        'Import routes from openapi',
        (ok, cancel) => <OpenapiImport ok={ok} cancel={cancel} />,
        { __style: { width: '100%' } }
      )
      .then((body) => {
        if (body) {
          fetch('/bo/api/proxy/api/experimental/services/_openapi', {
            method: 'POST',
            credentials: 'include',
            headers: {
              'Content-Type': 'application/json',
            },
            body: JSON.stringify(body),
          })
            .then((r) => r.json())
            .then((imported) => {
              const routes = [...routes, ...imported.routes];
              nextClient
                .update(nextClient.ENTITIES.SERVICES, {
                  ...service,
                  routes,
                })
                .then((s) => setRoutes(s.routes));
            });
        }
      });
  };

  return (
    <div className="h-100 flex-column">
      <div className="d-flex mb-3">
        <button
          className="btn btn-info"
          onClick={() => {
            const newItem = cloneDeep(templates?.routes[0]);
            updateRoute(routes.length, newItem);
          }}>
          <i className="fas fa-road me-1" />
          Create a new route
        </button>
        <button
          className="btn btn-info mx-1"
          style={{ marginLeft: 10 }}
          onClick={() => {
            importOpenApi();
          }}>
          <i className="fas fa-file-code me-1" />
          Import routes from openapi
        </button>
      </div>
      <div>
        {routes.map((route, i) => (
          <Route
            route={{ ...route }}
            key={route.id}
            index={i}
            originalRoute={{ ...service.routes[i] }}
            updateRoute={(item) => updateRoute(i, item)}
            removeRoute={() => removeRoute(i)}
            saveRoute={saveRoute}
            viewPlugins={viewPlugins}
          />
        ))}
        {routes.length === 0 && <h4 className='text-center' style={{
          fontSize: '1.25em'
        }}>
          Your route compositions is empty. Start by adding a new route or importing all routes from your open api.
        </h4>}
      </div>
    </div>
  );
};
