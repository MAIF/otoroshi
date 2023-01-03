import React, { useState } from 'react';
import { Route, Switch, useLocation, withRouter } from 'react-router-dom';
import { nextClient } from '../../services/BackOfficeServices';
import Designer from './Designer';
import RouteCompositions from './RouteComposition';
import { RoutesTable } from './RoutesTable';
import { Informations } from './Informations';
import DesignerSidebar from './Sidebar';

import { ServiceEventsPage } from '../ServiceEventsPage';
import { ServiceLiveStatsPage } from '../ServiceLiveStatsPage';
import { ServiceHealthPage } from '../ServiceHealthPage';
import { ServiceAnalyticsPage } from '../ServiceAnalyticsPage';
import { ServiceApiKeysPage } from '../ServiceApiKeysPage';
import { RouteWizard } from './RouteWizard';
import { ImportServiceDescriptor } from './ImportServiceDescriptor';
import { entityFromURI } from '../../util';
import { v4 } from 'uuid';
import { HelpWrapper } from '../../components/inputs';
import { SquareButton } from '../../components/SquareButton';
import { YAMLExportButton } from '../../components/exporters/YAMLButton';
import { JsonExportButton } from '../../components/exporters/JSONButton';
import { Dropdown } from '../../components/Dropdown';
import PageTitle from '../../components/PageTitle';
import Loader from '../../components/Loader';

function BackToButton({ history }) {
  return (
    <SquareButton
      onClick={() => {
        const what = window.location.pathname.split('/')[3];
        history.push('/' + what);
      }}
      icon="fa-arrow-left"
      text={`Back to ${window.location.pathname.split('/')[3]}`}
    />
  );
}

function DeleteRouteButton(props) {
  const what = window.location.pathname.split('/')[3];
  const id = window.location.pathname.split('/')[4];
  const kind = what === 'routes' ? nextClient.ENTITIES.ROUTES : nextClient.ENTITIES.SERVICES;
  return (
    <SquareButton
      disabled={id === props.globalEnv.adminApiId}
      level="danger"
      onClick={() => {
        window.newConfirm('are you sure you want to delete this entity ?').then((ok) => {
          if (ok) {
            nextClient.deleteById(kind, id).then(() => {
              // window.location = '/bo/dashboard/' + what;
              props.history.push('/' + what);
            });
          }
        });
      }}
      icon="fa-trash"
      text="Delete"
    />
  );
}

function DuplicateButton({ value, history }) {
  return (
    <SquareButton
      onClick={(e) => {
        const what = window.location.pathname.split('/')[3];
        const id = window.location.pathname.split('/')[4];
        const prefix = (id.split('_')[0] || what) + '_';
        const newId = `${prefix}${v4()}`;
        const kind = what === 'routes' ? nextClient.ENTITIES.ROUTES : nextClient.ENTITIES.SERVICES;
        window.newConfirm('are you sure you want to duplicate this entity ?').then((ok) => {
          if (ok) {
            nextClient
              .create(kind, {
                ...value,
                name: value.name + ' (duplicated)',
                id: newId,
                enabled: false,
              })
              .then(() => {
                // window.location = '/bo/dashboard/' + what + '/' + newId + '?tab=informations';
                history.push('/' + what + '/' + newId + '?tab=informations');
              });
          }
        });
      }}
      icon="fa-copy"
      text="Duplicate"
    />
  );
}

function BackToRouteTab({ history, routeId, viewPlugins }) {
  return (
    <div className="ms-2" style={{ height: '100%' }}>
      <button
        type="button"
        className="btn btn-sm toggle-form-buttons d-flex align-items-center"
        onClick={() => history.replace(`${routeId}?tab=routes&view_plugins=${viewPlugins}`)}
        style={{
          backgroundColor: '#494948',
          color: '#fff',
          height: '100%',
        }}>
        <i className="fas fa-arrow-left me-2" style={{ fontSize: '1.33333em' }} />
        Back to route
      </button>
    </div>
  );
}

