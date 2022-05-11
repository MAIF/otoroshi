import React, { useEffect, useState } from 'react';
import { Route, Switch, useHistory, useLocation, useParams, useRouteMatch } from 'react-router-dom';
import { nextClient } from '../../services/BackOfficeServices';
import Designer from './Designer';
import { TryIt } from './TryIt';
import RouteCompositions from './RouteComposition';
import Routes from './Routes'
import { Informations } from './Informations';
import DesignerSidebar from './Sidebar';

import { ServiceEventsPage } from '../ServiceEventsPage';
import { ServiceLiveStatsPage } from '../ServiceLiveStatsPage';
import { ServiceHealthPage } from '../ServiceHealthPage';
import { ServiceAnalyticsPage } from '../ServiceAnalyticsPage';
import { ServiceApiKeysPage } from '../ServiceApiKeysPage';
import { useEntityFromURI } from '../../util';

const Manager = ({ query, entity, ...props }) => {
    const p = useParams();
    const isCreation = p.routeId === 'new';
    const history = useHistory()

    const [value, setValue] = useState();
    const [saveButton, setSaveButton] = useState(null)

    useEffect(() => {
        if (p.routeId === 'new') {
            nextClient.template(nextClient.ENTITIES[entity.fetchName]).then(setValue);
        } else
            nextClient.fetch(nextClient.ENTITIES[entity.fetchName], p.routeId).then(setValue);
    }, [p.routeId]);

    useEffect(() => {
        if (value && value.id) {
            props.setSidebarContent(<DesignerSidebar route={value} />);

            props.setTitle(() => <div className='page-header d-flex align-item-center justify-content-between ms-0 mb-3'>
                <h4 className='flex' style={{ margin: 0 }}>
                    {{
                        flow: 'Designer',
                        'try-it': 'Test routes',
                        informations: 'Informations',
                        routes: 'Routes'
                    }[query]}
                </h4>
                <div className='d-flex align-item-center justify-content-between flex'>
                    {[
                        { to: `/${entity.link}/${value.id}?tab=informations`, icon: 'fa-file-alt', title: 'Informations', tab: 'informations' },
                        { to: `/${entity.link}/${value.id}?tab=routes`, icon: 'fa-road', title: 'Routes', tab: 'routes', enabled: ['route-compositions'] },
                        { to: `/${entity.link}/${value.id}?tab=flow`, icon: 'fa-pencil-ruler', title: 'Designer', tab: 'flow' },
                        { to: `/${entity.link}/${value.id}?tab=try-it`, icon: 'fa-vials', title: 'Tester', tab: 'try-it' },
                    ]
                        .filter(link => !link.enabled || link.enabled.includes(entity))
                        .map(({ to, icon, title, tooltip, tab }) => (
                            <button
                                onClick={() => {
                                    if (query !== tab)
                                        history.push(to)
                                }}
                                {...(tooltip || {})}
                                className="btn btn-sm toggle-form-buttons ms-2"
                                key={title}
                                style={{
                                    backgroundColor: tab === query ? '#f9b000' : '#494948',
                                    color: '#fff'
                                }}>
                                <i className={`fas ${icon}`} /> {title}
                            </button>
                        ))}
                    {saveButton}
                </div>
            </div>)
        }
    }, [value, saveButton]);

    const divs = [
        { predicate: query && query === 'flow' && !isCreation, render: () => <Designer {...props} value={value} setSaveButton={setSaveButton} /> },
        { predicate: query && query === 'try-it', render: () => <TryIt route={value} /> },
        { predicate: query && query === 'routes', render: () => value && <RouteCompositions service={value} /> }
    ]

    const component = divs.filter(p => p.predicate)

    if (component.length > 0)
        return <div className="designer row">
            {component[0].render()}
        </div>

    return (
        <div className="designer row ps-3">
            <Informations {...props} isCreation={isCreation} value={value} setValue={setValue} />
        </div>
    );
}

export default (props) => {
    const match = useRouteMatch();
    const { search } = useLocation();
    const entity = useEntityFromURI()
    const query = new URLSearchParams(search).get('tab');

    useEffect(() => {
        props.setTitle(entity.capitalize);

        patchStyle(true)

        return () => patchStyle(false)
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

    const patchStyle = applyPatch => {
        if (applyPatch) {
            document.getElementsByClassName('main')[0].classList.add('patch-main');
            [...document.getElementsByClassName('row')].map(r => r.classList.add('patch-row', 'g-0'))
        } else {
            document.getElementsByClassName('main')[0].classList.remove('patch-main');
            [...document.getElementsByClassName('row')].map(r => r.classList.remove('patch-row', 'g-0'))
        }
    }

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
                children={() => <Manager query={query} {...props} entity={entity} />}
            />
            <Route component={Routes} />
        </Switch>
    );
};
