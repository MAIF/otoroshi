import React, { useState, Suspense } from 'react';

import { nextClient } from '../../services/BackOfficeServices';
import { toUpperCaseLabels } from '../../util';
import { FeedbackButton } from './FeedbackButton';
import cloneDeep from 'lodash/cloneDeep';
import { useHistory, useRouteMatch } from 'react-router-dom';
import { NgForm } from '../../components/nginputs';
import { Backend, Frontend } from '../../forms/ng_plugins';

const CodeInput = React.lazy(() => Promise.resolve(require('../../components/inputs/CodeInput')));

export const HTTP_COLORS = {
  GET: 'var(--http_color-get)',
  POST: 'var(--http_color-post)',
  DELETE: 'var(--http_color-delete)',
  PUT: 'var(--http_color-put)',
  HEAD: 'var(--http_color-others)',
  PATCH: 'var(--http_color-others)',
  OPTIONS: 'var(--http_color-others)',
};

function Methods({ frontend }) {
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
}

function Uri({ frontend, domain }) {
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
}

class RouteForms extends React.Component {
  state = {
    usingExistingBackend: false,
    usingJsonView: false,
  };

  flow = ['frontend', 'backend'];

  render() {
    const { usingJsonView } = this.state;
    const { route } = this.props;

    const schema = {
      frontend: {
        type: 'form',
        label: 'Frontend',
        flow: Frontend.flow,
        collapsable: true,
        schema: toUpperCaseLabels(Frontend.schema),
        props: {
          showSummary: true,
        },
      },
      backend: {
        type: 'form',
        label: 'Backend',
        collapsable: true,
        props: {
          showSummary: true,
        },
        flow: (props) => {
          if (props.backend_ref || props.using_backend_ref)
            return ['using_backend_ref', 'backend_ref'];
          else return ['using_backend_ref', ...Backend.flow.otoroshi_full_flow];
        },
        schema: {
          ...toUpperCaseLabels(Backend.schema),
          using_backend_ref: {
            type: 'boolean',
            label: 'Using backend ref',
          },
          backend_ref: {
            type: 'select',
            label: 'Existing backend',
            props: {
              options: this.props.backends,
              optionsTransformer: {
                label: 'name',
                value: 'id',
              },
              placeholder: 'Select an existing backend',
            },
          },
        },
      },
    };

    return (
      <div
        className="p-2"
        style={{
          borderBottomLeftRadius: '4px',
          borderBottomRightRadius: '4px',
        }}
        onClick={(e) => e.stopPropagation()}>
        <div className="d-flex justify-content-end">
          <button
            type="button"
            className="btn btn-sm btn-success me-1"
            title="Edit this route"
            onClick={(e) => {
              e.stopPropagation();
              this.props.history.replace(
                `${this.props.url.split('?')[0]}?tab=route_plugins&view_plugins=${this.props.index}`
              );
            }}>
            <i className="fas fa-pencil-ruler" />
          </button>
          <button
            className="btn btn-sm btn-danger me-1"
            title="Delete this route"
            onClick={(e) => {
              e.stopPropagation();
              window.newConfirm('Delete this route ?').then((ok) => {
                if (ok) this.props.removeRoute(e);
              });
            }}>
            <i className="fas fa-trash" />
          </button>
          <button
            className="btn btn-sm me-3"
            style={{ backgroundColor: 'var(--color-primary)', color: '#fff' }}
            title="Switch Form view / Json view"
            onClick={(e) => {
              e.stopPropagation();
              this.setState({
                usingJsonView: !usingJsonView,
              });
            }}>
            {usingJsonView ? 'Form view' : 'Advanced json'}
          </button>
        </div>
        <div className="flex p-3 pb-2 pt-0 route-forms-form">
          {!usingJsonView && (
            <NgForm
              value={{
                ...route,
                backend: {
                  ...route.backend,
                  backend_ref: route.backend_ref,
                  using_backend_ref: route.backend_ref ? true : route.backend.using_backend_ref,
                },
              }}
              useBreadcrumb={true}
              onChange={(e) => {
                this.props.updateRoute({
                  ...e,
                  backend_ref: e.backend.using_backend_ref ? e.backend.backend_ref : undefined,
                });
              }}
              schema={schema}
              flow={this.flow}
            />
          )}
          {usingJsonView && (
            <Suspense fallback={<div>Loading ....</div>}>
              <div className="pt-2">
                <CodeInput
                  value={route}
                  onChange={this.props.updateRoute}
                  mode="json"
                  editorOnly={true}
                />
              </div>
            </Suspense>
          )}
        </div>
        <div className="d-flex align-items-center justify-content-end me-3">
          <FeedbackButton
            text="Update the route"
            icon={() => <i className="fas fa-paper-plane" />}
            onPress={this.props.saveRoute}
          />
        </div>
      </div>
    );
  }
}

