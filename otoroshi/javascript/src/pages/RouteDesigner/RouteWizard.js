import React, { useEffect, useState } from 'react';
import { TextInput } from '../../components/inputs';
import { getOldPlugins, getPlugins, nextClient } from '../../services/BackOfficeServices';
import { Plugins } from '../../forms/ng_plugins';
import Loader from '../../components/Loader';

const RouteNameStep = ({ state, onChange }) => (
  <>
    <h3>Let's start with a name for your route</h3>

    <div className="">
      <label className="mb-2">Route name</label>
      <TextInput
        placeholder="Your route name..."
        flex={true}
        className="my-3"
        style={{
          fontSize: '2em',
          color: '#f9b000',
        }}
        label="Route name"
        value={state.route.name}
        onChange={onChange}
      />
    </div>
  </>
);

const RouteChooser = ({ state, onChange }) => (
  <>
    <h3>Select a route template</h3>
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: '10px',
      }}>
      {[
        { kind: 'empty', title: 'BLANK ROUTE', text: 'From scratch, no plugin added' },
        {
          kind: 'api',
          title: 'REST API',
          text: 'Already setup secured rest api with api management',
        },
        {
          kind: 'webapp',
          title: 'WEBAPP',
          text: 'Already setup web application with authentication',
        },
        {
          kind: 'graphql-proxy',
          title: 'GRAPHQL API',
          text: 'Already setup grapqhl api with api management and validation',
        },
        {
          kind: 'mock',
          title: 'QUICKSTART REST API',
          text: 'Already setup rest api with extended mocking capabilities',
        },
        {
          kind: 'graphql',
          title: 'GRAPHQL COMPOSER API',
          text: 'Create a graphql api from scratch from existing sources',
        },
      ].map(({ kind, title, text }) => (
        <button
          type="button"
          className={`btn ${
            state.route.kind === kind ? 'btn-save' : 'btn-dark'
          } py-3 wizard-route-chooser`}
          onClick={() => onChange(kind)}
          key={kind}>
          <h3 className="wizard-h3--small">{title}</h3>
          <label
            style={{
              flex: 1,
              display: 'flex',
              alignItems: 'center',
            }}>
            {text}
          </label>
        </button>
      ))}
    </div>
  </>
);

const FrontendStep = ({ state, onChange }) => (
  <>
    <h3>Expose your service over the world</h3>
    <div className="">
      <label className="mb-2">Domain name</label>
      <TextInput
        placeholder="Your domain name..."
        flex={true}
        className="my-3"
        value={state.route.domain}
        onChange={onChange}
      />
    </div>
  </>
);

const BackendStep = ({ state, onChange, onError, error }) => {
  useEffect(() => {
    checkChange('');
  }, []);
  const checkChange = (e) => {
    try {
      if (!e.includes('://')) onError('Missing protocol');
      else {
        new URL(e);
        onError(false);
      }
    } catch (err) {
      onError(err.message);
    }
    onChange(e);
  };

  const sentences = {
    'graphql-proxy': {
      title: 'Endpoint',
      text: 'Your endpoint',
    },
  };

  return (
    <>
      <h3>Define the target to redirect traffic</h3>
      <div className="">
        <label className="mb-2">{sentences[state.route.kind]?.title || 'Target URL'}</label>
        <TextInput
          placeholder={sentences[state.route.kind]?.text || 'Your target URL...'}
          flex={true}
          className="my-3"
          value={state.route.url}
          onChange={checkChange}
        />
        <label style={{ color: '#D5443F' }}>{error}</label>
      </div>
    </>
  );
};

