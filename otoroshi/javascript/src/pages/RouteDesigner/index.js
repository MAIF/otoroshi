import React, { useEffect, useRef, useState } from 'react';
import { Route, Switch, useHistory, useLocation, useParams, useRouteMatch } from 'react-router-dom';
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
import { useEntityFromURI } from '../../util';
import { v4 } from 'uuid';
import { Help, HelpWrapper } from '../../components/inputs';

const GridButton = ({ onClick, text, icon, level = "info" }) => {
  return <button
    type="button"
    className={`btn btn-sm btn-${level} d-flex align-items-center justify-content-center flex-column`}
    style={{
      // minWidth: '50%',
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


const BackToButton = ({ history }) => {
  return <GridButton
    onClick={() => {
      const what = window.location.pathname.split('/')[3];
      history.push('/' + what);
    }}
    icon="fa-arrow-left"
    text={`Back to ${window.location.pathname.split('/')[3]}`} />
}

const DeleteRouteButton = () => {
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

const DuplicateButton = ({ value, history }) => {
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

const YAMLExportButton = ({ value }) => {
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

const JsonExportButton = ({ value }) => {
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

const Manager = ({ query, entity, ...props }) => {
  const p = useParams();
  const isCreation = p.routeId === 'new';
  const history = useHistory();
  const { url } = useRouteMatch();
  const location = useLocation();

  const rawViewPlugins = new URLSearchParams(location.search).get('view_plugins');
  const viewPlugins = rawViewPlugins !== null ? Number(rawViewPlugins) : -1;
  const isOnViewPlugins = (viewPlugins !== -1) & (query === 'route_plugins');

  const [value, setValue] = useState(location.state?.routeFromService);
  const [saveButton, setSaveButton] = useState(null);
  const [menu, setMenu] = useState();

  const [forceHideTester, setForceTester] = useState(false)

  const viewRef = useRef();

  useEffect(() => {
    if (p.routeId === 'new') {
      nextClient.template(nextClient.ENTITIES[entity.fetchName]).then(setValue);
    } else {
      nextClient.fetch(nextClient.ENTITIES[entity.fetchName], p.routeId)
        .then(res => {
          if (!res.error)
            setValue(res)
        });
    }
  }, [p.routeId]);

  useEffect(() => {
    if (value && value.id) {
      props.setSidebarContent(
        <DesignerSidebar route={value} setSidebarContent={props.setSidebarContent} />
      );

      props.setTitle(getTitle);
    }
  }, [value, saveButton, menu, viewRef, forceHideTester, isOnViewPlugins]);

  const getTitle = () => {
    return <div className="page-header d-flex align-item-center justify-content-between ms-0 mb-3">
      <h4 className="flex" style={{ margin: 0 }}>
        {
          {
            flow: 'Designer',
            informations: 'Informations',
            routes: 'Routes',
            route_plugins: 'Route plugins',
          }[query]
        }
      </h4>
      <div className="d-flex align-item-center justify-content-between flex">
        {!isCreation &&
          [
            {
              onClick: () => history.replace(`${url}?tab=routes&view_plugins=${viewPlugins}`),
              icon: 'fa-arrow-left',
              title: 'Back to route',
              enabled: () => isOnViewPlugins,
            },
            {
              to: `/${entity.link}/${value.id}?tab=informations`,
              icon: 'fa-file-alt',
              title: 'Informations',
              tab: 'informations',
              enabled: () => !isOnViewPlugins,
            },
            {
              to: `/${entity.link}/${value.id}?tab=routes`,
              icon: 'fa-road',
              title: 'Routes',
              tab: 'routes',
              enabled: () => ['route-compositions'].includes(entity.link),
            },
            {
              to: `/${entity.link}/${value.id}?tab=flow`,
              icon: 'fa-pencil-ruler',
              title: 'Designer',
              tab: 'flow',
              enabled: () => !isOnViewPlugins,
            },
            {
              icon: 'fa-vials',
              style: { marginLeft: 20 },
              moreClass: 'pb-2',
              title: 'Tester',
              disabledHelp: 'Your route is disabled. Navigate to the informations page to turn it',
              disabled: !value.enabled,
              onClick: () => {
                setForceTester(true)
                viewRef?.current?.onTestingButtonClick(history, value)
              },
              hidden: !(isOnViewPlugins || forceHideTester !== true) || query === 'routes',
            },
            {
              icon: 'fa-ellipsis-h',
              onClick: () => { },
              enabled: () => !isOnViewPlugins, //isOnViewPlugins || query == 'flow',
              dropdown: true,
              props: {
                id: 'designer-menu',
                'data-bs-toggle': 'dropdown',
                'data-bs-auto-close': 'outside',
                'aria-expanded': 'false',
              },
            },
          ]
            .filter((link) => !link.enabled || link.enabled())
            .filter(link => location.state?.routeFromService ? link.tab === 'Informations' : true)
            .map(({ to, icon, title, tooltip, tab, onClick, dropdown, moreClass, style, props = {}, hidden, disabledHelp, disabled }) => (
              <HelpWrapper text={disabled ? disabledHelp : undefined} dataPlacement="bottom">
                <div className={`ms-2 ${moreClass ? moreClass : ''} ${dropdown ? 'dropdown' : ''}`}
                  style={{
                    opacity: hidden ? 0 : 1,
                    pointerEvents: hidden ? 'none' : 'auto',
                    height: '100%'
                  }}>
                  <button
                    key={title}
                    disabled={disabled}
                    type="button"
                    className={`btn btn-sm toggle-form-buttons d-flex align-items-center`}
                    onClick={
                      onClick
                        ? onClick
                        : () => {
                          if (query !== tab || viewPlugins) {
                            if (!window.location.href.includes(to))
                              history.push(to);
                          }
                        }
                    }
                    {...(tooltip || {})}
                    style={{
                      ...(style || {}),
                      backgroundColor: tab === query ? '#f9b000' : '#494948',
                      color: '#fff',
                      height: '100%',
                    }}
                    {...props}>
                    {icon && (
                      <i
                        className={`fas ${icon} ${title ? 'me-2' : ''}`}
                        style={{ fontSize: '1.33333em' }}
                      />
                    )}{' '}
                    {title}
                  </button>
                  {dropdown && (
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
                  )}
                </div>
              </HelpWrapper>
            ))}
        {saveButton}
      </div>
    </div>
  }

  const divs = [
    {
      predicate: query && ['flow', 'route_plugins'].includes(query) && !isCreation,
      render: () => (
        <Designer
          {...props}
          toggleTesterButton={e => {
            setForceTester(e)
          }}
          ref={viewRef}
          tab={query}
          history={history}
          value={value}
          setSaveButton={setSaveButton}
          viewPlugins={viewPlugins}
          setMenu={setMenu}
        />
      ),
    },
    {
      predicate: query && query === 'routes',
      render: () =>
        value && (
          <RouteCompositions
            ref={viewRef}
            service={value}
            setSaveButton={setSaveButton}
            setService={setValue}
            viewPlugins={viewPlugins}
          />
        ),
    },
  ];

  const component = divs.filter((p) => p.predicate);

  if (component.length > 0) return <div className="designer row">{component[0].render()}</div>;

  return (
    <div className="designer row ps-3">
      <Informations
        {...props}
        routeId={p.routeId}
        ref={viewRef}
        isCreation={isCreation}
        value={value}
        setValue={setValue}
        setSaveButton={setSaveButton}
      />
    </div>
  );
};

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

export default (props) => {
  const match = useRouteMatch();
  const history = useHistory();
  const { search, pathname } = useLocation();
  const entity = useEntityFromURI();
  const query = new URLSearchParams(search).get('tab');

  useEffect(() => {
    patchStyle(true);

    return () => patchStyle(false);
  }, []);

  useEffect(() => {
    if (pathname === '/routes' || pathname === '/routes') props.setSidebarContent(null);
  }, [pathname]);

  useEffect(() => {
    if (pathname.endsWith('route-compositions'))
      props.setTitle('Routes compositions');
    else if (!query)
      props.setTitle('Routes');
  }, [search]);

  const patchStyle = (applyPatch) => {
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
          component: () => <Manager query={query} {...props} entity={entity} />,
        },
      ].map(({ path, component }) => {
        const Component = component;
        return (
          <Route
            exact
            key={path}
            path={path}
            component={(p) => (
              <Component
                setSidebarContent={props.setSidebarContent}
                setTitle={props.setTitle}
                {...p.match}
                history={history}
              />
            )}
          />
        );
      })}
      <Route component={() => <RoutesView history={history} />} />
    </Switch>
  );
};
