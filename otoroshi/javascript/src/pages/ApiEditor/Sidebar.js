import React, { useContext } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { createTooltip } from '../../tooltips';
import { SidebarContext } from '../../apps/BackOfficeApp';
import { signalVersion } from './VersionSignal';
import { useSignalValue } from 'signals-react-safe';
import { VersionToggle } from '.';

const LINK_GROUPS = (id) => [
  {
    label: 'General',
    links: [
      {
        to: `/apis/${id}`,
        icon: 'fa-file-alt',
        title: 'Dashboard',
        tab: 'overview',
        tooltip: { ...createTooltip(`Show overview tab`) },
      },
      {
        to: `/apis/${id}/informations`,
        icon: 'fa-file-alt',
        title: 'Informations',
        tab: 'informations',
        tooltip: { ...createTooltip(`Show informations tab`) },
      },
      {
        to: `/apis/${id}/actions`,
        icon: 'fa-bolt',
        title: 'Actions',
        tab: 'actions',
        tooltip: { ...createTooltip(`Show actions tab`) },
        isProd: true
      }
    ],
  },
  {
    label: 'API Gateway',
    links: [
      {
        to: `/apis/${id}/api-gateway`,
        icon: 'fa-cog',
        title: 'Exposition',
        tab: 'api-gateway',
        tooltip: { ...createTooltip(`Show exposition tab`) },
      },
      {
        to: `/apis/${id}/endpoints`,
        icon: 'fa-road',
        title: 'Endpoints',
        tab: 'endpoints',
        tooltip: { ...createTooltip(`Show endpoints tab`) },
      },
      {
        to: `/apis/${id}/backends`,
        icon: 'fa-server',
        title: 'Backends',
        tab: 'backends',
        tooltip: { ...createTooltip(`Show backends tab`) },
      },
      {
        to: `/apis/${id}/http-client-settings`,
        icon: 'fa-gamepad',
        title: 'HTTP client settings',
        tab: 'http-client-settings',
        tooltip: { ...createTooltip(`Show http client settings tab`) },
      },
      {
        to: `/apis/${id}/plugin-chains`,
        icon: 'fa-project-diagram',
        title: 'Plugin chains',
        tab: 'plugin-chains',
        tooltip: { ...createTooltip(`Show plugin chains tab`) },
      },
    ],
  },
  {
    label: 'API Management',
    links: [
      {
        to: `/apis/${id}/plans`,
        icon: 'fa-layer-group',
        title: 'Plans',
        tab: 'plan',
        tooltip: { ...createTooltip(`Show plan tab`) },
      },
      {
        to: `/apis/${id}/clients`,
        icon: 'fa-users',
        title: 'Clients',
        tab: 'Clients',
        tooltip: { ...createTooltip(`Show clients tab`) },
      },
      {
        to: `/apis/${id}/subscriptions`,
        icon: 'fa-key',
        title: 'Subscriptions',
        tab: 'Subscriptions',
        tooltip: { ...createTooltip(`Show subscriptions tab`) },
      },
      {
        to: `/apis/${id}/apikeys`,
        icon: 'fa-key',
        title: 'API Keys',
        tab: 'apikeys',
        tooltip: { ...createTooltip(`Manage all API keys that can access`) },
      },
      {
        to: `/apis/${id}/documentation`,
        icon: 'fa-file',
        title: 'Documentation',
        tab: 'dev portal',
        tooltip: { ...createTooltip(`Show dev portal tab`) },
      },
    ],
  },
  {
    label: 'Operations',
    links: [
      {
        to: `/apis/${id}/deployments`,
        icon: 'fa-server',
        title: 'Deployments',
        tab: 'deployments',
        tooltip: { ...createTooltip(`Show deployments tab`) },
      },
      {
        to: `/apis/${id}/testing`,
        icon: 'fa-play',
        title: 'Testing',
        tab: 'testing',
        tooltip: { ...createTooltip(`Show testing tab`) },
      },
    ],
  },
];

const ALL_LINKS = (id) => LINK_GROUPS(id).flatMap((g) => g.links);

export default (props) => {
  const location = useLocation();
  const { openedSidebar } = useContext(SidebarContext);

  const params = props.params;

  const currentTab = location.pathname.split('/')[3];
  const noneTabIsActive = !ALL_LINKS().find((r) => r.tab?.toLowerCase() === currentTab?.toLowerCase());

  const isOnApisHome = location.pathname.endsWith('/apis');
  const isOnNewAPIView = location.pathname.endsWith(`${params.apiId}/new`);
  const version = useSignalValue(signalVersion);

  const isActive = (tab) => {
    if (tab.toLowerCase() === 'overview' && noneTabIsActive) return 'active';

    return currentTab?.toLowerCase() === tab.toLowerCase() ? 'active' : null;
  };

  return (
    <div
      style={{
        padding: openedSidebar ? 'inherit' : '12px 0 6px',
      }}
    >
      {openedSidebar && <p className="sidebar-title">Shortcuts</p>}
      <ul className="nav flex-column nav-sidebar">
        <li className={`nav-item mb-3 ${openedSidebar ? 'nav-item--open' : ''}`} key="APIs">
          <Link
            to={`/apis`}
            {...createTooltip(`apis - All your apis`)}
            className={`d-flex align-items-center nav-link ${openedSidebar ? 'ms-1' : ''} m-0`}
          >
            <div style={{ width: '20px' }} className="d-flex justify-content-center">
              <i className="fa fa-brush" />
            </div>
            <div className="d-flex align-items-center">
              {' '}
              {openedSidebar ? 'APIs' : ''}
              <span className="badge bg-xs bg-warning ms-2">ALPHA</span>
            </div>
          </Link>
        </li>
        {!isOnApisHome && (
          <>
            {openedSidebar && version && version !== 'staging' && <div className="me-1 my-2" aria-disabled={isOnNewAPIView}>
              <VersionToggle isDraft={version === 'Draft'} />
            </div>}
            {LINK_GROUPS(params.apiId).map((group) => (
              <React.Fragment key={group.label}>
                {openedSidebar && (
                  <p className="sidebar-title mt-3" aria-disabled={isOnNewAPIView}>
                    {group.label}
                  </p>
                )}
                {group.links.map(({ to, icon, title, tooltip, tab, isProd }) => (
                  <li
                    className={`nav-item ${openedSidebar ? 'nav-item--open' : ''}`}
                    key={title}
                    aria-disabled={isOnNewAPIView}
                  >
                    <Link
                      to={{
                        pathname: to,
                        search: location.search,
                      }}
                      {...(tooltip || {})}
                      className={`d-flex align-items-center nav-link ${isActive(tab)} ${openedSidebar ? 'ms-1' : ''
                        } m-0 ${isActive(tab)}`}
                    >
                      <div style={{ width: '20px' }} className="d-flex justify-content-center">
                        <i className={`fas ${icon}`} />
                      </div>
                      <div className="title"> {openedSidebar ? title : ''}</div>

                      {isProd && openedSidebar && <span className='dashboard-version-toggle-indicator dashboard-version-toggle-indicator--prod ms-auto' style={{
                        color: 'var(--text) !important'
                      }}>
                        PROD
                      </span>}
                    </Link>
                  </li>
                ))}
              </React.Fragment>
            ))}
          </>
        )}
      </ul>
    </div>
  );
};
