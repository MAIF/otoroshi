import React, { useContext } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { createTooltip } from '../../tooltips';
import { SidebarContext } from '../../apps/BackOfficeApp';
import { signalVersion } from './VersionSignal';
import { useSignalValue } from 'signals-react-safe';
import { VersionToggle } from '.';

const LINKS = (id) =>
  [
    {
      to: `/apis/${id}`,
      icon: 'fa-file-alt',
      title: 'Overview',
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
      to: `/apis/${id}/api-gateway`,
      icon: 'fa-shield-halved',
      title: 'API Gateway',
      tab: 'api-gateway',
      tooltip: { ...createTooltip(`Show API Gateway tab`) },
    },
    {
      to: `/apis/${id}/actions`,
      icon: 'fa-bolt',
      title: 'Actions',
      tab: 'actions',
      tooltip: { ...createTooltip(`Show actions tab`) },
    },
    {
      to: `/apis/${id}/endpoints`,
      icon: 'fa-road',
      title: 'Endpoints',
      tab: 'endpoints',
      tooltip: { ...createTooltip(`Show endpoints tab`) },
    },
    {
      to: `/apis/${id}/plugin-chains`,
      icon: 'fa-project-diagram',
      title: 'Plugin chains',
      tab: 'plugin-chains',
      tooltip: { ...createTooltip(`Show plugin chains tab`) },
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
      to: `/apis/${id}/access-modes`,
      icon: 'fa-list',
      title: 'Access modes',
      tab: 'access-modes',
      tooltip: { ...createTooltip(`Show access modes tab`) },
    },
    {
      to: `/apis/${id}/subscriptions`,
      icon: 'fa-key',
      title: 'Subscriptions',
      tab: 'Subscriptions',
      tooltip: { ...createTooltip(`Show subscriptions tab`) },
    },
    {
      to: `/apis/${id}/plans`,
      icon: 'fa-layer-group',
      title: 'Plans',
      tab: 'plan',
      tooltip: { ...createTooltip(`Show plan tab`) },
    },
    {
      to: `/apis/${id}/deployments`,
      icon: 'fa-server',
      title: 'Deployments',
      tab: 'deployments',
      tooltip: { ...createTooltip(`Show deployments tab`) },
    },
    {
      to: `/apis/${id}/documentation`,
      icon: 'fa-file',
      title: 'Developer portal',
      tab: 'dev portal',
      tooltip: { ...createTooltip(`Show dev portal tab`) },
    },
    {
      to: `/apis/${id}/testing`,
      icon: 'fa-play',
      title: 'Testing',
      tab: 'testing',
      tooltip: { ...createTooltip(`Show testing tab`) },
    },
  ].filter((link) => !link.enabled);

export default (props) => {
  const location = useLocation();
  const { openedSidebar } = useContext(SidebarContext);

  const params = props.params;

  const currentTab = location.pathname.split('/')[3];
  const noneTabIsActive = !LINKS().find((r) => r.tab?.toLowerCase() === currentTab?.toLowerCase());

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
            className={`d-flex align-items-center nav-link ${openedSidebar ? 'ms-3' : ''} m-0`}
          >
            <div style={{ width: '20px' }} className="d-flex justify-content-center">
              <i className="fa fa-brush" />
            </div>
            <div className="d-flex align-items-center">
              {' '}
              {openedSidebar ? 'APIs' : ''}
            </div>
          </Link>
        </li>
        {!isOnApisHome && (
          <>
            {openedSidebar && (
              <p className="sidebar-title mt-3" aria-disabled={isOnNewAPIView}>
                General
              </p>
            )}
            <div className="me-1 my-2" aria-disabled={isOnNewAPIView}>
              {openedSidebar && version && version !== 'staging' && (
                <VersionToggle isDraft={version === 'Draft'} />
              )}
            </div>
            {LINKS(params.apiId).map(({ to, icon, title, tooltip, tab }) => (
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
                  className={`d-flex align-items-center nav-link ${isActive(tab)} ${openedSidebar ? 'ms-3' : ''
                    } m-0 ${isActive(tab)}`}
                >
                  <div style={{ width: '20px' }} className="d-flex justify-content-center">
                    <i className={`fas ${icon}`} />
                  </div>
                  <div className="title"> {openedSidebar ? title : ''}</div>
                </Link>
              </li>
            ))}
            {/* {openedSidebar && <p className="sidebar-title mt-3">Monitoring</p>} */}
          </>
        )}
      </ul>
    </div>
  );
};
