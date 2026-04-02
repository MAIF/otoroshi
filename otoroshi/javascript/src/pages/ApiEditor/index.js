import React, { useEffect } from 'react';
import './index.scss';
import { Switch, Route } from 'react-router-dom';
import { QueryClientProvider } from 'react-query';
import { queryClient } from '../../components/Drafts/DraftEditor';
import { Documentation } from './Documentation';
import { Plans, PlanEditor } from './Plans';

import { SidebarComponent } from './SidebarComponent';
import { Actions } from './Actions';
import { APIGateway } from './APIGateway';
import { Endpoints, NewRoute, RouteDesigner } from './Routes';
import { Subscriptions, SubscriptionDesigner } from './Subscriptions';
import {
  PluginChains,
  NewPluginChains,
  PluginChainsDesigner,
  EditPluginChains,
} from './PluginChains';
import { Backends, NewBackend, EditBackend } from './Backends';
import {
  HttpClientSettings,
  NewHttpClientSettings,
  EditHttpClientSettings,
} from './HttpClientSettings';
import { Deployments } from './Deployments';
import { Testing } from './Testing';
import { NewAPI, Apis } from './Apis';
import { Informations } from './Informations';
import { Dashboard } from './Dashboard';
import { ClientEditor, Clients } from './Clients';
import { ServiceApiKeysPage } from '../ServiceApiKeysPage';

const RouteWithProps = ({ component: Component, props: extraProps, ...rest }) => (
  <Route
    path={rest.path}
    exact={rest.exact}
    render={(routeProps) => (
      <Component
        {...routeProps}
        {...extraProps}
        {...rest}
        params={{ ...(routeProps.match.params || {}), ...(rest.params || {}) }}
      />
    )}
  />
);

export default function ApiEditor(props) {
  useEffect(() => {
    document.getElementById('otoroshi-toasts')?.remove();
  }, []);

  return (
    <div className="editor">
      <QueryClientProvider client={queryClient}>
        <SidebarComponent {...props} />

        <Switch>
          <RouteWithProps
            exact
            path="/apis/:apiId/apikeys/:taction/:titem"
            component={ServiceApiKeysPage}
            props={props}
          />
          <RouteWithProps
            exact
            path="/apis/:apiId/apikeys"
            component={ServiceApiKeysPage}
            props={props}
          />
          <RouteWithProps exact path="/apis/:apiId/actions" component={Actions} props={props} />
          <RouteWithProps
            exact
            path="/apis/:apiId/api-gateway"
            component={APIGateway}
            props={props}
          />
          <RouteWithProps exact path="/apis/:apiId/plans" component={Plans} props={props} />
          <RouteWithProps
            exact
            path="/apis/:apiId/plans/new"
            component={PlanEditor}
            props={props}
          />
          <RouteWithProps
            exact
            path="/apis/:apiId/plans/:planId/:action"
            component={PlanEditor}
            props={props}
          />
          <RouteWithProps exact path="/apis/:apiId/endpoints" component={Endpoints} props={props} />
          <RouteWithProps
            exact
            path="/apis/:apiId/endpoints/new"
            component={NewRoute}
            props={props}
          />
          <RouteWithProps
            exact
            path="/apis/:apiId/endpoints/:routeId/:action"
            component={RouteDesigner}
            props={props}
          />
          <RouteWithProps exact path="/apis/:apiId/clients" component={Clients} props={props} />
          <RouteWithProps
            exact
            path="/apis/:apiId/clients/new"
            component={ClientEditor}
            props={props}
          />
          <RouteWithProps
            exact
            path="/apis/:apiId/clients/:clientId/:action"
            component={ClientEditor}
            props={props}
          />
          <RouteWithProps
            exact
            path="/apis/:apiId/subscriptions"
            component={Subscriptions}
            props={props}
          />
          <RouteWithProps
            exact
            path="/apis/:apiId/subscriptions/:subscriptionId/:action"
            component={SubscriptionDesigner}
            props={props}
          />

          <RouteWithProps
            exact
            path="/apis/:apiId/plugin-chains"
            component={PluginChains}
            props={props}
          />
          <RouteWithProps
            exact
            path="/apis/:apiId/plugin-chains/new"
            component={NewPluginChains}
            props={props}
          />
          <RouteWithProps
            exact
            path="/apis/:apiId/plugin-chains/:flowId/designer"
            component={PluginChainsDesigner}
            props={props}
          />
          <RouteWithProps
            exact
            path="/apis/:apiId/plugin-chains/:flowId/:action"
            component={EditPluginChains}
            props={props}
          />
          <RouteWithProps exact path="/apis/:apiId/backends" component={Backends} props={props} />
          <RouteWithProps
            exact
            path="/apis/:apiId/backends/new"
            component={NewBackend}
            props={props}
          />
          <RouteWithProps
            exact
            path="/apis/:apiId/backends/:backendId/:action"
            component={EditBackend}
            props={props}
          />

          <RouteWithProps
            exact
            path="/apis/:apiId/http-client-settings"
            component={HttpClientSettings}
            props={props}
          />
          <RouteWithProps
            exact
            path="/apis/:apiId/http-client-settings/new"
            component={NewHttpClientSettings}
            props={props}
          />
          <RouteWithProps
            exact
            path="/apis/:apiId/http-client-settings/:httpClientSettingsId/:action"
            component={EditHttpClientSettings}
            props={props}
          />

          <RouteWithProps
            exact
            path="/apis/:apiId/deployments"
            component={Deployments}
            props={props}
          />
          <RouteWithProps
            exact
            path="/apis/:apiId/documentation"
            component={Documentation}
            props={props}
          />
          <RouteWithProps exact path="/apis/:apiId/testing" component={Testing} props={props} />
          <RouteWithProps exact path="/apis/:apiId/new" component={NewAPI} props={props} />
          <RouteWithProps
            exact
            path="/apis/:apiId/informations"
            component={Informations}
            props={props}
          />
          <RouteWithProps exact path="/apis" component={Apis} props={props} />
          <RouteWithProps exact path="/apis/:apiId" component={Dashboard} props={props} />
        </Switch>
      </QueryClientProvider>
    </div>
  );
}

// Re-exports for backward compatibility (Sidebar.js and Documentation.js import from here)
export { useDraftOfAPI } from './hooks';
export { DraftOnly, VersionBadge, VersionToggle } from './DraftOnly';