function InformationsTab({ isActive, entity, value, history }) {
  const isWhiteMode = document.body.classList.contains('white-mode');

  return (
    <div className="ms-2" style={{ height: '100%' }}>
      <button
        type="button"
        className="btn btn-sm toggle-form-buttons d-flex align-items-center"
        onClick={() => {
          const to = `/${entity.link}/${value.id}?tab=informations`;
          if (!window.location.href.includes(to))
            history.replace({
              pathname: to,
              state: {
                value,
              },
            });
        }}
        style={{
          backgroundColor: isActive ? '#f9b000' : isWhiteMode ? '#fff' : '#494948',
          height: '100%',
          color: isWhiteMode && !isActive ? '#000' : '#fff',
        }}>
        <i className="fas fa-file-alt me-2" style={{ fontSize: '1.33333em' }} />
        Informations
      </button>
    </div>
  );
}

function RoutesTab({ isActive, entity, value, history }) {
  return (
    <div className="ms-2" style={{ height: '100%' }}>
      <button
        type="button"
        className="btn btn-sm toggle-form-buttons d-flex align-items-center"
        onClick={() => {
          const to = `/${entity.link}/${value.id}?tab=routes`;
          if (!window.location.href.includes(to))
            history.replace({
              pathname: to,
              state: {
                value,
              },
            });
        }}
        style={{
          backgroundColor: isActive ? '#f9b000' : '#494948',
          color: '#fff',
          height: '100%',
        }}>
        <i className="fas fa-road me-2" style={{ fontSize: '1.33333em' }} />
        Routes
      </button>
    </div>
  );
}

function DesignerTab({ isActive, entity, value, history }) {
  const isWhiteMode = document.body.classList.contains('white-mode');

  return (
    <div className="ms-2" style={{ height: '100%' }}>
      <button
        type="button"
        className="btn btn-sm toggle-form-buttons d-flex align-items-center"
        onClick={() => {
          const to = `/${entity.link}/${value.id}?tab=flow`;
          if (!window.location.href.includes(to))
            history.replace({
              pathname: to,
              state: {
                value,
              },
            });
        }}
        style={{
          backgroundColor: isActive ? '#f9b000' : isWhiteMode ? '#fff' : '#494948',
          height: '100%',
          color: isWhiteMode && !isActive ? '#000' : '#fff',
        }}>
        <i className="fas fa-pencil-ruler me-2" style={{ fontSize: '1.33333em' }} />
        Designer
      </button>
    </div>
  );
}

function TesterButton({
  disabled,
  setForceTester,
  viewRef,
  history,
  value,
  isOnViewPlugins,
  forceHideTester,
  query,
}) {
  const disabledHelp =
    'Your route is disabled. Navigate to the informations page to turn it on again';
  const hidden = !(isOnViewPlugins || forceHideTester !== true) || query === 'routes';

  return (
    <HelpWrapper text={disabled ? disabledHelp : undefined} dataPlacement="bottom">
      <div
        className="ms-2 pb-2"
        style={{
          height: '100%',
          opacity: hidden ? 0 : 1,
          pointerEvents: hidden ? 'none' : 'auto',
        }}>
        <button
          type="button"
          className="btn btn-sm btn-dark d-flex align-items-center dark-background"
          onClick={() => {
            setForceTester(true);
            viewRef?.current?.onTestingButtonClick(history, value);
          }}
          style={{
            marginLeft: 20,
            height: '100%',
            borderRadius: '.2rem !important',
          }}>
          <i className="fas fa-vials" style={{ fontSize: '1.33333em' }} />
          Tester
        </button>
      </div>
    </HelpWrapper>
  );
}

