import React, { Component } from 'react';
import {
  BrowserRouter as Router,
  Route,
  Link,
  Switch,
  withRouter,
} from 'react-router-dom';
import queryString from 'query-string';

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
import { WasmPluginsPage } from '../pages/WasmPluginsPage';
import { ClientValidatorsPage } from '../pages/ClientValidatorsPage';
import { GroupStatsPage } from '../pages/GroupStatsPage';
import { ApiKeyStatsPage } from '../pages/ApiKeyStatsPage';
import { TcpServicesPage } from '../pages/TcpServicesPage';
import { ProvidersDashboardPage } from '../pages/ProvidersDashboardPage';
import { ResourceLoaderPage } from '../pages/ResourceLoaderPage';
import RouteDesignerPage from '../pages/RouteDesigner';
import { BackendsPage } from '../pages/BackendsPage';
import { MetricsPage } from '../pages/MetricsPage';
import { AtomicDesignPage } from '../pages/AtomicDesignPage';
import { FeaturesPage } from '../pages/FeaturesPage';
import { ErrorTemplatesPage } from '../pages/ErrorTemplatePage';

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
import { NgSelectRenderer } from '../components/nginputs';
import Loader from '../components/Loader';
import { globalConfig } from 'antd/lib/config-provider';

class AnonymousReportingEnabled extends Component {
  render() {
    return (
      <>
        <div className='modal-body'>
          <p style={{ textAlign: 'justify' }}>
            As you may know, Otoroshi is an open-source project. As such, we
            don't have much feedback from our users. But this feedback is
            essential for us to shape the future of Otoroshi.
          </p>
          <p style={{ textAlign: 'justify' }}>
            The best way to help is to enable{' '}
            <span style={{ fontWeight: 'bold' }}>anonymous reporting</span>.
            This feature allow Otoroshi to send us periodical reports.
          </p>
          <p style={{ textAlign: 'justify' }}>
            It won't send sensitive or personnal data, just a bunch of
            statistics about your usage of otoroshi (see{' '}
            <a href='https://maif.github.io/otoroshi/manual/topics/anonymous-reporting.html'>
              the documentation
            </a>
            ).
          </p>
          <p style={{ textAlign: 'justify' }}>
            At any moment, you can turn off anonymous reporting from the danger
            zone.
          </p>
          <p style={{ textAlign: 'justify' }}>
            Thanks for helping us building better products !
          </p>
        </div>
        <div className='modal-footer'>
          <button
            type='button'
            className='btn btn-danger'
            onClick={this.props.cancel}
          >
            No, thanks
          </button>
          <button
            type='button'
            className='btn btn-success'
            onClick={(e) => this.props.ok(true)}
          >
            Enable anonymous reporting
          </button>
        </div>
      </>
    );
  }
}

class AnonymousReportingEnable extends Component {
  render() {
    return (
      <>
        <div className='modal-body'>enable it in config file !</div>
        <div className='modal-footer'>
          <button
            type='button'
            className='btn btn-danger'
            onClick={this.props.cancel}
          >
            Cancel
          </button>
          <button
            type='button'
            className='btn btn-success'
            onClick={(e) => this.props.ok(true)}
          >
            Enable anonymous reporting
          </button>
        </div>
      </>
    );
  }
}

const sidebarOpenOnLoad =
  (window.localStorage.getItem('otoroshi-sidebar-open') || 'true') === 'true';

export const SidebarContext = React.createContext(sidebarOpenOnLoad);

