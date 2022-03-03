import React from 'react'
import { Link, useHistory, useLocation } from 'react-router-dom';

export default ({ isCreation, entity }) => {
    const history = useHistory()
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
        }} onClick={() => history.push(`/${entity}`)}>
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