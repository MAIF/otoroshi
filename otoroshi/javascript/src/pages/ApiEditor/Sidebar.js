import React, { useContext } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { createTooltip } from '../../tooltips';
import { SidebarContext } from '../../apps/BackOfficeApp';

const LINKS = (api) =>
    [
        {
            to: `/apis/${api.id}/informations`,
            icon: 'fa-file-alt',
            title: 'Informations',
            tab: 'informations',
            tooltip: { ...createTooltip(`Show informations tab`) },
        },
        {
            to: `/apis/${api.id}/playground`,
            icon: 'fa-play',
            title: 'API Playground',
            tab: 'playground',
            tooltip: { ...createTooltip(`Show playground tab`) },
        },
        {
            to: `/apis/${api.id}/deployments`,
            icon: 'fa-server',
            title: 'Deployments',
            tab: 'deployments',
            tooltip: { ...createTooltip(`Show deployments tab`) },
        }
    ].filter((link) => !link.enabled);

export default ({ api }) => {
    const location = useLocation();
    const { openedSidebar } = useContext(SidebarContext);

    const currentTab = location.pathname.split('/').slice(-1)[0];

    const isActive = (tab) => {
        const params = new URLSearchParams(window.location.search);
        const queryTab = params.get('tab');

        return currentTab === tab || queryTab === tab ? 'active' : '';
    };

    if (location.pathname.endsWith('/new') || !api) return null;

    return (
        <div
            className="d-flex"
            style={{
                padding: openedSidebar ? 'inherit' : '12px 0 6px',
            }}>
            <ul className="nav flex-column nav-sidebar">
                <li className={`nav-item mb-3 ${openedSidebar ? 'nav-item--open' : ''}`} key="APIs">
                    <Link
                        to={`/apis`}
                        {...createTooltip(`apis - All your apis`)}
                        className={`d-flex align-items-center nav-link ${openedSidebar ? 'ms-3' : ''} m-0`}>
                        <div style={{ width: '20px' }} className="d-flex justify-content-center">
                            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" strokeWidth={1.5} stroke="currentColor" className="size-6">
                                <path strokeLinecap="round" strokeLinejoin="round" d="M9.53 16.122a3 3 0 0 0-5.78 1.128 2.25 2.25 0 0 1-2.4 2.245 4.5 4.5 0 0 0 8.4-2.245c0-.399-.078-.78-.22-1.128Zm0 0a15.998 15.998 0 0 0 3.388-1.62m-5.043-.025a15.994 15.994 0 0 1 1.622-3.395m3.42 3.42a15.995 15.995 0 0 0 4.764-4.648l3.876-5.814a1.151 1.151 0 0 0-1.597-1.597L14.146 6.32a15.996 15.996 0 0 0-4.649 4.763m3.42 3.42a6.776 6.776 0 0 0-3.42-3.42" />
                            </svg>

                        </div>
                        <div className="title"> {openedSidebar ? 'APIs' : ''}</div>
                    </Link>
                </li>
                {openedSidebar && <p className="sidebar-title mt-3">General</p>}
                {LINKS(api).map(({ to, icon, title, tooltip, tab }) => (
                    <li className={`nav-item ${openedSidebar ? 'nav-item--open' : ''}`} key={title}>
                        <Link
                            to={to}
                            {...(tooltip || {})}
                            className={`d-flex align-items-center nav-link ${isActive(tab)} ${openedSidebar ? 'ms-3' : ''
                                } m-0 ${isActive(tab)}`}>
                            <div style={{ width: '20px' }} className="d-flex justify-content-center">
                                <i className={`fas ${icon}`} />
                            </div>
                            <div className="title"> {openedSidebar ? title : ''}</div>
                        </Link>
                    </li>
                ))}
                {openedSidebar && <p className="sidebar-title mt-3">Monitoring</p>}
            </ul>
        </div>
    );
};