function MoreActionsButton({ value, menu, history, globalEnv }) {
  return (
    <Dropdown className="ms-2" style={{ height: '100%' }}>
      <DuplicateButton value={value} history={history} />
      <JsonExportButton value={value} />
      <YAMLExportButton value={value} />
      <DeleteRouteButton globalEnv={globalEnv} history={history} />
      {menu}
      {/* <BackToButton history={history} /> */}
    </Dropdown>
  );
}

function ManagerTitle({
  query,
  isCreation,
  isOnViewPlugins,
  entity,
  menu,
  pathname,
  value,
  viewPlugins,
  viewRef,
  location,
  history,
  saveButton,
  url,
  setForceTester,
  forceHideTester,
  routeId,
  globalEnv,
}) {
  const commonsProps = {
    entity,
    value,
    history,
  };

  const tabs = [
    {
      visible: () => isOnViewPlugins,
      component: () => (
        <BackToRouteTab history={history} routeId={routeId} viewPlugins={viewPlugins} />
      ),
    },
    {
      visible: () => !isOnViewPlugins,
      tab: 'informations',
      component: () => <InformationsTab isActive={query === 'informations'} {...commonsProps} />,
    },
    {
      visible: () => ['route-compositions'].includes(entity.link),
      component: () => <RoutesTab isActive={query === 'routes'} {...commonsProps} />,
    },
    {
      visible: () => !isOnViewPlugins,
      component: () => <DesignerTab isActive={query === 'flow'} {...commonsProps} />,
    },
    {
      component: () => (
        <TesterButton
          disabled={!value.enabled}
          setForceTester={setForceTester}
          viewRef={viewRef}
          history={history}
          value={value}
          isOnViewPlugins={isOnViewPlugins}
          forceHideTester={forceHideTester}
          query={query}
        />
      ),
    },
    {
      visible: () => !isOnViewPlugins,
      component: () => (
        <MoreActionsButton value={value} menu={menu} history={history} globalEnv={globalEnv} />
      ),
    },
  ];

  return (
    <PageTitle
      style={{
        paddingBottom: pathname === '/routes' ? 'initial' : 0,
      }}
      title={
        {
          flow: 'Designer',
          informations: 'Informations',
          routes: 'Routes',
          route_plugins: 'Route plugins',
        }[query]
      }>
      {!isCreation &&
        tabs
          .filter((tab) => !tab.visible || tab.visible())
          .filter((tab) => (location.state?.routeFromService ? tab.tab === 'Informations' : true))
          .map(({ component }, i) => {
            const Tab = component;
            return <Tab key={`tab-${i}`} />;
          })}
      {saveButton}
    </PageTitle>
  );
}

class Manager extends React.Component {
  state = {
    value: this.props.location?.state?.value,
    menu: undefined,
    menuRefreshed: undefined,
    saveButton: undefined,
    saveTypeButton: undefined,
    forceHideTester: false,
    loading: !this.props.location?.state?.value,
  };

  viewRef = React.createRef(null);

  componentDidMount() {
    if (!this.props.location?.state?.value) {
      this.loadRoute('componentDidMount');
    } else {
      this.updateSidebar();
    }

    window.history.replaceState({}, document.title);
  }

  componentDidUpdate(prevProps, prevState) {
    if (this.props.match.params.routeId !== prevProps.match.params.routeId)
      this.loadRoute('componentDidUpdate');

    if (
      ['saveTypeButton', 'menuRefreshed', 'forceHideTester'].some(
        (field) => this.state[field] !== prevState[field]
      )
    ) {
      this.setTitle();
    }
  }

  componentWillUnmount() {
    this.props.setSidebarContent(null);
  }

