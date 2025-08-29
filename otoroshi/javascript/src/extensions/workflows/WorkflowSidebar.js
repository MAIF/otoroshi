import React, { useContext } from "react";
import { Link, useLocation } from "react-router-dom";
import { SidebarContext } from '../../apps/BackOfficeApp';
import { createTooltip } from "../../tooltips";

const LINKS = (id) =>
    [
        {
            to: `/extensions/workflows/${id}/designer`,
            icon: 'fa-pencil-ruler',
            title: 'Overview',
            tooltip: { ...createTooltip(`Show overview tab`) },
            isActive: (pathname) => pathname.endsWith('designer')
        },
        {
            to: `/extensions/workflows/edit/${id}`,
            icon: 'fa-file-alt',
            title: 'Informations',
            tooltip: { ...createTooltip(`Show information tab`) },
            isActive: (pathname) => pathname.includes('edit')
        },
        {
            to: `/extensions/workflows/${id}/functions`,
            icon: 'fa-code',
            title: 'Functions',
            tooltip: { ...createTooltip(`Show functions tab`) },
            isActive: (pathname) => pathname.endsWith('functions')
        },
        {
            to: `/extensions/workflows/${id}/sessions`,
            icon: 'fa-arrows-rotate',
            title: 'Sessions',
            tooltip: { ...createTooltip(`Show sessions tab`) },
            isActive: (pathname) => pathname.endsWith('sessions')
        },
    ].filter((link) => !link.enabled);


export const WorkflowSidebar = ({ workflow }) => {
    const location = useLocation();

    const { openedSidebar } = useContext(SidebarContext);

    if (location.pathname.endsWith('/new') && !location.pathname.endsWith('functions/new')) return null;

    return (
        <div
            className="d-flex"
            style={{
                padding: openedSidebar ? 'inherit' : '12px 0 6px',
            }}
        >
            <ul className="nav flex-column nav-sidebar">
                {openedSidebar && <p className="sidebar-title">Shortcuts</p>}
                <li className={`nav-item mb-3 ${openedSidebar ? 'nav-item--open' : ''}`} key="Workflows">
                    <Link
                        to={`/extensions/workflows`}
                        {...createTooltip(`All your workflows`)}
                        className={`d-flex align-items-center nav-link ${openedSidebar ? 'ms-3' : ''} m-0`}>
                        <div style={{ width: '20px' }} className="d-flex justify-content-center">
                            <i className="fas fa-cubes" />
                        </div>
                        <div className="title"> {openedSidebar ? 'Workflows' : ''}</div>
                    </Link>
                </li>
                {openedSidebar && <p className="sidebar-title">Workflow</p>}
                {LINKS(workflow.id).map(({ to, icon, title, tooltip, isActive }) => {
                    return (
                        <li className={`nav-item ${openedSidebar ? 'nav-item--open' : ''}`} key={title}>
                            <Link
                                to={to}
                                {...(tooltip || {})}
                                className={`d-flex align-items-center nav-link ${isActive(location.pathname) ? 'active' : ''} ${openedSidebar ? 'ms-3' : ''} m-0`}
                            >
                                <div style={{ width: '20px' }} className="d-flex justify-content-center">
                                    <i className={`fas ${icon}`} />
                                </div>
                                <div className="title"> {openedSidebar ? title : ''}</div>
                            </Link>
                        </li>
                    );
                })}
            </ul>
        </div>
    );
};