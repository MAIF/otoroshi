import React, { Component } from 'react';
import { BrowserRouter as Router, Route, Link, Switch, withRouter } from 'react-router-dom';
import queryString from 'query-string';
import Select from 'react-select';

import { ServicePage } from '../pages/ServicePage';
import { ServiceAnalyticsPage } from '../pages/ServiceAnalyticsPage';
import { DocumentationPage } from '../pages/DocumentationPage';
import { ServiceApiKeysPage, ApiKeysPage } from '../pages/ServiceApiKeysPage';
import { ServiceHealthPage } from '../pages/ServiceHealthPage';
import { ServiceEventsPage } from '../pages/ServiceEventsPage';
import { ServiceLiveStatsPage } from '../pages/ServiceLiveStatsPage';
import { NotFoundPage } from '../pages/NotFoundPage';
import { DangerZonePage } from '../pages/DangerZonePage';
import { DataExportersPage } from '../pages/DataExportersPage';
import { GroupsPage } from '../pages/GroupsPage';
import { HomePage } from '../pages/HomePage';
import { ServicesPage } from '../pages/ServicesPage';
import { SessionsPage } from '../pages/SessionsPage';
import { U2FRegisterPage } from '../pages/U2FRegisterPage';
import { CleverPage } from '../pages/CleverPage';
import { EurekaServersPage } from '../pages/EurekaServersPage';
import { EurekaServerPage } from '../pages/EurekaServerPage';
import { AuditPage } from '../pages/AuditPage';
import { Top10servicesPage } from '../pages/Top10servicesPage';
import { LoggersPage } from '../pages/LoggersPage';
import { AlertPage } from '../pages/AlertPage';
import { ServicesMapPage } from '../pages/ServicesMapPage';
import { PrivateAppsSessionsPage } from '../pages/PrivateAppsSessionsPage';
import { GlobalEventsPage } from '../pages/GlobalEventsPage';
import { GlobalAnalyticsPage } from '../pages/GlobalAnalyticsPage';
import { GlobalStatusPage } from '../pages/GlobalStatusPage';
import { SnowMonkeyPage } from '../pages/SnowMonkeyPage';
import { JwtVerifiersPage } from '../pages/JwtVerifiersPage';
import { AuthModuleConfigsPage } from '../pages/AuthModuleConfigsPage';
import { CertificatesPage } from '../pages/CertificatesPage';
import { ClusterPage } from '../pages/ClusterPage';
import { TunnelsPage, TunnelPage } from '../pages/TunnelsPage';
import { ScriptsPage } from '../pages/ScriptsPage';
import { ClientValidatorsPage } from '../pages/ClientValidatorsPage';
import { GroupStatsPage } from '../pages/GroupStatsPage';
import { ApiKeyStatsPage } from '../pages/ApiKeyStatsPage';
import { TcpServicesPage } from '../pages/TcpServicesPage';
import { ProvidersDashboardPage } from '../pages/ProvidersDashboardPage';
import { ResourceLoaderPage } from '../pages/ResourceLoaderPage';
import RouteDesignerPage from '../pages/RouteDesigner';
import { BackendsPage } from '../pages/BackendsPage';
import { MetricsPage } from '../pages/MetricsPage';

import { TopBar } from '../components/TopBar';
import { ReloadNewVersion } from '../components/ReloadNewVersion';
import { UpdateOtoroshiVersion } from '../components/UpdateOtoroshiVersion';
import { DefaultSidebar } from '../components/DefaultSidebar';
import { DynamicSidebar } from '../components/DynamicSidebar';
import { DynamicTitle } from '../components/DynamicTitle';

import * as BackOfficeServices from '../services/BackOfficeServices';

import { createTooltip } from '../tooltips';
import { TenantsPage } from '../pages/TenantsPage';
import { TeamsPage } from '../pages/TeamsPage';
import { Toasts } from '../components/Toasts';
import { NgFormPlayground } from '../components/nginputs';
import Loader from '../components/Loader';
import { globalConfig } from 'antd/lib/config-provider';

class BackOfficeAppContainer extends Component {
  constructor(props) {
    super(props);
    this.state = {
      groups: [],
      lines: [],
      catchedError: null,
      env: null,
      loading: true,
    };
  }