  setTitle = () => {
    if (!this.state.value) return;

    const { entity, history, location } = this.props;

    let query = new URLSearchParams(location.search).get('tab');

    if (!query && location.pathname.includes('?')) {
      query = new URLSearchParams(`?${location.pathname.split('?')[1]}`).get('tab');
    }

    const p = this.props.match.params;
    const isCreation = p.routeId === 'new';

    const rawViewPlugins = new URLSearchParams(location.search).get('view_plugins');
    const viewPlugins = rawViewPlugins !== null ? Number(rawViewPlugins) : -1;
    const isOnViewPlugins = (viewPlugins !== -1) & (query === 'route_plugins');
    const url = p.url;

    this.props.setTitle(() => (
      <ManagerTitle
        forceHideTester={this.state.forceHideTester}
        setForceTester={(va) => this.setState({ forceHideTester: va })}
        pathname={location.pathname}
        menu={this.state.menu}
        routeId={p.routeId}
        url={url}
        query={query}
        isCreation={isCreation}
        isOnViewPlugins={isOnViewPlugins}
        entity={entity}
        value={this.state.value}
        viewPlugins={viewPlugins}
        viewRef={this.viewRef}
        location={location}
        history={history}
        saveButton={this.state.saveButton}
        globalEnv={this.props.globalEnv}
      />
    ));
  };

  loadRoute = (from) => {
    const { routeId } = this.props.match.params || { routeId: undefined };

    if (routeId === 'new') {
      nextClient.template(nextClient.ENTITIES[this.props.entity.fetchName]).then((value) => {
        this.setState({ value, loading: false }, this.updateSidebar);
      });
    } else {
      if (this.props.location.state && this.props.location.state.routeFromService) {
        this.setState(
          { value: this.props.location.state.routeFromService, loading: false },
          this.updateSidebar
        );
      } else {
        nextClient.fetch(nextClient.ENTITIES[this.props.entity.fetchName], routeId).then((res) => {
          if (!res.error) {
            this.setState({ value: res, loading: false }, this.updateSidebar);
          }
        });
      }
    }
  };

  updateSidebar = () => {
    if (
      location.pathname.endsWith('/routes') ||
      location.pathname.endsWith('/route-compositions')
    ) {
      this.props.setSidebarContent(null);
    } else {
      this.props.setSidebarContent(
        <DesignerSidebar
          route={this.state.value}
          setSidebarContent={this.props.setSidebarContent}
        />
      );
    }

    this.setTitle();
  };

  render() {
    const { entity, history, location, ...props } = this.props;

    let query = new URLSearchParams(location.search).get('tab');

    if (!query && location.pathname.includes('?')) {
      query = new URLSearchParams(`?${location.pathname.split('?')[1]}`).get('tab');
    }

    const p = this.props.match.params;
    const isCreation = p.routeId === 'new';

    const rawViewPlugins = new URLSearchParams(location.search).get('view_plugins');
    const viewPlugins = rawViewPlugins !== null ? Number(rawViewPlugins) : -1;

    const { value, loading } = this.state;
    const divs = [
      {
        predicate: query && ['flow', 'route_plugins'].includes(query) && !isCreation,
        render: () => (
          <Designer
            {...this.props}
            toggleTesterButton={(va) => this.setState({ forceHideTester: va })}
            ref={this.viewRef}
            tab={query}
            history={history}
            value={this.state.value}
            setValue={(v) => this.setState({ value: v }, this.setTitle)}
            setSaveButton={(n) => this.setState({ saveButton: n, saveTypeButton: 'routes' })}
            viewPlugins={viewPlugins}
            setMenu={(n) => this.setState({ menu: n, menuRefreshed: Date.now() })}
          />
        ),
      },
      {
        predicate: query && query === 'routes',
        render: () =>
          value && (
            <RouteCompositions
              ref={this.viewRef}
              service={this.state.value}
              setSaveButton={(n) =>
                this.setState({ saveButton: n, saveTypeButton: 'route-compositions' })
              }
              setRoutes={(routes) =>
                this.setState(
                  {
                    value: {
                      ...this.state.value,
                      routes,
                    },
                  },
                  this.setTitle
                )
              }
              viewPlugins={viewPlugins}
            />
          ),
      },
    ];

    const component = divs.filter((p) => p.predicate);

    if (component.length > 0) {
      return (
        <Loader loading={loading}>
          <div className="designer row">{component[0].render()}</div>
        </Loader>
      );
    }

    return (
      <Loader loading={loading}>
        <div className="designer row ps-3">
          <Informations
            {...this.props}
            routeId={p.routeId}
            ref={this.viewRef}
            isCreation={isCreation}
            value={value}
            setValue={(v) => this.setState({ value: v })}
            setSaveButton={(n) =>
              this.setState({ saveButton: n, saveTypeButton: 'informations' }, this.setTitle)
            }
          />
        </div>
      </Loader>
    );
  }
}