class BackOfficeAppContainer extends Component {
  constructor(props) {
    super(props);
    this.state = {
      shortMenu: true,
      groups: [],
      lines: [],
      catchedError: null,
      env: null,
      loading: true,
      openedSidebar: sidebarOpenOnLoad,
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

  triggerAnonymousReportingPopup = (env) => {
    if (env.anonymousReporting.should_ask) {
      if (!env.anonymousReporting.should_enable) {
        window
          .popup(
            'Enable anonymous reporting',
            (ok, cancel) => (
              <AnonymousReportingEnabled ok={ok} cancel={cancel} />
            ),
            { style: { width: '100%' } }
          )
          .then((enable) => {
            if (enable) {
              BackOfficeServices.anonymousReporting({ enabled: true });
            } else {
              BackOfficeServices.anonymousReporting({ enabled: false });
            }
          });
      } else {
        // Do something here as the user disabled it on purpose in static config ???
      }
    }
  };

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
        usedNewEngine: env.newEngineEnabled,
      });
      this.triggerAnonymousReportingPopup(env);
    });
    this.readShortMenu();
  }

  componentDidCatch(e) {
    this.setState({ catchedError: e });
  }

  decorate = (Component, props) => {
    const newProps = { ...props };
    const query =
      queryString.parse((props.location || { search: '' }).search) || {};
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

  readShortMenu = () => {
    const shortMenuStr =
      window.localStorage.getItem('otoroshi-short-menu') || 'true';
    const shortMenu = shortMenuStr === 'true';
    this.setState({ shortMenu });
  };

  toggleShortMenu = () => {
    const newShortMenu = !this.state.shortMenu;
    window.localStorage.setItem('otoroshi-short-menu', String(newShortMenu));
    this.setState({ shortMenu: newShortMenu });
  };

  render() {
    const classes = ['page-container'];
    if (
      this.props.children &&
      this.props.children.type &&
      this.props.children.type.backOfficeClassName
    ) {
      classes.push(this.props.children.type.backOfficeClassName);
    }

    return (
      <Loader loading={this.state.loading}>
        <SidebarContext.Provider
          value={{
            openedSidebar: this.state.openedSidebar,
            toggleSibebar: (openedSidebar) => this.setState({ openedSidebar }),
          }}
        >
          <ReloadNewVersion />
          {this.state.env && (
            <>
              <UpdateOtoroshiVersion env={this.state.env} />
              <TopBar
                shortMenu={this.state.shortMenu}
                setTitle={(t) => DynamicTitle.setContent(t)}
                {...this.props}
                changePassword={this.state.env.changePassword}
                env={this.state.env}
              />
            </>
          )}
          {/* <div className='container-fluid'> */}
          <div style={{ height: 'calc(100vh - 52px)', overflow: 'hidden' }}>
            <div className='d-flex' style={{ position: 'relative' }}>
              <div
                className={`sidebar ${!this.state.openedSidebar ? 'sidebar--closed' : ''
                  }`}
                id='sidebar'
              >
                <i
                  className={`fas fa-chevron-${this.state.openedSidebar ? 'left' : 'right'
                    } sidebar-toggle`}
                  onClick={(e) => {
                    e.stopPropagation();
                    window.localStorage.setItem(
                      'otoroshi-sidebar-open',
                      String(!this.state.openedSidebar)
                    );
                    this.setState({
                      openedSidebar: !this.state.openedSidebar,
                    });
                  }}
                />
                <div
                  className={`sidebar-content ${this.state.openedSidebar ? 'ps-2' : ''
                    }`}
                  style={{
                    alignItems: this.state.openedSidebar
                      ? 'flex-start'
                      : 'center',
                  }}
                >
                  {this.state.env && (
                    <GlobalTenantSelector
                      env={this.state.env}
                      openedSidebar={this.state.openedSidebar}
                      toggleSidebar={(value) =>
                        this.setState({ openedSidebar: value })
                      }
                    />
                  )}
                  <ul className='nav flex-column nav-sidebar mt-3'>
                    <li
                      className={`nav-item mt-0 ${this.state.openedSidebar ? 'nav-item--open' : ''
                        }`}>
                      <Link
                        to='/'
                        className={`nav-link ${window.location.pathname === '/bo/dashboard/'
                            ? 'active'
                            : ''
                          }`}
                        {...createTooltip(
                          'Home dashboard of Otoroshi displaying global metrics'
                        )}
                        onClick={() => {
                          DynamicTitle.setContent(null);
                          DynamicSidebar.setContent(null);
                        }}
                      >
                        <i
                          className={`fab fa-fort-awesome ${this.state.openedSidebar ? 'me-3' : ''
                            }`}
                        />
                        {this.state.openedSidebar ? 'Dashboard' : ''}
                      </Link>
                    </li>
                  </ul>
                  <DynamicSidebar />
                  <DefaultSidebar
                    lines={this.state.lines}
                    addService={this.addService}
                    env={this.state.env}
                  />
                  <div className='bottom-sidebar'>
                    {/*<img src='/assets/images/otoroshi-logo-inverse.png' width='16' /> version {window.__currentVersion}*/}
                    {this.state.env && (
                      <span
                        onClick={(e) =>
                          (window.location = '/bo/dashboard/snowmonkey')
                        }
                      >
                        {this.state.env.snowMonkeyRunning &&
                          window.location.pathname !==
                          '/bo/dashboard/snowmonkey' && (
                            <div className='screen'>
                              <p>Snow monkey is running...</p>
                            </div>
                          )}
                      </span>
                    )}
                  </div>
                </div>
              </div>
              <div className="flex-fill px-3">
                <div className={classes.join(' ')} id="content-scroll-container">
                  <DynamicTitle />
                  {!this.state.catchedError && (
                    <Switch>
                      <Route
                        exact
                        path='/'
                        component={(props) =>
                          this.decorate(HomePage, {
                            ...props,
                            env: this.state.env,
                            usedNewEngine: this.state.usedNewEngine,
                          })
                        }
                      />
                      {Otoroshi.extensions()
                        .flatMap((ext) => ext.routes)
                        .map((item) => {
                          return (
                            <Route
                              path={item.path}
                              component={(props) =>
                                this.decorate(item.component, props)
                              }
                            />
                          );
                        })}
                      <Route
                        path='/lines/:lineId/services/:serviceId/stats'
                        component={(props) =>
                          this.decorate(ServiceLiveStatsPage, props)
                        }
                      />
                      <Route
                        path='/lines/:lineId/services/:serviceId/events'
                        component={(props) =>
                          this.decorate(ServiceEventsPage, props)
                        }
                      />
                      <Route
                        path='/lines/:lineId/services/:serviceId/analytics'
                        component={(props) =>
                          this.decorate(ServiceAnalyticsPage, props)
                        }
                      />
                      <Route
                        path='/lines/:lineId/services/:serviceId/health'
                        component={(props) =>
                          this.decorate(ServiceHealthPage, props)
                        }
                      />
                      <Route
                        path='/lines/:lineId/services/:serviceId/doc'
                        component={(props) =>
                          this.decorate(DocumentationPage, props)
                        }
                      />
                      <Route
                        path='/lines/:lineId/services/:serviceId/apikeys/:taction/:titem/stats'
                        component={(props) =>
                          this.decorate(ApiKeyStatsPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/lines/:lineId/services/:serviceId/apikeys/:taction/:titem'
                        component={(props) =>
                          this.decorate(ServiceApiKeysPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/lines/:lineId/services/:serviceId/apikeys/:taction'
                        component={(props) =>
                          this.decorate(ServiceApiKeysPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/lines/:lineId/services/:serviceId/apikeys'
                        component={(props) =>
                          this.decorate(ServiceApiKeysPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path={['/routes', '/route-compositions']}
                        component={(props) => (
                          <RouteDesignerPage
                            globalEnv={this.state.env}
                            setTitle={(t) => DynamicTitle.setContent(t)}
                            getTitle={() => DynamicTitle.getContent()}
                            setSidebarContent={(c) =>
                              DynamicSidebar.setContent(c)
                            }
                            {...props}
                          />
                        )}
                      />
                      <Route
                        path='/ngforms'
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
                        path='/metrics'
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
                        path='/apikeys/:taction/:titem'
                        component={(props) =>
                          this.decorate(ApiKeysPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/apikeys/:taction'
                        component={(props) =>
                          this.decorate(ApiKeysPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/apikeys'
                        component={(props) =>
                          this.decorate(ApiKeysPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/backends/:taction/:titem'
                        component={(props) =>
                          this.decorate(BackendsPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/backends/:taction'
                        component={(props) =>
                          this.decorate(BackendsPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/backends'
                        component={(props) =>
                          this.decorate(BackendsPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/organizations/:taction/:titem'
                        component={(props) =>
                          this.decorate(TenantsPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/organizations/:taction'
                        component={(props) =>
                          this.decorate(TenantsPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/organizations'
                        component={(props) =>
                          this.decorate(TenantsPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/teams/:taction/:titem'
                        component={(props) =>
                          this.decorate(TeamsPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/teams/:taction'
                        component={(props) =>
                          this.decorate(TeamsPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/teams'
                        component={(props) =>
                          this.decorate(TeamsPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/error-templates/:taction/:titem'
                        component={(props) =>
                          this.decorate(ErrorTemplatesPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/error-templates/:taction'
                        component={(props) =>
                          this.decorate(ErrorTemplatesPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/error-templates'
                        component={(props) =>
                          this.decorate(ErrorTemplatesPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/lines/:lineId/services/:serviceId'
                        component={(props) =>
                          this.decorate(ServicePage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        path='/services/:taction/:titem'
                        component={(props) =>
                          this.decorate(ServicesPage, props)
                        }
                      />
                      <Route
                        path='/services/:taction'
                        component={(props) =>
                          this.decorate(ServicesPage, props)
                        }
                      />
                      <Route
                        path='/services'
                        component={(props) =>
                          this.decorate(ServicesPage, props)
                        }
                      />
                      <Route
                        path='/tcp/services/:taction/:titem'
                        component={(props) =>
                          this.decorate(TcpServicesPage, props)
                        }
                      />
                      <Route
                        path='/tcp/services/:taction'
                        component={(props) =>
                          this.decorate(TcpServicesPage, props)
                        }
                      />
                      <Route
                        path='/tcp/services'
                        component={(props) =>
                          this.decorate(TcpServicesPage, props)
                        }
                      />

                      <Route
                        path='/groups/:taction/:titem/stats'
                        component={(props) =>
                          this.decorate(GroupStatsPage, props)
                        }
                      />
                      <Route
                        path='/groups/:taction/:titem'
                        component={(props) => this.decorate(GroupsPage, props)}
                      />
                      <Route
                        path='/groups/:taction'
                        component={(props) => this.decorate(GroupsPage, props)}
                      />
                      <Route
                        path='/groups'
                        component={(props) => this.decorate(GroupsPage, props)}
                      />
                      <Route
                        path='/features'
                        component={(props) =>
                          this.decorate(FeaturesPage, {
                            ...props,
                            shortMenu: this.state.shortMenu,
                            toggleShortMenu: this.toggleShortMenu,
                          })
                        }
                      />

                      <Route
                        path='/certificates/:taction/:titem'
                        component={(props) =>
                          this.decorate(CertificatesPage, props)
                        }
                      />
                      <Route
                        path='/certificates/:taction'
                        component={(props) =>
                          this.decorate(CertificatesPage, props)
                        }
                      />
                      <Route
                        path='/certificates'
                        component={(props) =>
                          this.decorate(CertificatesPage, props)
                        }
                      />

                      <Route
                        path='/cluster'
                        component={(props) => this.decorate(ClusterPage, props)}
                      />
                      <Route
                        path='/tunnels/:id'
                        component={(props) => this.decorate(TunnelPage, props)}
                      />
                      <Route
                        path='/tunnels'
                        component={(props) => this.decorate(TunnelsPage, props)}
                      />

                      <Route
                        path='/exporters/:taction/:titem'
                        component={(props) =>
                          this.decorate(DataExportersPage, props)
                        }
                      />
                      <Route
                        path='/exporters/:taction'
                        component={(props) =>
                          this.decorate(DataExportersPage, props)
                        }
                      />
                      <Route
                        path='/exporters'
                        component={(props) =>
                          this.decorate(DataExportersPage, props)
                        }
                      />
                      <Route
                        path='/dangerzone'
                        component={(props) =>
                          this.decorate(DangerZonePage, props)
                        }
                      />
                      <Route
                        path='/sessions/admin'
                        component={(props) =>
                          this.decorate(SessionsPage, props)
                        }
                      />
                      <Route
                        path='/sessions/private'
                        component={(props) =>
                          this.decorate(PrivateAppsSessionsPage, props)
                        }
                      />
                      <Route
                        path='/clever'
                        component={(props) => this.decorate(CleverPage, props)}
                      />
                      <Route
                        path='/eureka-servers/edit/:eurekaServerId'
                        component={(props) =>
                          this.decorate(EurekaServerPage, props)
                        }
                      />
                      <Route
                        path='/eureka-servers'
                        component={(props) =>
                          this.decorate(EurekaServersPage, props)
                        }
                      />
                      <Route
                        path='/audit'
                        component={(props) => this.decorate(AuditPage, props)}
                      />
                      <Route
                        path='/alerts'
                        component={(props) => this.decorate(AlertPage, props)}
                      />
                      <Route
                        path='/loggers'
                        component={(props) => this.decorate(LoggersPage, props)}
                      />
                      <Route
                        path='/top10'
                        component={(props) =>
                          this.decorate(Top10servicesPage, props)
                        }
                      />
                      <Route
                        path='/map'
                        component={(props) =>
                          this.decorate(ServicesMapPage, props)
                        }
                      />
                      <Route
                        path='/stats'
                        component={(props) =>
                          this.decorate(GlobalAnalyticsPage, props)
                        }
                      />
                      <Route
                        path='/status'
                        component={(props) =>
                          this.decorate(GlobalStatusPage, props)
                        }
                      />
                      <Route
                        path='/events'
                        component={(props) =>
                          this.decorate(GlobalEventsPage, props)
                        }
                      />
                      <Route
                        path='/snowmonkey'
                        component={(props) =>
                          this.decorate(SnowMonkeyPage, props)
                        }
                      />
                      <Route
                        path='/jwt-verifiers/:taction/:titem'
                        component={(props) =>
                          this.decorate(JwtVerifiersPage, props)
                        }
                      />
                      <Route
                        path='/jwt-verifiers/:taction'
                        component={(props) =>
                          this.decorate(JwtVerifiersPage, props)
                        }
                      />
                      <Route
                        path='/jwt-verifiers'
                        component={(props) =>
                          this.decorate(JwtVerifiersPage, props)
                        }
                      />
                      <Route
                        path='/resources-loader'
                        component={(props) =>
                          this.decorate(ResourceLoaderPage, props)
                        }
                      />
                      <Route
                        path='/validation-authorities/:taction/:titem'
                        component={(props) =>
                          this.decorate(ClientValidatorsPage, props)
                        }
                      />
                      <Route
                        path='/validation-authorities/:taction'
                        component={(props) =>
                          this.decorate(ClientValidatorsPage, props)
                        }
                      />
                      <Route
                        path='/validation-authorities'
                        component={(props) =>
                          this.decorate(ClientValidatorsPage, props)
                        }
                      />
                      <Route
                        path='/auth-configs/:taction/:titem'
                        component={(props) =>
                          this.decorate(AuthModuleConfigsPage, props)
                        }
                      />
                      <Route
                        path='/auth-configs/:taction'
                        component={(props) =>
                          this.decorate(AuthModuleConfigsPage, props)
                        }
                      />
                      <Route
                        path='/auth-configs'
                        component={(props) =>
                          this.decorate(AuthModuleConfigsPage, props)
                        }
                      />
                      <Route
                        path='/plugins/:taction/:titem'
                        component={(props) => this.decorate(ScriptsPage, props)}
                      />
                      <Route
                        path='/plugins/:taction'
                        component={(props) => this.decorate(ScriptsPage, props)}
                      />
                      <Route
                        path='/plugins'
                        component={(props) => this.decorate(ScriptsPage, props)}
                      />
                      <Route
                        path='/wasm-plugins/:taction/:titem'
                        component={(props) =>
                          this.decorate(WasmPluginsPage, props)
                        }
                      />
                      <Route
                        path='/wasm-plugins/:taction'
                        component={(props) =>
                          this.decorate(WasmPluginsPage, props)
                        }
                      />
                      <Route
                        path='/wasm-plugins'
                        component={(props) =>
                          this.decorate(WasmPluginsPage, props)
                        }
                      />
                      <Route
                        path='/design'
                        component={(props) =>
                          this.decorate(AtomicDesignPage, props)
                        }
                      />
                      <Route
                        path='/provider'
                        component={(props) =>
                          this.decorate(ProvidersDashboardPage, props)
                        }
                      />
                      <Route
                        path='/admins'
                        component={(props) =>
                          this.decorate(U2FRegisterPage, {
                            ...props,
                            env: this.state.env,
                          })
                        }
                      />
                      <Route
                        component={(props) =>
                          this.decorate(NotFoundPage, props)
                        }
                      />
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
                      }}
                    >
                      <img
                        src={this.state.env ? this.state.env.otoroshiLogo : ''}
                        className='logoOtoroshi'
                      />
                      <div
                        style={{
                          fontSize: 20,
                          marginBottom: 20,
                          marginTop: 20,
                        }}
                      >
                        Ooops, an error occured
                      </div>
                      <p style={{ width: '50%' }}>
                        {this.state.catchedError.message}
                      </p>
                      <div style={{ marginTop: 20 }}>
                        <button
                          type='button'
                          className='btn btn-success'
                          onClick={(e) => {
                            this.setState(
                              {
                                catchedError: undefined,
                              },
                              window.history.back
                            );
                          }}
                        >
                          <i className='fas fa-arrow-left' /> back
                        </button>
                        <button
                          type='button'
                          className='btn btn-danger ms-2'
                          onClick={(e) => window.location.reload()}
                        >
                          <i className='fas fa-redo' /> reload
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              </div>
            </div>
          </div>
          <Toasts />
        </SidebarContext.Provider>
      </Loader>
    );
  }
}

const BackOfficeAppContainerWithRouter = withRouter(BackOfficeAppContainer);

export class BackOfficeApp extends Component {
  render() {
    return (
      <Router basename='/bo/dashboard'>
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
    window.localStorage.setItem('Otoroshi-Tenant', e);

    setTimeout(() => window.location.reload(), 300);
  };

  handleClick = (e) => {
    e.preventDefault();
    if (!this.props.openedSidebar) {
      this.props.toggleSidebar(true);
    }
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
      <ul className='nav flex-column nav-sidebar mt-3'>
        <li
          className={`nav-item mt-0 ${this.props.openedSidebar ? 'nav-item--open' : ''
            }`}
        >
          <a
            className='nav-link'
            data-toggle='tooltip'
            data-placement='top'
            title='Change the tenant'
            href='/bo/dashboard/'
            onClick={(e) => this.handleClick(e)}
          >
            <i className='fas fa-building'></i>
            {this.props.openedSidebar && (
              <div className='global-tenant-selector'>
                <NgSelectRenderer
                  id='tenants'
                  ngOptions={{ spread: true }}
                  value={
                    window.localStorage.getItem('Otoroshi-Tenant') || 'default'
                  }
                  options={this.state.tenants}
                  onChange={this.onChange}
                  style={{ width: '200px' }}
                />
              </div>
            )}
          </a>
        </li>
      </ul>
    );
  }
}
