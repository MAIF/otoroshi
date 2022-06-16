import React, { useEffect, useState } from 'react';
import { Route, Switch, useHistory, useLocation, useParams, useRouteMatch } from 'react-router-dom';
import { nextClient } from '../../services/BackOfficeServices';
import Designer from './Designer';
import { TryIt } from './TryIt';
import RouteCompositions from './RouteComposition';
import Routes from './Routes';
import { Informations } from './Informations';
import { RouteForm } from './form';
import DesignerSidebar from './Sidebar';

import { ServiceEventsPage } from '../ServiceEventsPage';
import { ServiceLiveStatsPage } from '../ServiceLiveStatsPage';
import { ServiceHealthPage } from '../ServiceHealthPage';
import { ServiceAnalyticsPage } from '../ServiceAnalyticsPage';
import { ServiceApiKeysPage } from '../ServiceApiKeysPage';
import { useEntityFromURI } from '../../util';
import { v4 } from 'uuid';
import YAML from 'yaml';

const MenuContainer = ({ children }) => (
  <li className="p-2 px-3">
    <h4
      className="pb-2"
      style={{
        borderBottom: '1px solid',
      }}>
      Actions
    </h4>
    <div className="d-flex flex-column" style={{ color: '#fff' }}>
      {children}
    </div>
  </li>
);

