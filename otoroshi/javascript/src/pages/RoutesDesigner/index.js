import React, { useEffect, useState } from 'react';
import { Route, Switch, useLocation, useParams, useRouteMatch } from 'react-router-dom';
import { nextClient } from '../../services/BackOfficeServices';
import Designer from '../RouteDesigner/Designer';
import { TryIt } from '../RouteDesigner/TryIt';
import Services from './Services';
import Sidebar from './Sidebar';
import Routes from './Routes'

import { ServiceEventsPage } from '../ServiceEventsPage';
import { ServiceLiveStatsPage } from '../ServiceLiveStatsPage';
import { ServiceHealthPage } from '../ServiceHealthPage';
import { ServiceAnalyticsPage } from '../ServiceAnalyticsPage';
import { ServiceApiKeysPage } from '../ServiceApiKeysPage';
import { Informations } from './Informations';

export const RoutesPage = (props) => {
    const match = useRouteMatch();
    const { search } = useLocation();
    const query = new URLSearchParams(search).get('tab');

    useEffect(() => {
        props.setTitle('Services');
    }, []);

    useEffect(() => {
        const value = null;
        if (query) {
            props.setTitle({
                flow: 'Designer',
                'try-it': 'Test routes',
                informations: 'Informations'
            }[query]);
        }
    }, [search]);

    return (
        <Switch>
            {[
                { path: `${match.url}/:routeId/health`, component: ServiceHealthPage },
                { path: `${match.url}/:routeId/analytics`, component: ServiceAnalyticsPage },
                { path: `${match.url}/:routeId/apikeys`, component: ServiceApiKeysPage },
                { path: `${match.url}/:routeId/stats`, component: ServiceLiveStatsPage },
                { path: `${match.url}/:routeId/events`, component: ServiceEventsPage }
            ].map((route, i) => {
                const Component = route.component;
                <Route
                    exact
                    key={`route${i}`}
                    path={route.path}
                    component={(p) => <Component setSidebarContent={props.setSidebarContent} setTitle={props.setTitle} {...p.match} />} />
            })}
            <Route
                exact
                path={`${match.url}/:routeId`}
                component={() => {
                    const p = useParams();
                    const isCreation = p.routeId === 'new';
                    const [value, setValue] = useState({});

                    useEffect(() => {
                        if (p.routeId === 'new') {
                            nextClient.template(nextClient.ENTITIES.SERVICES).then(setValue);
                        } else nextClient.fetch(nextClient.ENTITIES.SERVICES, p.routeId).then(setValue);
                    }, [p.routeId]);

                    useEffect(() => {
                        if (value && value.id)
                            props.setSidebarContent(<Sidebar route={value} />);
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

                        if (query === 'routes')
                            return (
                                <div className="designer row ps-3">
                                    <Routes service={value} />
                                </div>
                            );
                    }

                    return (
                        <div className="designer row ps-3">
                            <Informations {...props} isCreation={isCreation} value={value} setValue={setValue} />
                        </div>
                    );
                }}
            />
            <Route component={Services} />
        </Switch>
    );
};