const ProcessStep = ({ state, history }) => {
  const [loading, setLoading] = useState(true);
  const [createdRoute, setCreatedRoute] = useState({});

  const API_PLUGINS = [
    'cp:otoroshi.next.plugins.ForceHttpsTraffic',
    'cp:otoroshi.next.plugins.Cors',
    'cp:otoroshi.next.plugins.DisableHttp10',
    'cp:otoroshi.next.plugins.ApikeyCalls',
    'cp:otoroshi.next.plugins.OverrideHost',
    'cp:otoroshi.next.plugins.XForwardedHeaders',
    'cp:otoroshi.next.plugins.OtoroshiInfos',
    'cp:otoroshi.next.plugins.SendOtoroshiHeadersBack',
    'cp:otoroshi.next.plugins.OtoroshiChallenge',
  ];
  const PLUGINS = {
    api: API_PLUGINS,
    webapp: [
      'cp:otoroshi.next.plugins.ForceHttpsTraffic',
      'cp:otoroshi.next.plugins.BuildMode',
      'cp:otoroshi.next.plugins.MaintenanceMode',
      'cp:otoroshi.next.plugins.DisableHttp10',
      'cp:otoroshi.next.plugins.AuthModule',
      'cp:otoroshi.next.plugins.OverrideHost',
      'cp:otoroshi.next.plugins.OtoroshiInfos',
      'cp:otoroshi.next.plugins.OtoroshiChallenge',
      'cp:otoroshi.next.plugins.GzipResponseCompressor',
    ],
    empty: [],
    'graphql-proxy': ['cp:otoroshi.next.plugins.GraphQLProxy'],
    graphql: [...API_PLUGINS, 'cp:otoroshi.next.plugins.GraphQLBackend'],
    mock: [...API_PLUGINS, 'cp:otoroshi.next.plugins.MockResponses'],
  };

  useEffect(() => {
    Promise.all([
      Promise.resolve(Plugins),
      getOldPlugins(),
      getPlugins(),
      nextClient.template(nextClient.ENTITIES.ROUTES),
    ]).then(([plugins, oldPlugins, metadataPlugins, template]) => {
      const url = ['mock', 'graphql'].includes(state.route.kind)
        ? {
            pahtname: '/',
            hostname: '',
            protocol: 'https://',
          }
        : new URL(state.route.url);
      const secured = url.protocol.includes('https');

      const selectedPlugins = PLUGINS[state.route.kind];

      nextClient
        .create(nextClient.ENTITIES.ROUTES, {
          ...template,
          enabled: false,
          name: state.route.name,
          frontend: {
            ...template.frontend,
            domains: [state.route.domain],
          },
          plugins: [
            ...plugins.map((p) => ({
              ...(metadataPlugins.find((metaPlugin) => metaPlugin.id === p.id) || {}),
              ...p,
            })),
            ...oldPlugins,
          ]
            .filter((f) => selectedPlugins.includes(f.id))
            .map((plugin) => {
              return {
                config: plugin.default_config,
                debug: false,
                enabled: true,
                exclude: [],
                include: [],
                plugin: plugin.id,
              };
            }),
          backend: {
            ...template.backend,
            root: url.pathname,
            targets: [
              {
                ...template.backend.targets[0],
                hostname: url.hostname,
                port: url.port || (secured ? 443 : 80),
                tls_config: {
                  ...template.backend.targets[0].tls_config,
                  enabled: secured,
                },
              },
            ],
          },
        })
        .then((r) => {
          setLoading(false);
          setCreatedRoute(r);
        });
    });
  }, []);

  const pluginsLength = PLUGINS[state.route.kind].length;

  const timers = PLUGINS[state.route.kind].reduce((acc, _, i) => {
    if (i === 0) return [100 + Math.floor(Math.random() * 300)];
    return [...acc, acc[i - 1] + 100 + Math.floor(Math.random() * 300)];
  }, []);

  return (
    <>
      {PLUGINS[state.route.kind].map((plugin, i) => (
        <LoaderItem
          timeout={timers[i]}
          text={`Configure ${plugin
            .split('.')
            .slice(-1)[0]
            .replace(/([A-Z])/g, ' $1')
            .replace(/^./, (str) => str.toUpperCase())}`}
          key={plugin}
        />
      ))}
      <Loader
        loading={loading}
        minLoaderTime={pluginsLength === 0 ? 1500 : 100 + timers[timers.length - 1]}
        loadingChildren={
          <h3 style={{ textAlign: 'center' }} className="mt-3">
            Creation steps
          </h3>
        }>
        {pluginsLength === 0 && (
          <button
            className="btn btn-save mx-auto"
            style={{ borderRadius: '50%', width: '42px', height: '42px' }}>
            <i className="fas fa-check" />
          </button>
        )}
        <div
          className="mt-3"
          style={{
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
            flexDirection: 'column',
          }}>
          <h3>Your route is now available!</h3>

          <button
            className="btn btn-save"
            onClick={() => {
              if (['mock', 'graphql'].includes(state.route.kind))
                history.push(`/routes/${createdRoute.id}?tab=flow`, {
                  plugin:
                    state.route.kind === 'mock'
                      ? 'cp:otoroshi.next.plugins.MockResponse'
                      : 'cp:otoroshi.next.plugins.GraphQLBackend',
                });
              else history.push(`/routes/${createdRoute.id}?tab=flow`);
            }}>
            {state.route.kind === 'mock'
              ? 'Start creating mocks'
              : state.route.kind === 'graphql'
              ? 'Start creating schema'
              : 'Start editing plugins'}
          </button>
        </div>
      </Loader>
    </>
  );
};

