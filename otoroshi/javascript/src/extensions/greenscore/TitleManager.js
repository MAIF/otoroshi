import React, { useEffect } from 'react';
import PageTitle from '../../components/PageTitle';
import { useHistory, useLocation } from 'react-router-dom';

function Tab({ isActive, title, icon, to, fillBackground }) {
    const history = useHistory()

    return <div className="ms-2" style={{ minHeight: 40 }}>
        <button
            type="button"
            className="btn btn-sm d-flex align-items-center h-100"
            onClick={() => {
                if (window.location.href !== to)
                    history.replace({
                        pathname: to
                    });
            }}
            style={{
                borderRadius: 6,
                backgroundColor: fillBackground ? 'var(--color-primary)' : 'transparent',
                boxShadow: `0 0 0 1px ${isActive ? 'var(--color-primary,transparent)' : 'var(--bg-color_level3,transparent)'}`,
                color: 'var(--text)'
            }}>
            {icon && <i className={`fas fa-${icon} me-2`} style={{ fontSize: '1.33333em' }} />}
            {title}
        </button>
    </div >
}

export default function ManagerTitle({ }) {
    const location = useLocation();

    const editingGroup = location.pathname.startsWith('/extensions/green-score/groups/green-score');

    const isOnCreation = location.pathname.endsWith('new');

    return (
        <PageTitle
            style={{
                paddingBottom: 0,
                border: 0,
                margin: '3rem auto 2.5rem'
            }}
            className="container-sm"
            title={"Green score"}>
            {!editingGroup && !isOnCreation && <>
                <Tab title="Dashboard" icon="globe" to='/extensions/green-score' isActive={location.pathname === '/extensions/green-score'} />

                <Tab title="Groups" icon="users" to='/extensions/green-score/groups' isActive={location.pathname === '/extensions/green-score/groups'} />

                <Tab title="Add New Group" fillBackground to='/extensions/green-score/groups/new' />
            </>}
        </PageTitle>
    );
}