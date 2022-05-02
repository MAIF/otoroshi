import React, { useEffect, useState } from 'react';
import { Route, Switch, useLocation, useParams, useRouteMatch } from 'react-router-dom';
import { nextClient } from '../../services/BackOfficeServices';
import Designer from './Designer';
import { TryIt } from './TryIt';
import Services from '../RoutesDesigner/Services';
import Routes from './Routes'
import { Informations } from './Informations';
import DesignerSidebar from './Sidebar';

import { ServiceEventsPage } from '../ServiceEventsPage';
import { ServiceLiveStatsPage } from '../ServiceLiveStatsPage';
import { ServiceHealthPage } from '../ServiceHealthPage';
import { ServiceAnalyticsPage } from '../ServiceAnalyticsPage';
import { ServiceApiKeysPage } from '../ServiceApiKeysPage';
import { useEntityFromURI } from '../../util';

export default (props) => {
    const match = useRouteMatch();
    const { search } = useLocation();
    const entity = useEntityFromURI()
    const query = new URLSearchParams(search).get('tab');

    useEffect(() => {
        props.setTitle(entity.capitalize);
    }, []);

    useEffect(() => {
        if (query) {
            props.setTitle({
                flow: 'Designer',
                'try-it': 'Test routes',
                informations: 'Informations',
                routes: 'Routes'
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
                    const [value, setValue] = useState();

                    useEffect(() => {
                        if (p.routeId === 'new') {
                            nextClient.template(nextClient.ENTITIES[entity.fetchName]).then(setValue);
                        } else
                            nextClient.fetch(nextClient.ENTITIES[entity.fetchName], p.routeId).then(setValue);
                    }, [p.routeId]);

                    useEffect(() => {
                        if (value && value.id)
                            props.setSidebarContent(<DesignerSidebar route={value} />);
                    }, [value]);

                    const divs = [
                        { predicate: query && query === 'flow' && !isCreation, render: () => <Designer {...props} value={value} /> },
                        { predicate: query && query === 'try-it', render: () => <TryIt route={value} /> },
                        { predicate: query && query === 'routes', render: () => value && <Services service={value} /> }
                    ]

                    const component = divs.filter(p => p.predicate)

                    if (component.length > 0)
                        return <div className="designer row ps-3">
                            {component[0].render()}
                        </div>

                    return (
                        <div className="designer row ps-3">
                            <Informations {...props} isCreation={isCreation} value={value} setValue={setValue} />
                        </div>
                    );
                }}
            />
            <Route component={Routes} />
        </Switch>
    );
};
