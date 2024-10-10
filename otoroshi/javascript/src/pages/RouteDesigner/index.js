import React, { useEffect, useState } from 'react';
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
import { FeedbackButton } from './FeedbackButton';
import Loader from '../../components/Loader';
import _ from 'lodash';
import { Button } from '../../components/Button';
import { DraftEditorContainer } from '../../components/Drafts/DraftEditor';
import { dynamicTitleContent } from '../../components/DynamicTitleSignal';
import { draftSignal, draftVersionSignal, resetDraftSignal } from '../../components/Drafts/DraftEditorSignal';
import { useSignalValue } from 'signals-react-safe';
import PageTitle from '../../components/PageTitle';
import { Dropdown } from '../../components/Dropdown';
import { YAMLExportButton } from '../../components/exporters/YAMLButton';
import { JsonExportButton } from '../../components/exporters/JSONButton';
import JsonViewCompare from '../../components/Drafts/Compare';

function DuplicateModalContent({ value }) {
  return <pre style={{ height: 'inherit' }}>
    Frontend: {value.frontend.domains[0]}<br />
    Backend: {value.backend.targets[0].hostname}<br />
    Plugins: {value.plugins.length}
  </pre>
}

function DuplicateButton({ value, history }) {
  return (
    <Button
      type="primary"
      className="btn-sm ms-1"
      onClick={(e) => {
        const what = window.location.pathname.split('/')[3];
        const id = window.location.pathname.split('/')[4];
        const prefix = (id.split('_')[0] || what) + '_';
        const newId = `${prefix}${v4()}`;
        const kind = what === 'routes' ? nextClient.ENTITIES.ROUTES : nextClient.ENTITIES.SERVICES;
        window.newConfirm(<DuplicateModalContent value={value} />, {
          title: `Duplicate ${value.name}`,
          yesText: 'I want to duplicate this route'
        }).then((ok) => {
          if (ok) {
            nextClient
              .forEntityNext(kind)
              .create({
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
    >
      <i className="fas fa-copy" /> Duplicate route
    </Button>
  );
}

function BackToRouteTab({ history, routeId, viewPlugins }) {
  return (
    <div className="ms-2">
      <button
        type="button"
        className="btn btn-sm toggle-form-buttons d-flex align-items-center h-100"
        onClick={() => history.replace(`${routeId}?tab=routes&view_plugins=${viewPlugins}`)}
        style={{
          backgroundColor: 'var(--bg-color_level2)',
          color: 'var(--color_level2)',
        }}
      >
        <i className="fas fa-arrow-left me-2" style={{ fontSize: '1.33333em' }} />
        Back to route
      </button>
    </div>
  );
}

function RoutesTab({ isActive, entity, value, history }) {
  return (
    <div className="ms-2">
      <button
        type="button"
        className="btn btn-sm toggle-form-buttons d-flex align-items-center h-100"
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
          backgroundColor: isActive ? 'var(--color-primary)' : 'var(--bg-color_level2)',
          color: isActive ? 'var(--color-white)' : 'var(--color_level2)',
        }}
      >
        <i className="fas fa-road me-2" style={{ fontSize: '1.33333em' }} />
        Routes
      </button>
    </div>
  );
}

function MoreActionsButton({ value, menu, history }) {
  // const handleSelect = (e) => {
  //   const kind = e.target.value;

  //   if (kind === 'json') {
  //     const what = window.location.pathname.split('/')[3];
  //     const entityKind = what === 'routes' ? 'Route' : 'RouteComposition';
  //     const itemName = entityKind
  //       ? entityKind.toLowerCase()
  //       : what === 'routes'
  //         ? 'route'
  //         : 'route-composition';
  //     const kind = entityKind || (what === 'routes' ? 'Route' : 'RouteComposition');
  //     const name = value.id.replace(/ /g, '-').replace(/\(/g, '').replace(/\)/g, '').toLowerCase();
  //     const json = JSON.stringify({ ...value, kind }, null, 2);
  //     const blob = new Blob([json], { type: 'application/json' });
  //     const url = URL.createObjectURL(blob);
  //     const a = document.createElement('a');
  //     a.id = String(Date.now());
  //     a.style.display = 'none';
  //     a.download = `${itemName}-${name}-${Date.now()}.json`;
  //     a.href = url;
  //     document.body.appendChild(a);
  //     a.click();
  //     setTimeout(() => document.body.removeChild(a), 300);
  //   } else {
  //     const what = window.location.pathname.split('/')[3];
  //     const entityKind = what === 'routes' ? 'Route' : 'RouteComposition';
  //     const itemName = entityKind
  //       ? entityKind.toLowerCase()
  //       : what === 'routes'
  //         ? 'route'
  //         : 'route-composition';
  //     const kind = entityKind || (what === 'routes' ? 'Route' : 'RouteComposition');
  //     const name = value.id.replace(/ /g, '-').replace(/\(/g, '').replace(/\)/g, '').toLowerCase();

  //     fetch('/bo/api/json_to_yaml', {
  //       method: 'POST',
  //       headers: {
  //         'Content-Type': 'application/json',
  //       },
  //       body: JSON.stringify({
  //         apiVersion: 'proxy.otoroshi.io/v1',
  //         kind,
  //         metadata: {
  //           name,
  //         },
  //         spec: value,
  //       }),
  //     })
  //       .then((r) => r.text())
  //       .then((yaml) => {
  //         const blob = new Blob([yaml], { type: 'application/yaml' });
  //         const url = URL.createObjectURL(blob);
  //         const a = document.createElement('a');
  //         a.id = String(Date.now());
  //         a.style.display = 'none';
  //         a.download = `${itemName}-${name}-${Date.now()}.yaml`;
  //         a.href = url;
  //         document.body.appendChild(a);
  //         a.click();
  //         setTimeout(() => document.body.removeChild(a), 300);
  //       });
  //   }
  // };

  return (
    <div className="mb-1 d-flex" style={{ gap: '.5rem' }}>
      <DuplicateButton value={value} history={history} />
      <YAMLExportButton value={value} entityKind="JwtVerifier" />
      <JsonExportButton value={value} entityKind="JwtVerifier" />
      {/* <select
        className="form-select selectSkin btn-primary"
        aria-label="Choose export"
        onChange={handleSelect}
      >
        <option value="export">Export</option>
        <option value="json">JSON</option>
        <option value="yaml">YAML</option>
      </select> */}
      {menu}
    </div>
  );
}

function PublisDraftModalContent({ ok }) {
  const context = useSignalValue(draftSignal)

  return <div className='mt-3 d-flex flex-column' style={{ flex: 1 }}>
    <JsonViewCompare oldData={context.entityContent} newData={context.draft} />
  </div>
}

function PublisDraftButton() {
  const publish = useSignalValue(draftVersionSignal)
  const context = useSignalValue(draftSignal)

  if (publish.version === 'published')
    return null

  return <Button text="Publish draft" className='btn-sm ms-2 mb-1' type="primaryColor" style={{
    borderColor: 'var(--color-primary)'
  }} onClick={() => {
    window.wizard(
      'Publish this draft',
      () => <PublisDraftModalContent />,
      {
        style: { width: '100%' },
        noCancel: false,
        okClassName: "ms-2",
        okLabel: 'I want to publish this route'
      }
    )
      .then((ok) => {
        if (ok) {
          const what = window.location.pathname.split('/')[3];
          const kind = what === 'routes' ? nextClient.ENTITIES.ROUTES : nextClient.ENTITIES.SERVICES;

          nextClient
            .forEntityNext(kind)
            .update(context.draft)
            .then(() => {
              window.location.reload()
            });
        }
      });
    // window.newConfirm(<PublisDraftModalContent />, {
    //   title: `Publish this draft`,
    //   yesText: 'I want to publish this route',
    //   className: 'modal-dialog--lg'
    // }).then((ok) => {
    //   if (ok) {
    //     nextClient
    //       .forEntityNext(kind)
    //       .update(context.draft)
    //       .then(() => {
    //         window.location.reload()
    //       });
    //   }
    // });
  }} />
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
  location,
  history,
  saveButton,
  routeId,
  ...props
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
      visible: () => ['route-compositions'].includes(entity.link),
      component: () => <RoutesTab isActive={query === 'routes'} {...commonsProps} />,
    },
    {
      visible: () => !isOnViewPlugins,
      component: () => <MoreActionsButton value={value} menu={menu} history={history} />,
    },
  ].flatMap((item) => {
    if (Array.isArray(item)) {
      return item;
    } else {
      return [item];
    }
  });

  const maybeExtensionTab = Otoroshi.extensions()
    .flatMap((ext) => ext.routeDesignerTabs || [])
    .find((item) => item.id === query);
  const maybeExtensionTabLabel = maybeExtensionTab ? maybeExtensionTab.label : '';

  return (
    <PageTitle
      style={{
        paddingBottom: pathname === '/routes' ? 'initial' : 0,
      }}
      title={
        {
          flow: value.name,
          informations: isCreation ? `Create a new Route` : value.name,
          routes: 'Routes',
          route_plugins: 'Route plugins',
        }[query] || maybeExtensionTabLabel
      }
      {...props}
    >
      {!isOnViewPlugins && <div style={{
        position: 'absolute',
        left: 0,
        right: 0,
        margin: 'auto',
        bottom: '1.25rem',
        width: 'fit-content'
      }}>
        <DraftEditorContainer
          entityId={value.id}
          value={value} />
      </div>}
      <Dropdown className='mb-1'>
        {!isCreation &&
          tabs
            .filter((tab) => !tab.visible || tab.visible())
            .filter((tab) => (location.state?.routeFromService ? tab.tab === 'Informations' : true))
            .map(({ component }, i) => {
              const Tab = component;
              return <Tab key={`tab-${i}`} />;
            })}
      </Dropdown>
      {saveButton}
      <PublisDraftButton />
    </PageTitle>
  );
}

class Manager extends React.Component {
  state = {
    value: this.props.value,
    menu: undefined,
    menuRefreshed: undefined,
    saveButton: undefined,
    saveTypeButton: undefined,
    forceHideTester: false,
    loading: false,
    template: undefined,
  };

  componentDidMount() {
    if (this.props.value) {
      this.updateSidebar();
    }

    window.history.replaceState({}, document.title);
  }

  loadRoute = () => {
    nextClient
      .forEntityNext(nextClient.ENTITIES[this.props.entity.fetchName])
      .template()
      .then((value) => {
        this.setState({ value, loading: false, template: value });
      });
  };

  componentDidUpdate(prevProps, prevState) {
    if (this.props.routeId !== prevProps.routeId || this.props.routeId === 'new') {
      if (!this.state.template) this.loadRoute();
    }

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

    // this.props.setTitle(
    dynamicTitleContent.value = <ManagerTitle
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
      location={location}
      history={history}
      saveButton={this.state.saveButton}
      globalEnv={this.props.globalEnv}
      env={this.props.globalEnv}
      reloadEnv={this.props.reloadEnv}
      getTitle={this.props.getTitle}
    />
    // );
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
    const { entity, history, location } = this.props;

    let query = new URLSearchParams(location.search).get('tab');

    if (!query && location.pathname.includes('?')) {
      query = new URLSearchParams(`?${location.pathname.split('?')[1]}`).get('tab');
    }

    const isCreation = this.props.routeId === 'new';

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
            tab={query}
            history={history}
            value={this.state.value}
            setValue={(v) => {
              this.setState({ value: v }, this.setTitle)
            }}
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
      Otoroshi.extensions()
        .flatMap((ext) => ext.routeDesignerTabs || [])
        .map((item) => {
          return {
            predicate: query && query === item.id,
            render: () =>
              item.render({
                value: this.state.value,
                history,
                query,
                isCreation,
                setValue: (v) => this.setState({ value: v }, this.setTitle),
                setSaveButton: (n) => this.setState({ saveButton: n, saveTypeButton: item.id }),
                setMenu: (n) => this.setState({ menu: n, menuRefreshed: Date.now() }),
                FeedbackButton: FeedbackButton,
                props: this.props,
              }),
          };
        }),
    ].flatMap((item) => {
      if (Array.isArray(item)) {
        return item;
      } else {
        return [item];
      }
    });

    const component = divs.filter((p) => p.predicate);

    if (component.length > 0) {
      return (
        <Loader loading={loading}>
          <div className="designer">{component[0].render()}</div>
        </Loader>
      );
    }

    return (
      <Loader loading={loading}>
        <div className="designer ps-3">
          <Informations
            {...this.props}
            routeId={this.props.routeId}
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
                className="btn btn-primary btn-sm"
                style={{
                  _backgroundColor: 'var(--color-primary)',
                  _borderColor: 'var(--color-primary)',
                  marginLeft: 5,
                }}
              >
                <i className="fas fa-hat-wizard" /> Create with wizard
              </button>
              <button
                onClick={() => setImportServiceDescriptor(true)}
                className="btn btn-primary btn-sm"
                style={{
                  _backgroundColor: 'var(--color-primary)',
                  _borderColor: 'var(--color-primary)',
                  marginLeft: 5,
                }}
              >
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
  state = {
    value: undefined,
    loading: true,
  };

  componentDidMount() {
    this.props.setTitle('Routes');

    if (!this.props.location?.state?.value) {
      this.loadRoute();
    } else {
      this.setState({ loading: false });
    }
  }

  componentDidUpdate(prevProps) {
    if (this.props.match.params.routeId !== prevProps.match.params.routeId) {
      this.loadRoute();
      resetDraftSignal()
    }
  }

  componentWillUnmount() {
    resetDraftSignal()
  }

  loadRoute = () => {
    const { routeId } = this.props.match.params || { routeId: undefined };

    if (
      routeId === 'new' ||
      (this.props.location.state && this.props.location.state.routeFromService)
    ) {
      this.setState({ loading: false });
    } else if (routeId) {
      nextClient
        .forEntityNext(nextClient.ENTITIES[entityFromURI(this.props.location).fetchName])
        .findById(routeId)
        .then((res) => {
          if (!res.error) {
            this.setState({ value: res, loading: false });
          }
        });
    }
  };

  render() {
    const { match, history, location, globalEnv } = this.props;

    const entity = entityFromURI(location);

    // console.log(this.props, this.state, location.state)

    if (Object.keys(match.params).length === 0)
      return <Route component={() => <RoutesView history={history} globalEnv={globalEnv} />} />;

    return (
      <Switch>
        {[
          {
            path: `${match.url}/health`,
            component: (props) => (
              <ServiceHealthPage {...props} title={this.state.value?.name} {...match} />
            ),
          },
          {
            path: `${match.url}/analytics`,
            component: (props) => (
              <ServiceAnalyticsPage {...props} title={this.state.value?.name} {...match} />
            ),
          },
          {
            path: `${match.url}/apikeys/:taction/:titem`,
            component: (props) => (
              <ServiceApiKeysPage {...props} title={this.state.value?.name} {...match} />
            ),
          },
          {
            path: `${match.url}/apikeys`,
            component: (props) => (
              <ServiceApiKeysPage {...props} title={this.state.value?.name} {...match} />
            ),
          },
          {
            path: `${match.url}/stats`,
            component: (props) => (
              <ServiceLiveStatsPage {...props} title={this.state.value?.name} {...match} />
            ),
          },
          {
            path: `${match.url}/events`,
            component: (props) => (
              <ServiceEventsPage {...props} title={this.state.value?.name} {...match} />
            ),
          },
          {
            path: `${match.url}/`,
            component: (p) => {
              return (
                <Manager
                  {...this.props}
                  {...p}
                  entity={entity}
                  globalEnv={globalEnv}
                  {...this.state}
                  routeId={match?.params?.routeId}
                />
              );
            },
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
                    onRoutes
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
