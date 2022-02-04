import React, { useEffect } from 'react'
import { Route, Switch, useHistory, useParams, useRouteMatch } from 'react-router-dom'
import Designer from './Designer'
import Routes from './Routes'

export default (props) => {
    const history = useHistory()
    const params = useParams()
    const match = useRouteMatch()

    useEffect(() => {
        props.setTitle("Routes designer")
    }, [])

    const Sidebar = () => <div className='col-sm-1'>
        <button className='btn btn-sm' style={{
            backgroundColor: '#494948',
            borderRadius: '50%',
            height: '42px',
            width: '42px',
            display: 'flex',
            alignItems: 'center'
        }} onClick={history.goBack}>
            <i className='fas fa-arrow-left fa-2x' style={{ color: '#f9b000' }} />
        </button>
    </div>

    return <Switch>
        <Route exact
            path={`${match.url}/:routeId`}
            component={() => {
                const p = useParams()
                return <div className='row'>
                    <Sidebar />
                    <div className='col-sm-11' style={{ paddingLeft: 0 }}>
                        <Designer {...props} lineId={params.lineId}
                            onCreation={p.routeId === 'new'}
                        />
                    </div>
                </div>
            }} />
        <Route component={Routes} />
    </Switch>
}