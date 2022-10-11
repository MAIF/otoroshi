import React, { useEffect, useRef, useState } from 'react';
import { Route, Switch, useHistory, useLocation, useParams, useRouteMatch, withRouter } from 'react-router-dom';
import { nextClient } from '../../services/BackOfficeServices';
import Designer from './Designer';
import RouteCompositions from './RouteComposition';
import Routes from './RoutesTable';
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

function GridButton({ onClick, text, icon, level = "info" }) {
  return <button
    type="button"
    className={`btn btn-sm btn-${level} d-flex align-items-center justify-content-center flex-column`}
    style={{
      minWidth: '80px',
      minHeight: '80px',
      maxWidth: '80px',
      maxHeight: '80px',

      flex: 1
    }}
    onClick={onClick}>
    <div>
      <i className={`fas ${icon}`} />
    </div>
    <div>
      {text}
    </div>
  </button>
}

function BackToButton({ history }) {
  return <GridButton
    onClick={() => {
      const what = window.location.pathname.split('/')[3];
      history.push('/' + what);
    }}
    icon="fa-arrow-left"
    text={`Back to ${window.location.pathname.split('/')[3]}`} />
}

function DeleteRouteButton() {
  return <GridButton
    level="danger"
    onClick={() => {
      const what = window.location.pathname.split('/')[3];
      const id = window.location.pathname.split('/')[4];
      const kind =
        what === 'routes'
          ? nextClient.ENTITIES.ROUTES
          : nextClient.ENTITIES.SERVICES;
      window
        .newConfirm('are you sure you want to delete this entity ?')
        .then((ok) => {
          if (ok) {
            nextClient.deleteById(kind, id).then(() => {
              // window.location = '/bo/dashboard/' + what;
              history.push('/' + what);
            });
          }
        });
    }}
    icon="fa-trash"
    text="Delete" />
}

function DuplicateButton({ value, history }) {
  return <GridButton
    onClick={(e) => {
      const what = window.location.pathname.split('/')[3];
      const id = window.location.pathname.split('/')[4];
      const prefix = (id.split('_')[0] || what) + '_';
      const newId = `${prefix}${v4()}`;
      const kind =
        what === 'routes'
          ? nextClient.ENTITIES.ROUTES
          : nextClient.ENTITIES.SERVICES;
      window
        .newConfirm('are you sure you want to duplicate this entity ?')
        .then((ok) => {
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
                history.push(
                  '/' + what + '/' + newId + '?tab=informations'
                );
              });
          }
        });
    }}
    icon="fa-copy"
    text="Duplicate" />
}

function YAMLExportButton({ value }) {
  return <GridButton
    onClick={() => {
      const what = window.location.pathname.split('/')[3];
      const itemName = what === 'routes' ? 'route' : 'route-composition';
      const kind = what === 'routes' ? 'Route' : 'RouteComposition';
      const name = value.id
        .replace(/ /g, '-')
        .replace(/\(/g, '')
        .replace(/\)/g, '')
        .toLowerCase();

      fetch('/bo/api/json_to_yaml', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          apiVersion: 'proxy.otoroshi.io/v1alpha1',
          kind,
          metadata: {
            name,
          },
          spec: value,
        }),
      })
        .then((r) => r.text())
        .then((yaml) => {
          const blob = new Blob([yaml], { type: 'application/yaml' });
          const url = URL.createObjectURL(blob);
          const a = document.createElement('a');
          a.id = String(Date.now());
          a.style.display = 'none';
          a.download = `${itemName}-${name}-${Date.now()}.yaml`;
          a.href = url;
          document.body.appendChild(a);
          a.click();
          setTimeout(() => document.body.removeChild(a), 300);
        });
    }}
    icon="fa-file-export"
    text="Export YAML" />
}

