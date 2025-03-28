import React, { useState } from 'react';
import { Route, Switch, useLocation, withRouter } from 'react-router-dom';
import { nextClient } from '../../services/BackOfficeServices';
import Designer from './Designer';
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
import { v4 } from 'uuid';
import { FeedbackButton } from './FeedbackButton';
import Loader from '../../components/Loader';
import _ from 'lodash';
import { Button } from '../../components/Button';
import { DraftEditorContainer, PublisDraftButton } from '../../components/Drafts/DraftEditor';
import { dynamicTitleContent } from '../../components/DynamicTitleSignal';

import PageTitle from '../../components/PageTitle';
import { Dropdown } from '../../components/Dropdown';
import { YAMLExportButton } from '../../components/exporters/YAMLButton';
import { JsonExportButton } from '../../components/exporters/JSONButton';

function DuplicateModalContent({ value }) {
  return (
    <pre style={{ height: 'inherit' }}>
      Frontend: {value.frontend.domains[0]}
      <br />
      Backend: {value.backend.targets[0].hostname}
      <br />
      Plugins: {value.plugins.length}
    </pre>
  );
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
        const kind = nextClient.ENTITIES.ROUTES;
        window
          .newConfirm(<DuplicateModalContent value={value} />, {
            title: `Duplicate ${value.name}`,
            yesText: 'I want to duplicate this route',
          })
          .then((ok) => {
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

function MoreActionsButton({ value, history }) {
  return (
    <div className="mb-1 d-flex" style={{ gap: '.5rem' }}>
      <DuplicateButton value={value} history={history} />
      <YAMLExportButton value={value} entityKind="proxy.otoroshi.io/Route" />
      <JsonExportButton value={value} entityKind="proxy.otoroshi.io/Route" />
    </div>
  );
}

function ManagerTitle({
  query,
  isCreation,
  pathname,
  value,
  location,
  history,
  saveButton,
  routeId,
  ...props
}) {
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
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          margin: 'auto',
          bottom: '1.25rem',
          width: 'fit-content',
        }}
      >
        <DraftEditorContainer entityId={value.id} value={value} />
      </div>

      <Dropdown className="mb-1">
        {!isCreation && (location.state?.routeFromService ? tab.tab === 'Informations' : true) &&
          <MoreActionsButton value={value} history={history} />}
      </Dropdown>
      {saveButton}
      <PublisDraftButton className="ms-2 mb-1" />
    </PageTitle>
  );
}

class Manager extends React.Component {
  state = {
    value: this.props.value,
    saveButton: undefined,
    saveTypeButton: undefined,
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
      .forEntityNext(nextClient.ENTITIES.ROUTES)
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
      ['saveTypeButton'].some(
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

    const { history, location } = this.props;

    let query = new URLSearchParams(location.search).get('tab');

    if (!query && location.pathname.includes('?')) {
      query = new URLSearchParams(`?${location.pathname.split('?')[1]}`).get('tab');
    }

    const p = this.props.match.params;
    const isCreation = p.routeId === 'new';

    const url = p.url;

    dynamicTitleContent.value = (
      <ManagerTitle
        pathname={location.pathname}
        routeId={p.routeId}
        url={url}
        query={query}
        isCreation={isCreation}
        value={this.state.value}
        location={location}
        history={history}
        saveButton={this.state.saveButton}
        globalEnv={this.props.globalEnv}
        env={this.props.globalEnv}
        reloadEnv={this.props.reloadEnv}
        getTitle={this.props.getTitle}
      />
    );
  };

  updateSidebar = () => {
    if (location.pathname.endsWith('/routes')) {
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
    const { history, location } = this.props;

    let query = new URLSearchParams(location.search).get('tab');

    if (!query && location.pathname.includes('?')) {
      query = new URLSearchParams(`?${location.pathname.split('?')[1]}`).get('tab');
    }

    const isCreation = this.props.routeId === 'new';

    const { value, loading } = this.state;

    const divs = [
      {
        predicate: query && ['flow', 'route_plugins'].includes(query) && !isCreation,
        render: () => (
          <Designer
            {...this.props}
            tab={query}
            history={history}
            value={this.state.value}
            setValue={(v) => {
              this.setState({ value: v }, this.setTitle);
            }}
            setSaveButton={(n) => this.setState({ saveButton: n, saveTypeButton: 'routes' })}
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

  return (
    <>
      {creation && <RouteWizard hide={() => setCreation(false)} history={history} />}
      {importServiceDescriptor && (
        <ImportServiceDescriptor hide={() => setImportServiceDescriptor(false)} history={history} />
      )}
      <RoutesTable
        globalEnv={globalEnv}
        injectTopBar={<>
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
        </>}
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
    }
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
        .forEntityNext(nextClient.ENTITIES.ROUTES)
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

    // if (!this.state.value)
    //   return null

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