const LoaderItem = ({ text, timeout }) => {
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const timeout = setTimeout(() => setLoading(false), timeout);

    return () => timeout;
  }, []);

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        marginBottom: '6px',
      }}>
      <Loader loading={loading} minLoaderTime={timeout}>
        <button
          className="btn btn-save mx-auto"
          style={{
            borderRadius: '50%',
            width: '32px',
            height: '32px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
          }}>
          <i className="fas fa-check" />
        </button>
      </Loader>
      <div
        style={{
          flex: 1,
          marginLeft: '12px',
          color: loading ? '#eee' : '#ccc',
          fontWeight: loading ? 'normal' : 'bold',
        }}>
        {text}
      </div>
    </div>
  );
};

export class RouteWizard extends React.Component {
  state = {
    steps: 4,
    step: 1,
    route: {
      name: 'My new route',
      domain: '',
      url: '',
      kind: 'api',
    },
    error: undefined,
  };

  prevStep = () => {
    if (this.state.step - 1 === 4 && ['mock', 'graphql'].includes(this.state.route.kind))
      this.setState({
        step: 3,
        error: undefined,
      });
    else
      this.setState({
        step: this.state.step - 1,
        error: undefined,
      });
  };

  nextStep = () => {
    if (this.state.step + 1 === 4 && ['mock', 'graphql'].includes(this.state.route.kind))
      this.setState({
        step: 5,
      });
    else
      this.setState({
        step: this.state.step + 1,
      });
  };

  onRouteFieldChange = (field, value) => {
    this.setState({
      route: {
        ...this.state.route,
        [field]: value,
      },
    });
  };

  render() {
    const { steps, step, error } = this.state;

    return (
      <div className="wizard">
        <div className="wizard-container">
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: '2.5rem' }}>
            <label style={{ fontSize: '1.15rem' }}>
              <i
                className="fas fa-times me-3"
                onClick={() => this.props.hide()}
                style={{ cursor: 'pointer' }}
              />
              <span>{`Create a new route(Step ${step <= steps ? step : steps} of ${steps})`}</span>
            </label>

            <div className="wizard-content">
              {step === 1 && (
                <RouteNameStep
                  state={this.state}
                  onChange={(n) => this.onRouteFieldChange('name', n)}
                />
              )}
              {step === 2 && (
                <RouteChooser
                  state={this.state}
                  onChange={(n) => this.onRouteFieldChange('kind', n)}
                />
              )}
              {step === 3 && (
                <FrontendStep
                  state={this.state}
                  onChange={(n) => this.onRouteFieldChange('domain', n)}
                />
              )}
              {step === 4 && (
                <BackendStep
                  onError={(err) => this.setState({ error: err })}
                  error={error}
                  state={this.state}
                  onChange={(n) => this.onRouteFieldChange('url', n)}
                />
              )}
              {step === 5 && <ProcessStep state={this.state} history={this.props.history} />}
              {step <= steps && (
                <div
                  style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}
                  className="mt-3">
                  {step !== 1 && (
                    <label style={{ color: '#f9b000' }} onClick={this.prevStep}>
                      Previous
                    </label>
                  )}
                  <button
                    className="btn btn-save"
                    style={{
                      backgroundColor: '#f9b000',
                      borderColor: '#f9b000',
                      padding: '12px 48px',
                    }}
                    disabled={error}
                    onClick={this.nextStep}>
                    {step === steps ? 'Create' : 'Continue'}
                  </button>
                </div>
              )}
            </div>
          </div>
        </div>
      </div>
    );
  }
}