function JsonExportButton({ value }) {
  return <GridButton
    onClick={() => {
      const what = window.location.pathname.split('/')[3];
      const itemName = what === 'routes' ? 'route' : 'route-composition';
      const kind = what === 'routes' ? 'Route' : 'RouteComposition';
      const name = value.id
        .replace(/ /g, '-')
        .replace(/\(/g, '')
        .replace(/\)/g, '')
        .toLowerCase();
      const json = JSON.stringify({ ...value, kind }, null, 2);
      const blob = new Blob([json], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.id = String(Date.now());
      a.style.display = 'none';
      a.download = `${itemName}-${name}-${Date.now()}.json`;
      a.href = url;
      document.body.appendChild(a);
      a.click();
      setTimeout(() => document.body.removeChild(a), 300);
    }}
    icon="fas fa-file-export"
    text="Export JSON" />
}

function BackToRouteTab({ isActive, url, viewPlugins }) {
  return (
    <div className="ms-2" style={{ height: '100%' }}>
      <button type="button"
        className="btn btn-sm toggle-form-buttons d-flex align-items-center"
        onClick={() => history.replace(`${url}?tab=routes&view_plugins=${viewPlugins}`)}
        style={{
          backgroundColor: '#494948',
          color: '#fff',
          height: '100%',
        }}>
        <i className="fas fa-arrow-left me-2" style={{ fontSize: '1.33333em' }} />
        Back to route
      </button>
    </div>
  )
}

function InformationsTab({ isActive, entity, value, history }) {
  return (
    <div className="ms-2" style={{ height: '100%' }}>
      <button type="button"
        className="btn btn-sm toggle-form-buttons d-flex align-items-center"
        onClick={() => {
          const to = `/${entity.link}/${value.id}?tab=informations`;
          if (!window.location.href.includes(to))
            history.push(to);
        }}
        style={{
          backgroundColor: isActive ? '#f9b000' : '#494948',
          color: '#fff',
          height: '100%',
        }}>
        <i className="fas fa-file-alt me-2" style={{ fontSize: '1.33333em' }} />
        Informations
      </button>
    </div >
  )
}

function RoutesTab({ isActive, entity, value, history }) {
  return (
    <div className="ms-2" style={{ height: '100%' }}>
      <button type="button"
        className="btn btn-sm toggle-form-buttons d-flex align-items-center"
        onClick={() => {
          const to = `/${entity.link}/${value.id}?tab=routes`;
          if (!window.location.href.includes(to))
            history.push(to);
        }}
        style={{
          backgroundColor: isActive ? '#f9b000' : '#494948',
          color: '#fff',
          height: '100%',
        }}>
        <i className="fas fa-road me-2" style={{ fontSize: '1.33333em' }} />
        Routes
      </button>
    </div >
  )
}

function DesignerTab({ isActive, entity, value, history }) {
  return (
    <div className="ms-2" style={{ height: '100%' }}>
      <button type="button"
        className="btn btn-sm toggle-form-buttons d-flex align-items-center"
        onClick={() => {
          const to = `/${entity.link}/${value.id}?tab=flow`;
          if (!window.location.href.includes(to))
            history.push(to);
        }}
        style={{
          backgroundColor: isActive ? '#f9b000' : '#494948',
          color: '#fff',
          height: '100%',
        }}>
        <i className="fas fa-pencil-ruler me-2" style={{ fontSize: '1.33333em' }} />
        Designer
      </button>
    </div>
  )
}

function TesterButton({ disabled, setForceTester, viewRef, history, value, isOnViewPlugins, forceHideTester, query }) {
  const disabledHelp = 'Your route is disabled. Navigate to the informations page to turn it on again';
  const hidden = !(isOnViewPlugins || forceHideTester !== true) || query === 'routes';

  return (
    <HelpWrapper text={disabled ? disabledHelp : undefined} dataPlacement="bottom">
      <div className="ms-2 pb-2" style={{
        height: '100%',
        opacity: hidden ? 0 : 1,
        pointerEvents: hidden ? 'none' : 'auto',
      }}>
        <button type="button"
          className="btn btn-sm toggle-form-buttons d-flex align-items-center"
          onClick={() => {
            setForceTester(true)
            viewRef?.current?.onTestingButtonClick(history, value)
          }}
          style={{
            marginLeft: 20,
            backgroundColor: '#494948',
            color: '#fff',
            height: '100%',
          }}>
          <i className="fas fa-vials" style={{ fontSize: '1.33333em' }} />
          Tester
        </button>
      </div>
    </HelpWrapper>
  )
}

function MoreActionsButton({ value, menu, history }) {
  return <div className="ms-2 dropdown" style={{ height: '100%' }}>
    <button type="button"
      className="btn btn-sm toggle-form-buttons d-flex align-items-center"
      style={{
        backgroundColor: '#494948',
        color: '#fff',
        height: '100%',
      }}
      id='designer-menu'
      data-bs-toggle='dropdown'
      data-bs-auto-close='outside'
      aria-expanded='false'>
      <i className="fas fa-ellipsis-h" style={{ fontSize: '1.33333em' }} />
    </button>
    <ul
      className="dropdown-menu"
      aria-labelledby="designer-menu"
      style={{
        background: 'rgb(73, 73, 72)',
        border: '1px solid #373735',
        borderTop: 0,
        padding: '12px',
        zIndex: 4000,
      }}
      onClick={(e) => e.stopPropagation()}>
      <li className="d-flex flex-wrap" style={{
        gap: '8px',
        minWidth: '170px'
      }}>
        <DuplicateButton value={value} history={history} />
        <JsonExportButton value={value} />
        <YAMLExportButton value={value} />
        <DeleteRouteButton />
        {menu}
        <BackToButton history={history} />
      </li>
    </ul>
  </div>
}


function ManagerTitle({
  query, isCreation, isOnViewPlugins, entity, menu, pathname,
  value, viewPlugins, viewRef, location, history, saveButton,
  url, setForceTester, forceHideTester }) {

  const commonsProps = {
    entity, value, history
  };

  const tabs = [
    {
      visible: () => isOnViewPlugins,
      component: () => <BackToRouteTab url={url} viewPlugins={viewPlugins} />
    },
    {
      visible: () => !isOnViewPlugins,
      tab: 'informations',
      component: () => <InformationsTab
        isActive={query === 'informations'} {...commonsProps} />
    },
    {
      visible: () => ['route-compositions'].includes(entity.link),
      component: () => <RoutesTab
        isActive={query === 'routes'} {...commonsProps} />
    },
    {
      visible: () => !isOnViewPlugins,
      component: () => <DesignerTab
        isActive={query === 'flow'} {...commonsProps} />
    },
    {
      component: () => <TesterButton
        disabled={!value.enabled}
        setForceTester={setForceTester}
        viewRef={viewRef}
        history={history}
        value={value}
        isOnViewPlugins={isOnViewPlugins}
        forceHideTester={forceHideTester}
        query={query}
      />
    },
    {
      visible: () => !isOnViewPlugins,
      component: () => <MoreActionsButton value={value} menu={menu} history={history} />
    }
  ];

  return <div className="page-header d-flex align-item-center justify-content-between ms-0 mb-3" style={{
    paddingBottom: pathname === '/routes' ? 'initial' : 0
  }}>
    <h4 className="flex" style={{ margin: 0 }}>
      {location.pathname.includes('route-compositions') ? 'Route compositions' :
        {
          flow: 'Designer',
          informations: 'Informations',
          routes: 'Routes',
          route_plugins: 'Route plugins'
        }[query]
      }
    </h4>
    <div className="d-flex align-item-center justify-content-between flex">
      {!isCreation &&
        tabs
          .filter(tab => !tab.visible || tab.visible())
          .filter(tab => location.state?.routeFromService ? tab.tab === 'Informations' : true)
          .map(({ component }) => {
            const Tab = component;
            return <Tab key={Tab.type} />
          })}
      {saveButton}
    </div>
  </div>
}

class Manager extends React.Component {
  state = {
    value: undefined,
    menu: undefined,
    menuRefreshed: undefined,
    saveButton: undefined,
    saveTypeButton: undefined,
    forceHideTester: false
  }

  viewRef = React.createRef(null)

  componentDidMount() {
    this.loadRoute();
  }

  componentDidUpdate(prevProps, prevState) {
    if (this.props.match.params.routeId !== prevProps.match.params.routeId)
      this.loadRoute();

    if (['saveTypeButton', 'menuRefreshed', 'forceHideTester']
      .some(field => this.state[field] !== prevState[field])) {
      this.setTitle()
    }
  }

  setTitle = () => {
    if (!this.state.value)
      return;

    const { query, entity, history, location } = this.props;

    const p = this.props.match.params
    const isCreation = p.routeId === 'new';

    const rawViewPlugins = new URLSearchParams(location.search).get('view_plugins');
    const viewPlugins = rawViewPlugins !== null ? Number(rawViewPlugins) : -1;
    const isOnViewPlugins = (viewPlugins !== -1) & (query === 'route_plugins');
    const url = p.url;

    this.props.setTitle(() => <ManagerTitle
      forceHideTester={this.state.forceHideTester}
      setForceTester={va => this.setState({ forceHideTester: va })}
      pathname={location.pathname}
      menu={this.state.menu}
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
      saveButton={this.state.saveButton} />);
  }

  loadRoute = () => {
    console.log('load route')
    const { routeId } = this.props.match.params || { routeId: undefined }
    if (routeId === 'new') {
      nextClient.template(nextClient.ENTITIES[this.props.entity.fetchName])
        .then(value => {
          this.setState({ value }, this.updateSidebar)
        });
    } else {
      nextClient.fetch(nextClient.ENTITIES[this.props.entity.fetchName], routeId)
        .then(res => {
          if (!res.error)
            this.setState({ value: res }, this.updateSidebar)
        });
    }
  }

  updateSidebar = () => {
    this.props.setSidebarContent(
      <DesignerSidebar route={this.state.value} setSidebarContent={this.props.setSidebarContent} />
    );

    this.setTitle()
  }

  render() {
    const { query, entity, history, location, ...props } = this.props;

    const p = this.props.match.params
    const isCreation = p.routeId === 'new';

    const rawViewPlugins = new URLSearchParams(location.search).get('view_plugins');
    const viewPlugins = rawViewPlugins !== null ? Number(rawViewPlugins) : -1;

    // const isOnViewPlugins = (viewPlugins !== -1) & (query === 'route_plugins');
    // const url = p.url;
    // const [value, setValue] = useState(location.state?.routeFromService);

    const { value } = this.state;
    const divs = [
      {
        predicate: query && ['flow', 'route_plugins'].includes(query) && !isCreation,
        render: () => (
          <Designer
            {...this.props}
            toggleTesterButton={va => this.setState({ forceHideTester: va })}
            ref={this.viewRef}
            tab={query}
            history={history}
            value={value}
            setSaveButton={n => this.setState({ saveButton: n, saveTypeButton: 'routes' })}
            viewPlugins={viewPlugins}
            setMenu={n => this.setState({ menu: n, menuRefreshed: Date.now() })}
          />
        ),
      },
      {
        predicate: query && query === 'routes',
        render: () =>
          value && (
            <RouteCompositions
              ref={this.viewRef}
              service={value}
              setSaveButton={n => this.setState({ saveButton: n, saveTypeButton: 'route-compositions' })}
              setRoutes={routes => setValue({ ...value, routes })}
              viewPlugins={viewPlugins}
            />
          ),
      },
    ];

    const component = divs.filter((p) => p.predicate);

    if (component.length > 0) {
      return <div className="designer row">{component[0].render()}</div>;
    }

    return (
      <div className="designer row ps-3">
        <Informations
          {...this.props}
          routeId={p.routeId}
          ref={this.viewRef}
          isCreation={isCreation}
          value={value}
          setValue={n => this.setState({ value: n })}
          setSaveButton={n => this.setState({ saveButton: n, saveTypeButton: 'informations' }, this.setTitle)}
        />
      </div>
    );
  }
}

const RoutesView = ({ history }) => {
  const [creation, setCreation] = useState(false);
  const [importServiceDescriptor, setImportServiceDescriptor] = useState(false);
  const { pathname } = useLocation()

  return (
    <>
      {creation && <RouteWizard hide={() => setCreation(false)} history={history} />}
      {importServiceDescriptor && <ImportServiceDescriptor hide={() => setImportServiceDescriptor(false)} history={history} />}
      <Routes
        injectTopBar={pathname.includes('route-compositions') ?
          null : <>
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
          </>}
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
      [...document.getElementsByClassName('row')].map((r) => r.classList.add('patch-row', 'g-0'));
    } else {
      document.getElementsByClassName('main')[0].classList.remove('patch-main');
      [...document.getElementsByClassName('row')].map((r) =>
        r.classList.remove('patch-row', 'g-0')
      );
    }
  };

  render() {
    const { match, history, location } = this.props;

    const entity = entityFromURI(location);
    const query = new URLSearchParams(location.search).get('tab');

    return (
      <Switch>
        {[
          { path: `${match.url}/:routeId/health`, component: ServiceHealthPage },
          { path: `${match.url}/:routeId/analytics`, component: ServiceAnalyticsPage },
          { path: `${match.url}/:routeId/apikeys`, component: ServiceApiKeysPage },
          { path: `${match.url}/:routeId/stats`, component: ServiceLiveStatsPage },
          { path: `${match.url}/:routeId/events`, component: ServiceEventsPage },
          {
            path: `${match.url}/:routeId`,
            component: p => <Manager query={query} {...this.props} {...p} entity={entity} />
          }
        ].map(({ path, component }) => {
          const Component = component;
          return (
            <Route
              exact
              key={path}
              path={path}
              component={p => {
                return <Component
                  setSidebarContent={this.props.setSidebarContent}
                  setTitle={this.props.setTitle}
                  {...p}
                />
              }}
            />
          );
        })}
        <Route component={() => <RoutesView history={history} />} />
      </Switch>
    );
  }
};

export default withRouter(RouteDesigner);