  addService = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    BackOfficeServices.createNewService().then((r) => {
      ServicePage.__willCreateService = r;
      this.props.history.push({
        pathname: `/lines/${r.env}/services/${r.id}`,
      });
    });
  };

  componentWillReceiveProps(nextProps) {
    if (nextProps.children !== this.props.children) {
      DynamicSidebar.setContent(null);
    }
  }

  componentDidMount() {
    Promise.all([
      BackOfficeServices.env(),
      BackOfficeServices.fetchLines(),
      BackOfficeServices.findAllGroups(),
    ]).then(([env, lines, groups]) => {
      this.setState({
        env,
        lines,
        groups,
        loading: false,
        usedNewEngine: env.newEngineEnabled
      });
    });
  }

  checkEngineUsage = globalConfig => {
    const plugins = globalConfig.plugins

    try {
      if (plugins.refs && plugins.config) {
        return plugins.refs.includes('cp:otoroshi.next.proxy.ProxyEngine') &&
          plugins.config['NextGenProxyEngine'] &&
          plugins.config['NextGenProxyEngine'].enabled
      } else {
        return false;
      }
    } catch (_) {
      return false
    }
  }

  componentDidCatch(e) {
    this.setState({ catchedError: e });
  }

  decorate = (Component, props) => {
    const newProps = { ...props };
    const query = queryString.parse((props.location || { search: '' }).search) || {};
    newProps.location.query = query;
    newProps.params = newProps.match.params || {};
    return (
      <Component
        globalEnv={this.state.env}
        // Pass env to the child to avoid to fetch it again
        env={this.state.env}
        setTitle={(t) => DynamicTitle.setContent(t)}
        getTitle={() => DynamicTitle.getContent()}
        setSidebarContent={(c) => DynamicSidebar.setContent(c)}
        {...newProps}
      />
    );
  };

  render() {
    const classes = ['backoffice-container'];
    if (
      this.props.children &&
      this.props.children.type &&
      this.props.children.type.backOfficeClassName
    ) {
      classes.push(this.props.children.type.backOfficeClassName);
    }

    return (
      <Loader loading={this.state.loading}>
        <ReloadNewVersion />
        {this.state.env && (
          <>
            <UpdateOtoroshiVersion env={this.state.env} />
            <TopBar
              setTitle={(t) => DynamicTitle.setContent(t)}
              {...this.props}
              changePassword={this.state.env.changePassword}
              env={this.state.env}
            />
          </>
        )}
        <div className="container-fluid">
          <div className="row">
            <div className="col-sm-2 sidebar" id="sidebar">
              <div className="sidebar-container">
                <div className="sidebar-content">
                  {this.state.env && <GlobalTenantSelector env={this.state.env} />}
                  <ul className="nav flex-column nav-sidebar">
                    <li>
                      <h2>
                        <Link
                          to="/"
                          {...createTooltip('Home dashboard of Otoroshi displaying global metrics')}
                          onClick={() => {
                            DynamicTitle.setContent(null);
                          }}>
                          <i className="fas fa-tachometer-alt" />
                          Dashboard
                        </Link>
                      </h2>
                    </li>
                  </ul>
                  <DynamicSidebar />
                  <DefaultSidebar
                    lines={this.state.lines}
                    addService={this.addService}
                    env={this.state.env}
                  />
                  <div className="bottom-sidebar">
                    {/*<img src="/assets/images/otoroshi-logo-inverse.png" width="16" /> version {window.__currentVersion}*/}
                    {this.state.env && (
                      <span onClick={(e) => (window.location = '/bo/dashboard/snowmonkey')}>
                        {this.state.env.snowMonkeyRunning &&
                          window.location.pathname !== '/bo/dashboard/snowmonkey' && (
                            <div className="screen">
                              <p>Snow monkey is running...</p>
                            </div>
                          )}
                      </span>
                    )}
                  </div>
                </div>
              </div>
            </div>
            <div className="col-sm-10 offset-2 main">
              <div className="row">
                <div className={classes.join(' ')}>
                  <DynamicTitle />
                  <div className="row" style={{ marginTop: 1 }}>
                    {!this.state.catchedError && (
                      <Switch>
                        <Route
                          exact
                          path="/"
                          component={(props) =>
                            this.decorate(HomePage, { ...props, env: this.state.env, usedNewEngine: this.state.usedNewEngine })
                          }
                        />
                        <Route
                          path="/lines/:lineId/services/:serviceId/stats"
                          component={(props) => this.decorate(ServiceLiveStatsPage, props)}
                        />
                        <Route
                          path="/lines/:lineId/services/:serviceId/events"
                          component={(props) => this.decorate(ServiceEventsPage, props)}
                        />
                        <Route
                          path="/lines/:lineId/services/:serviceId/analytics"
                          component={(props) => this.decorate(ServiceAnalyticsPage, props)}
                        />
                        <Route
                          path="/lines/:lineId/services/:serviceId/health"
                          component={(props) => this.decorate(ServiceHealthPage, props)}
                        />
                        <Route
                          path="/lines/:lineId/services/:serviceId/doc"
                          component={(props) => this.decorate(DocumentationPage, props)}
                        />
                        <Route
                          path="/lines/:lineId/services/:serviceId/apikeys/:taction/:titem/stats"
                          component={(props) =>
                            this.decorate(ApiKeyStatsPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/lines/:lineId/services/:serviceId/apikeys/:taction/:titem"
                          component={(props) =>
                            this.decorate(ServiceApiKeysPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/lines/:lineId/services/:serviceId/apikeys/:taction"
                          component={(props) =>
                            this.decorate(ServiceApiKeysPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/lines/:lineId/services/:serviceId/apikeys"
                          component={(props) =>
                            this.decorate(ServiceApiKeysPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path={['/routes', '/route-compositions']}
                          component={(props) => (
                            <RouteDesignerPage
                              globalEnv={this.state.env}
                              setTitle={(t) => DynamicTitle.setContent(t)}
                              getTitle={() => DynamicTitle.getContent()}
                              setSidebarContent={(c) => DynamicSidebar.setContent(c)}
                              {...props}
                            />
                          )}
                        />
                        <Route
                          path="/ngforms"
                          component={(props) => (
                            <NgFormPlayground
                              globalEnv={this.state.env}
                              setTitle={(t) => DynamicTitle.setContent(t)}
                              getTitle={() => DynamicTitle.getContent()}
                              {...props}
                            />
                          )}
                        />
                        <Route
                          path="/metrics"
                          component={(props) => (
                            <MetricsPage
                              globalEnv={this.state.env}
                              setTitle={(t) => DynamicTitle.setContent(t)}
                              getTitle={() => DynamicTitle.getContent()}
                              {...props}
                            />
                          )}
                        />
                        <Route
                          path="/apikeys/:taction/:titem"
                          component={(props) =>
                            this.decorate(ApiKeysPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/apikeys/:taction"
                          component={(props) =>
                            this.decorate(ApiKeysPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/apikeys"
                          component={(props) =>
                            this.decorate(ApiKeysPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/backends/:taction/:titem"
                          component={(props) =>
                            this.decorate(BackendsPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/backends/:taction"
                          component={(props) =>
                            this.decorate(BackendsPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/backends"
                          component={(props) =>
                            this.decorate(BackendsPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/organizations/:taction/:titem"
                          component={(props) =>
                            this.decorate(TenantsPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/organizations/:taction"
                          component={(props) =>
                            this.decorate(TenantsPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/organizations"
                          component={(props) =>
                            this.decorate(TenantsPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/teams/:taction/:titem"
                          component={(props) =>
                            this.decorate(TeamsPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/teams/:taction"
                          component={(props) =>
                            this.decorate(TeamsPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/teams"
                          component={(props) =>
                            this.decorate(TeamsPage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/lines/:lineId/services/:serviceId"
                          component={(props) =>
                            this.decorate(ServicePage, { ...props, env: this.state.env })
                          }
                        />
                        <Route
                          path="/services/:taction/:titem"
                          component={(props) => this.decorate(ServicesPage, props)}
                        />
                        <Route
                          path="/services/:taction"
                          component={(props) => this.decorate(ServicesPage, props)}
                        />
                        <Route
                          path="/services"
                          component={(props) => this.decorate(ServicesPage, props)}
                        />
                        <Route
                          path="/tcp/services/:taction/:titem"
                          component={(props) => this.decorate(TcpServicesPage, props)}
                        />
                        <Route
                          path="/tcp/services/:taction"
                          component={(props) => this.decorate(TcpServicesPage, props)}
                        />
                        <Route
                          path="/tcp/services"
                          component={(props) => this.decorate(TcpServicesPage, props)}
                        />

                        <Route
                          path="/groups/:taction/:titem/stats"
                          component={(props) => this.decorate(GroupStatsPage, props)}
                        />
                        <Route
                          path="/groups/:taction/:titem"
                          component={(props) => this.decorate(GroupsPage, props)}
                        />
                        <Route
                          path="/groups/:taction"
                          component={(props) => this.decorate(GroupsPage, props)}
                        />
                        <Route
                          path="/groups"
                          component={(props) => this.decorate(GroupsPage, props)}
                        />

                        <Route
                          path="/certificates/:taction/:titem"
                          component={(props) => this.decorate(CertificatesPage, props)}
                        />
                        <Route
                          path="/certificates/:taction"
                          component={(props) => this.decorate(CertificatesPage, props)}
                        />
                        <Route
                          path="/certificates"
                          component={(props) => this.decorate(CertificatesPage, props)}
                        />

                        <Route
                          path="/cluster"
                          component={(props) => this.decorate(ClusterPage, props)}
                        />
                        <Route
                          path="/tunnels/:id"
                          component={(props) => this.decorate(TunnelPage, props)}
                        />
                        <Route
                          path="/tunnels"
                          component={(props) => this.decorate(TunnelsPage, props)}
                        />

                        <Route
                          path="/exporters/:taction/:titem"
                          component={(props) => this.decorate(DataExportersPage, props)}
                        />
                        <Route
                          path="/exporters/:taction"
                          component={(props) => this.decorate(DataExportersPage, props)}
                        />
                        <Route
                          path="/exporters"
                          component={(props) => this.decorate(DataExportersPage, props)}
                        />
                        <Route
                          path="/dangerzone"
                          component={(props) => this.decorate(DangerZonePage, props)}
                        />
                        <Route
                          path="/sessions/admin"
                          component={(props) => this.decorate(SessionsPage, props)}
                        />
                        <Route
                          path="/sessions/private"
                          component={(props) => this.decorate(PrivateAppsSessionsPage, props)}
                        />
                        <Route
                          path="/clever"
                          component={(props) => this.decorate(CleverPage, props)}
                        />
                        <Route
                          path="/eureka-servers/edit/:eurekaServerId"
                          component={(props) => this.decorate(EurekaServerPage, props)}
                        />
                        <Route
                          path="/eureka-servers"
                          component={(props) => this.decorate(EurekaServersPage, props)}
                        />
                        <Route
                          path="/audit"
                          component={(props) => this.decorate(AuditPage, props)}
                        />
                        <Route
                          path="/alerts"
                          component={(props) => this.decorate(AlertPage, props)}
                        />
                        <Route
                          path="/loggers"
                          component={(props) => this.decorate(LoggersPage, props)}
                        />
                        <Route
                          path="/top10"
                          component={(props) => this.decorate(Top10servicesPage, props)}
                        />
                        <Route
                          path="/map"
                          component={(props) => this.decorate(ServicesMapPage, props)}
                        />
                        <Route
                          path="/stats"
                          component={(props) => this.decorate(GlobalAnalyticsPage, props)}
                        />
                        <Route
                          path="/status"
                          component={(props) => this.decorate(GlobalStatusPage, props)}
                        />
                        <Route
                          path="/events"
                          component={(props) => this.decorate(GlobalEventsPage, props)}
                        />
                        <Route
                          path="/snowmonkey"
                          component={(props) => this.decorate(SnowMonkeyPage, props)}
                        />
                        <Route
                          path="/jwt-verifiers/:taction/:titem"
                          component={(props) => this.decorate(JwtVerifiersPage, props)}
                        />
                        <Route
                          path="/jwt-verifiers/:taction"
                          component={(props) => this.decorate(JwtVerifiersPage, props)}
                        />
                        <Route
                          path="/jwt-verifiers"
                          component={(props) => this.decorate(JwtVerifiersPage, props)}
                        />
                        <Route
                          path="/resources-loader"
                          component={(props) => this.decorate(ResourceLoaderPage, props)}
                        />
                        <Route
                          path="/validation-authorities/:taction/:titem"
                          component={(props) => this.decorate(ClientValidatorsPage, props)}
                        />
                        <Route
                          path="/validation-authorities/:taction"
                          component={(props) => this.decorate(ClientValidatorsPage, props)}
                        />
                        <Route
                          path="/validation-authorities"
                          component={(props) => this.decorate(ClientValidatorsPage, props)}
                        />
                        <Route
                          path="/auth-configs/:taction/:titem"
                          component={(props) => this.decorate(AuthModuleConfigsPage, props)}
                        />
                        <Route
                          path="/auth-configs/:taction"
                          component={(props) => this.decorate(AuthModuleConfigsPage, props)}
                        />
                        <Route
                          path="/auth-configs"
                          component={(props) => this.decorate(AuthModuleConfigsPage, props)}
                        />
                        <Route
                          path="/plugins/:taction/:titem"
                          component={(props) => this.decorate(ScriptsPage, props)}
                        />
                        <Route
                          path="/plugins/:taction"
                          component={(props) => this.decorate(ScriptsPage, props)}
                        />
                        <Route
                          path="/plugins"
                          component={(props) => this.decorate(ScriptsPage, props)}
                        />
                        <Route
                          path="/provider"
                          component={(props) => this.decorate(ProvidersDashboardPage, props)}
                        />
                        <Route
                          path="/admins"
                          component={(props) =>
                            this.decorate(U2FRegisterPage, {
                              ...props,
                              env: this.state.env,
                            })
                          }
                        />
                        <Route component={(props) => this.decorate(NotFoundPage, props)} />
                      </Switch>
                    )}
                    {this.state.catchedError && (
                      <div
                        style={{
                          display: 'flex',
                          flexDirection: 'column',
                          justifyContent: 'center',
                          alignItems: 'center',
                          width: '100%',
                          height: '70vh',
                        }}>
                        <img
                          src={this.state.env ? this.state.env.otoroshiLogo : ''}
                          className="logoOtoroshi"
                        />
                        <div style={{ fontSize: 20, marginBottom: 20, marginTop: 20 }}>
                          Ooops, an error occured
                        </div>
                        <p style={{ width: '50%' }}>{this.state.catchedError.message}</p>
                        <div style={{ marginTop: 20 }}>
                          <button
                            type="button"
                            className="btn btn-success"
                            onClick={(e) => {
                              this.setState(
                                {
                                  catchedError: undefined,
                                },
                                window.history.back
                              );
                            }}>
                            <i className="fas fa-arrow-left" /> back
                          </button>
                          <button
                            type="button"
                            className="btn btn-danger ms-2"
                            onClick={(e) => window.location.reload()}>
                            <i className="fas fa-redo" /> reload
                          </button>
                        </div>
                      </div>
                    )}
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
        <Toasts />
      </Loader>
    );
  }
}

const BackOfficeAppContainerWithRouter = withRouter(BackOfficeAppContainer);

export class BackOfficeApp extends Component {
  render() {
    return (
      <Router basename="/bo/dashboard">
        <BackOfficeAppContainerWithRouter />
      </Router>
    );
  }
}

class GlobalTenantSelector extends Component {
  state = { tenants: ['default'], loading: false };

  componentDidMount() {
    this.forceUpdate();
    this.update();
  }

  update = () => {
    this.setState({ loading: true });
    BackOfficeServices.findAllTenants().then((rtenants) => {
      if (rtenants.length === 1) {
        window.localStorage.setItem('Otoroshi-Tenant', rtenants[0].id);
      }
      const tenants = rtenants.map((t) => {
        return {
          label: t.name,
          value: t.id,
        };
      });
      this.setState({ tenants, loading: false });
    });
  };

  onChange = (e) => {
    window.localStorage.setItem('Otoroshi-Tenant', e.value);
    setTimeout(() => window.location.reload(), 300);
  };

  render() {
    if (
      window.__otoroshi__env__latest.bypassUserRightsCheck ||
      window.__otoroshi__env__latest.userAdmin
    ) {
      return null;
    }
    if (this.state.tenants.length < 2) {
      return null;
    }
    return (
      <div className="global-tenant-selector">
        <Select
          style={{ width: '100%' }}
          isLoading={this.state.loading}
          value={window.localStorage.getItem('Otoroshi-Tenant') || 'default'}
          options={this.state.tenants}
          onChange={this.onChange}
          menuPlacement="auto"
          menuPosition="fixed"
          isClearable={false}
        />
      </div>
    );
  }
}
