import React, { useEffect, useState } from 'react'
import { Route, Switch, useHistory, useLocation, useParams, useRouteMatch, Link } from 'react-router-dom'
import { getRouteTemplate, fetchRoute } from '../../services/BackOfficeServices'
import Designer from './Designer'
import { Informations } from './Informations'
import Routes from './Routes'

export default (props) => {
    const history = useHistory()
    const match = useRouteMatch()

    useEffect(() => {
        props.setTitle("Routes designer")
    }, [])

    const Sidebar = ({ isCreation }) => {
        const { search, pathname } = useLocation()

        const query = new URLSearchParams(search).get("tab")
        const isCogTab = !query || query === 'informations';

        return <div className='col-sm-1 designer-sidebar'>
            <button className='btn btn-sm' style={{
                backgroundColor: '#494948',
                borderRadius: '50%',
                height: '42px',
                width: '42px',
                display: 'flex',
                alignItems: 'center',
                marginRight: 0
            }} onClick={() => history.push('/routes')}>
                <i className='fas fa-arrow-left fa-2x' style={{ color: '#f9b000' }} />
            </button>
            {!isCreation && <div className='designer-sidebar' style={{ marginTop: '24px' }}>
                <Link className={`tab ${!isCogTab ? 'tab-selected' : ''}`} to={{
                    pathname,
                    search: '?tab=flow'
                }}>
                    <i className='fas fa-stream fa-lg' />
                </Link>
                <Link className={`tab ${isCogTab ? 'tab-selected' : ''}`} to={{
                    pathname,
                    search: '?tab=informations'
                }}>
                    <i className='fas fa-cog fa-lg' style={{ paddingRight: 0 }} />
                </Link>
            </div>}
        </div>
    }

    return <Switch>
        <Route exact
            path={`${match.url}/:routeId`}
            component={() => {
                const p = useParams()
                const { search } = useLocation()
                const query = new URLSearchParams(search).get("tab")
                const isCreation = p.routeId === 'new'

                const [value, setValue] = useState({})

                useEffect(() => {
                    if (p.routeId === 'new')
                        getRouteTemplate()
                            .then(setValue)
                    else
                        fetchRoute(p.routeId)
                            .then(setValue)
                }, [p.routeId])

                return <div style={{ padding: '6px' }}>
                    <Sidebar isCreation={isCreation} />
                    <div className='col-sm-11' style={{ paddingLeft: 0 }}>
                        {
                            (query && query === 'flow' && !isCreation) ?
                                <Designer {...props} value={value} /> :
                                <Informations {...props} isCreation={isCreation} value={value} />
                        }
                    </div>
                </div>
            }} />
        <Route component={Routes} />
    </Switch>
}