const Manager = ({ query, entity, ...props }) => {
  const p = useParams();
  const isCreation = p.routeId === 'new';
  const history = useHistory();
  const { url } = useRouteMatch();

  const rawViewPlugins = new URLSearchParams(location.search).get('view_plugins');
  const viewPlugins = rawViewPlugins !== null ? Number(rawViewPlugins) : -1;
  const isOnViewPlugins = (viewPlugins !== -1) & (query === 'route_plugins');

  const [value, setValue] = useState();
  const [saveButton, setSaveButton] = useState(null);
  const [menu, setMenu] = useState();

  useEffect(() => {
    if (p.routeId === 'new') {
      nextClient.template(nextClient.ENTITIES[entity.fetchName]).then(setValue);
    } else nextClient.fetch(nextClient.ENTITIES[entity.fetchName], p.routeId).then(setValue);
  }, [p.routeId]);

  useEffect(() => {
    if (value && value.id) {
      props.setSidebarContent(<DesignerSidebar route={value} />);

      props.setTitle(() => (
        <div className="page-header d-flex align-item-center justify-content-between ms-0 mb-3">
          <h4 className="flex" style={{ margin: 0 }}>
            {
              {
                flow: 'Designer',
                'try-it': 'Test routes',
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
                  to: `/${entity.link}/${value.id}?tab=form`,
                  icon: 'fa-file-alt',
                  title: 'Form',
                  tab: 'form',
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
                  to: `/${entity.link}/${value.id}?tab=try-it`,
                  icon: 'fa-vials',
                  title: 'Tester',
                  tab: 'try-it',
                  enabled: () => !isOnViewPlugins,
                },
                {
                  title: 'Actions',
                  onClick: () => {},
                  enabled: () => !isOnViewPlugins,//isOnViewPlugins || query == 'flow',
                  dropdown: true,
                  style: { marginLeft: 20 },
                  props: {
                    id: 'designer-menu',
                    'data-bs-toggle': 'dropdown',
                    'data-bs-auto-close': 'outside',
                    'aria-expanded': 'false',
                  },
                },
              ]
                .filter((link) => !link.enabled || link.enabled())
                .map(({ to, icon, title, tooltip, tab, onClick, dropdown, style, props = {} }) => (
                  <div className={`ms-2 ${dropdown ? 'dropdown dropstart' : ''}`}>
                    <button
                      key={title}
                      type="button"
                      className={`btn btn-sm toggle-form-buttons d-flex align-items-center ${
                        dropdown ? 'dropdown-toggle' : ''
                      }`}
                      onClick={
                        onClick
                          ? onClick
                          : () => {
                              if (query !== tab || viewPlugins) history.push(to);
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
                        class="dropdown-menu"
                        aria-labelledby="designer-menu"
                        style={{
                          overflow: 'initial',
                          height: 'initial',
                          minWidth: '420px',
                          background: 'rgb(73, 73, 72)',
                          border: '1px solid',
                        }}
                        onClick={(e) => e.stopPropagation()}>
                        <MenuContainer>
                          {menu}
                          <div className="d-flex-between" style={{ borderTop: '1px solid #666666', marginTop: 10, paddingTop: 10 }}>
                            <button type="button" className="btn btn-sm btn-danger me-1" style={{ marginTop: 5 }} onClick={e => {
                              const part = window.location.pathname.split('/')[3];
                              // window.location = `/bo/dashboard/${part}`
                              history.push(`/${part}`)
                            }}><i class="fas fa-times" /> Cancel</button>
                            <button type="button" className="btn btn-sm btn-danger me-1" style={{ marginTop: 5 }} onClick={e => {
                              const what = window.location.pathname.split('/')[3];
                              const id = window.location.pathname.split('/')[4];
                              const kind = what === 'routes' ? nextClient.ENTITIES.ROUTES : nextClient.ENTITIES.SERVICES;
                              window.newConfirm('are you sure you want to delete this entity ?').then((ok) => {
                                if (ok) {
                                  nextClient.deleteById(kind, id).then(() => {
                                    // window.location = '/bo/dashboard/' + what;
                                    history.push('/' + what);
                                  });
                                }
                              });
                            }}><i class="fas fa-trash" /> Delete</button>
                            <button type="button" className="btn btn-sm btn-info me-1" style={{ marginTop: 5 }} onClick={e => {
                              const what = window.location.pathname.split('/')[3];
                              const id = window.location.pathname.split('/')[4];
                              const prefix = (id.split('_')[0] || what) + '_';
                              const newId = `${prefix}${v4()}`
                              const kind = what === 'routes' ? nextClient.ENTITIES.ROUTES : nextClient.ENTITIES.SERVICES;
                              window.newConfirm('are you sure you want to duplicate this entity ?').then((ok) => {
                                if (ok) {
                                  nextClient.create(kind, { ...value, name: value.name + ' (duplicated)', id: newId, enabled: false }).then(() => {
                                    // window.location = '/bo/dashboard/' + what + '/' + newId + '?tab=informations';
                                    history.push('/' + what + '/' + newId + '?tab=informations');
                                  });
                                }
                              });
                            }}><i class="far fa-copy" /> Duplicate</button>
                            <button type="button" className="btn btn-sm btn-info me-1" style={{ marginTop: 5 }} onClick={e => {
                              const what = window.location.pathname.split('/')[3];
                              const itemName = what === 'routes' ? 'route' : 'route-composition'
                              const kind = what === 'routes' ? 'Route' : 'RouteComposition'
                              const name = (value.id)
                                .replace(/ /g, '-')
                                .replace(/\(/g, '')
                                .replace(/\)/g, '')
                                .toLowerCase();
                              const json = JSON.stringify(
                                { ...value, kind },
                                null,
                                2
                              );
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
                            }}><i class="fas fa-file-export" /> Export JSON</button>
                            <button type="button" className="btn btn-sm btn-info me-1" style={{ marginTop: 5 }} onClick={e => {
                              const what = window.location.pathname.split('/')[3];
                              const itemName = what === 'routes' ? 'route' : 'route-composition'
                              const kind = what === 'routes' ? 'Route' : 'RouteComposition'
                              const name = (value.id)
                                .replace(/ /g, '-')
                                .replace(/\(/g, '')
                                .replace(/\)/g, '')
                                .toLowerCase();
                              const json = YAML.stringify({
                                apiVersion: 'proxy.otoroshi.io/v1alpha1',
                                kind,
                                metadata: {
                                  name,
                                },
                                spec: value,
                              });
                              const blob = new Blob([json], { type: 'application/yaml' });
                              const url = URL.createObjectURL(blob);
                              const a = document.createElement('a');
                              a.id = String(Date.now());
                              a.style.display = 'none';
                              a.download = `${itemName}-${name}-${Date.now()}.yaml`;
                              a.href = url;
                              document.body.appendChild(a);
                              a.click();
                              setTimeout(() => document.body.removeChild(a), 300);
                            }}><i class="fas fa-file-export" /> Export YAML</button>                            
                          </div>
                        </MenuContainer>
                      </ul>
                    )}
                  </div>
                ))}
            {saveButton}
          </div>
        </div>
      ));
    }
  }, [value, saveButton, menu]);

  const divs = [
    {
      predicate: query && ['flow', 'route_plugins'].includes(query) && !isCreation,
      render: () => (
        <Designer
          {...props}
          value={value}
          setSaveButton={setSaveButton}
          viewPlugins={viewPlugins}
          setMenu={setMenu}
        />
      ),
    },
    { predicate: query && query === 'try-it', render: () => <TryIt route={value} setSaveButton={setSaveButton} /> },
    { predicate: query && query === 'form' && entity.fetchName === 'ROUTES', render: () => <RouteForm setSaveButton={setSaveButton} isCreation={isCreation} routeId={p.routeId} setValue={setValue} /> },
    { predicate: query && query === 'form' && entity.fetchName === 'SERVICES', render: () => <div>services</div> },
    {
      predicate: query && query === 'routes',
      render: () =>
        value && (
          <RouteCompositions
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
        isCreation={isCreation}
        value={value}
        setValue={setValue}
        setSaveButton={setSaveButton}
      />
    </div>
  );
};

export default (props) => {
  const match = useRouteMatch();
  const { search } = useLocation();
  const entity = useEntityFromURI();
  const query = new URLSearchParams(search).get('tab');

  useEffect(() => {
    patchStyle(true);

    return () => patchStyle(false);
  }, []);

  useEffect(() => {
    if (!query) {
      props.setTitle('Routes');
    }
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
              />
            )}
          />
        );
      })}
      <Route component={Routes} />
    </Switch>
  );
};