const RoutesView = ({ history, globalEnv }) => {
  const [creation, setCreation] = useState(false);
  const [importServiceDescriptor, setImportServiceDescriptor] = useState(false);
  const { pathname } = useLocation();

  return (
    <>
      {creation && <RouteWizard hide={() => setCreation(false)} history={history} />}
      {importServiceDescriptor && (
        <ImportServiceDescriptor hide={() => setImportServiceDescriptor(false)} history={history} />
      )}
      <RoutesTable
        globalEnv={globalEnv}
        injectTopBar={
          pathname.includes('route-compositions') ? null : (
            <>
              <button
                onClick={() => setCreation(true)}
                className="btn btn-primary"
                style={{ _backgroundColor: '#f9b000', _borderColor: '#f9b000', marginLeft: 5 }}>
                <i className="fas fa-hat-wizard" /> Create with wizard
              </button>
              <button
                onClick={() => setImportServiceDescriptor(true)}
                className="btn btn-primary"
                style={{ _backgroundColor: '#f9b000', _borderColor: '#f9b000', marginLeft: 5 }}>
                <i className="fas fas fa-exchange-alt" /> Convert a service descriptor
              </button>
            </>
          )
        }
      />
    </>
  );
};

class RouteDesigner extends React.Component {
  componentDidMount() {
    this.patchStyle(true);
    this.props.setTitle('Routes');
  }

  componentWillUnmount() {
    this.patchStyle(false);
  }

  patchStyle = (applyPatch) => {
    if (applyPatch) {
      document.getElementsByClassName('main')[0].classList.add('patch-main');
      [...document.getElementsByClassName('row')].map((r) => r.classList.add('patch-row'));
    } else {
      document.getElementsByClassName('main')[0].classList.remove('patch-main');
      [...document.getElementsByClassName('row')].map((r) =>
        r.classList.remove('patch-row')
      );
    }
  };

  render() {
    const { match, history, location, globalEnv } = this.props;

    const entity = entityFromURI(location);

    return (
      <Switch>
        {[
          { path: `${match.url}/:routeId/health`, component: ServiceHealthPage },
          { path: `${match.url}/:routeId/analytics`, component: ServiceAnalyticsPage },
          { path: `${match.url}/:routeId/apikeys/:taction/:titem`, component: ServiceApiKeysPage },
          { path: `${match.url}/:routeId/apikeys`, component: ServiceApiKeysPage },
          { path: `${match.url}/:routeId/stats`, component: ServiceLiveStatsPage },
          { path: `${match.url}/:routeId/events`, component: ServiceEventsPage },
          {
            path: `${match.url}/:routeId`,
            component: (p) => (
              <Manager {...this.props} {...p} entity={entity} globalEnv={globalEnv} />
            ),
          },
        ].map(({ path, component }) => {
          const Component = component;
          return (
            <Route
              exact
              key={path}
              path={path}
              component={(p) => {
                return (
                  <Component
                    setSidebarContent={this.props.setSidebarContent}
                    setTitle={this.props.setTitle}
                    {...p}
                    {...p.match}
                    env={this.props.env}
                  />
                );
              }}
            />
          );
        })}
        <Route component={() => <RoutesView history={history} globalEnv={globalEnv} />} />
      </Switch>
    );
  }
}

export default withRouter(RouteDesigner);
