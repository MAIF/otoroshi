import React, { useEffect, useState } from 'react'
import { Route, Switch, useLocation, useParams, useRouteMatch } from 'react-router-dom'
import NextSidebar from '../../components/NextSidebar'
import { nextClient } from '../../services/BackOfficeServices'
import Designer from './Designer'
import { Informations } from './Informations'
import { TryIt } from './TryIt'
import Routes from './Routes'

export default (props) => {
    const match = useRouteMatch()

    useEffect(() => {
        props.setTitle("Routes designer")
    }, [])

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
                        nextClient.template(nextClient.ENTITIES.ROUTES)
                            .then(setValue)
                    else
                        nextClient.fetch(nextClient.ENTITIES.ROUTES, p.routeId)
                            .then(setValue)
                }, [p.routeId])

                console.log(value)

                return <div style={{ padding: '7px 15px 0 0' }} className="row">
                    <NextSidebar isCreation={isCreation} entity={nextClient.ENTITIES.ROUTES} />
                    <div className='col-sm-11' style={{ paddingLeft: 0 }}>
                        {
                            (query && query === 'flow' && !isCreation) ?
                                <Designer {...props} value={value} /> :
                                (query && query === 'informations') ?
                                    <Informations {...props} isCreation={isCreation} value={value} /> :
                                    <TryIt route={value} />
                        }
                    </div>
                </div>
            }} />
        <Route component={Routes} />
    </Switch>
}