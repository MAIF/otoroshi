import React, { useEffect, useState } from 'react';
import { Route, Switch, useLocation, useParams, useRouteMatch } from 'react-router-dom';
import { nextClient } from '../../services/BackOfficeServices';
import Designer from './Designer';
import { Informations } from './Informations';
import { TryIt } from './TryIt';
import Routes from './Routes';
import DesignerSidebar from './DesignerSidebar';

import { ServiceEventsPage } from '../ServiceEventsPage';
import { ServiceLiveStatsPage } from '../ServiceLiveStatsPage';
import { ServiceHealthPage } from '../ServiceHealthPage';
import { ServiceAnalyticsPage } from '../ServiceAnalyticsPage';
import { ServiceApiKeysPage } from '../ServiceApiKeysPage';

export default (props) => {
  const match = useRouteMatch();
  const { search } = useLocation();
  const query = new URLSearchParams(search).get('tab');

  useEffect(() => {
    props.setTitle('Routes');
  }, []);

  useEffect(() => {
    const value = null;
    if (query) {
      if (query === 'flow') {
        props.setTitle('Designer');
      }
      if (query === 'try-it') {
        props.setTitle('Test route');
      }
      if (query === 'informations') {
        props.setTitle('Informations');
      }
    }
  }, [search]);

  return (
    <Switch>
      <Route exact path={`${match.url}/:routeId/health`}    component={(p) => <ServiceHealthPage    setSidebarContent={props.setSidebarContent} setTitle={props.setTitle} {...p.match} />} />
      <Route exact path={`${match.url}/:routeId/analytics`} component={(p) => <ServiceAnalyticsPage setSidebarContent={props.setSidebarContent} setTitle={props.setTitle} {...p.match} />} />
      <Route exact path={`${match.url}/:routeId/apikeys`}   component={(p) => <ServiceApiKeysPage   setSidebarContent={props.setSidebarContent} setTitle={props.setTitle} {...p.match} />} />
      <Route exact path={`${match.url}/:routeId/stats`}     component={(p) => <ServiceLiveStatsPage setSidebarContent={props.setSidebarContent} setTitle={props.setTitle} {...p.match} />} />
      <Route exact path={`${match.url}/:routeId/events`}    component={(p) => <ServiceEventsPage    setSidebarContent={props.setSidebarContent} setTitle={props.setTitle} {...p.match} />} />
      <Route
        exact
        path={`${match.url}/:routeId`}
        component={() => {
          const p = useParams();
          const isCreation = p.routeId === 'new';
          const [value, setValue] = useState({});

          useEffect(() => {
            if (p.routeId === 'new') {
              nextClient.template(nextClient.ENTITIES.ROUTES).then(setValue);
            } else nextClient.fetch(nextClient.ENTITIES.ROUTES, p.routeId).then(setValue);
          }, [p.routeId]);

          useEffect(() => {
            if (value && value.id) props.setSidebarContent(<DesignerSidebar route={value} />);
          }, [value]);

          if (query) {
            if (query === 'flow' && !isCreation)
              return (
                <div className="designer row ps-3">
                  <Designer {...props} value={value} />
                </div>
              );

            if (query === 'try-it')
              return (
                <div className="designer row ps-3">
                  <TryIt route={value} />
                </div>
              );
          }

          return (
            <div className="designer row ps-3">
              <Informations {...props} isCreation={isCreation} value={value} />
            </div>
          );
        }}
      />
      <Route component={Routes} />
    </Switch>
  );
};

const NotImplementedYet = () => (
  <h2>Not implemented yet !</h2>
)