const Route = (props) => {
  const history = useHistory();
  const { url } = useRouteMatch();

  const [open, setOpen] = useState(
    (props.viewPlugins !== undefined && String(props.viewPlugins) === String(props.index)) || false
  );

  const onClick = () => {
    const newState = !open;
    setOpen(newState);

    const url = new URL(window.location);
    url.searchParams.set('view_plugins', newState ? props.index : null);
    window.history.pushState({}, '', url);
  };

  const { frontend, plugins } = props.route;

  return (
    <div
      className="route-item my-2"
      style={{ minHeight: open ? '200px' : 'initial' }}
      onClick={onClick}>
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
          <button
            className="btn btn-sm"
            style={{ background: 'var(--color-primary)', borderColor: 'var(--color-primary)' }}
            onClick={onClick}>
            <i className={`fas fa-chevron-${open ? 'up' : 'down'}`} />
          </button>
        </div>
      </div>
      {open && <RouteForms {...props} history={history} url={url} />}
    </div>
  );
};

function OpenapiImport(props) {
  const [state, setState] = useState({ openapi: '', domain: '' });

  const schema = {
    openapi: {
      type: 'string',
      label: 'Openapi URL',
    },
    domain: {
      type: 'string',
      label: 'Exposed domain',
    },
  };

  return (
    <>
      <div className="modal-body">
        <NgForm value={state} onChange={setState} schema={schema} />
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

export default class RouteCompositions extends React.Component {
  state = {
    templates: [],
    backends: [],
  };

  componentDidMount() {
    Promise.all([
      nextClient.template(nextClient.ENTITIES.SERVICES),
      nextClient.find(nextClient.ENTITIES.BACKENDS),
    ]).then(([templates, backends]) => {
      this.setState({ templates, backends });
    });

    this.props.setSaveButton(
      <FeedbackButton
        className="ms-2"
        text="Save route composition"
        icon={() => <i className="fas fa-paper-plane" />}
        onPress={this.saveRoute}
      />
    );
  }

  updateRoute(index, item) {
    let r = this.props.service.routes.map((route, i) => {
      if (i === index) return item;
      return route;
    });

    if (this.props.service.routes.length === 0) {
      r = [item];
    } else if (index >= this.props.service.routes.length) {
      r = [...this.props.service.routes, item];
    }

    this.props.setRoutes(r);
  }

  saveRoute = (routes) => {
    return nextClient
      .update(nextClient.ENTITIES.SERVICES, {
        ...this.props.service,
        routes: routes || this.props.service.routes,
      })
      .then((service) => this.props.setRoutes(service.routes));
  };

  removeRoute(idx) {
    this.props.setRoutes(this.props.service.routes.filter((_, i) => i !== idx));
  }

  importOpenApi = () => {
    return window
      .popup(
        'Import routes from openapi',
        (ok, cancel) => <OpenapiImport ok={ok} cancel={cancel} />,
        { __style: { width: '100%' } }
      )
      .then(this.importOpenApiModalResponse);
  };

  importOpenApiModalResponse = (body) => {
    if (body) {
      fetch('/bo/api/proxy/api/route-compositions/_openapi', {
        method: 'POST',
        credentials: 'include',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
      })
        .then((r) => r.json())
        .then((imported) => {
          this.saveRoute(imported.routes);
        });
    }
  };

  render() {
    const { service, viewPlugins } = this.props;
    const { templates } = this.state;

    return (
      <div className="h-100 flex-column">
        <div className="d-flex">
          <button
            className="btn btn-primary me-1"
            onClick={() => {
              const newItem = cloneDeep(templates?.routes[0]);
              this.props.setRoutes([newItem, ...service.routes]);
            }}>
            <i className="fas fa-road me-1" />
            Create a new route
          </button>
          <FeedbackButton
            type="info"
            text="Import routes from openapi"
            icon={() => <i className="fas fa-file-code me-1" />}
            onPress={this.importOpenApi}
          />
        </div>
        <div>
          {(service.routes || []).map((route, i) => (
            <Route
              route={route}
              key={route.id}
              index={i}
              originalRoute={route}
              updateRoute={(item) => this.updateRoute(i, item)}
              removeRoute={() => this.removeRoute(i)}
              saveRoute={this.saveRoute}
              viewPlugins={viewPlugins}
              backends={this.state.backends}
            />
          ))}
          {service.routes.length === 0 && (
            <h4
              className="text-center"
              style={{
                fontSize: '1.25em',
              }}>
              Your route compositions is empty. Start by adding a new route or importing all routes
              from your open api.
            </h4>
          )}
        </div>
      </div>
    );
  }
}
