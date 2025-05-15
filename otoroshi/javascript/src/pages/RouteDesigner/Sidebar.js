import React, { useContext } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { createTooltip } from '../../tooltips';
import { SidebarContext } from '../../apps/BackOfficeApp';

const LINKS = (route) =>
  [
    {
      to: `/routes/${route.id}?tab=informations`,
      icon: 'fa-file-alt',
      title: 'Informations',
      tab: 'informations',
      tooltip: { ...createTooltip(`Show informations tab`) },
    },
    {
      to: `/routes/${route.id}?tab=flow`,
      icon: 'fa-pencil-ruler',
      title: 'Designer',
      tab: 'flow',
      tooltip: { ...createTooltip(`Show designer tab`) },
    },
    {
      to: `/routes/${route.id}?tab=flow&showTryIt=true`,
      icon: 'fa-vials',
      title: 'Tester',
      tab: 'showTryIt',
      tooltip: { ...createTooltip(`Show tester tab`) },
    },
    {
      to: `/routes/${route.id}/health`,
      icon: 'fa-heart',
      title: 'Health',
      tab: 'health',
      tooltip: { ...createTooltip(`Show healthcheck report`) },
    },
    {
      to: `/routes/${route.id}/stats`,
      icon: 'fa-chart-bar',
      title: 'Live metrics',
      tab: 'stats',
      tooltip: { ...createTooltip(`Show live metrics report`) },
    },
    {
      to: `/routes/${route.id}/analytics`,
      icon: 'fa-signal',
      title: 'Analytics',
      tab: 'analytics',
      tooltip: { ...createTooltip(`Show analytics report`) },
    },
    {
      to: `/routes/${route.id}/events`,
      icon: 'fa-list',
      title: 'Events',
      tab: 'events',
      tooltip: { ...createTooltip(`Show raw events report`) },
    },
    {
      to: `/routes/${route.id}/apikeys`,
      icon: 'fa-key',
      title: 'API Keys',
      tab: 'apikeys',
      tooltip: { ...createTooltip(`Manage all API keys that can access`) },
    },
  ].filter((link) => !link.enabled);

export default ({ route }) => {
  const location = useLocation();
  const { search } = useLocation();

  const { openedSidebar } = useContext(SidebarContext);

  const currentTab = location.pathname.split('/').slice(-1)[0];
  const isActive = (tab) => {
    const params = new URLSearchParams(window.location.search);
    const onTryIt = window.location.search.includes('showTryIt') ? 'showTryIt' : '';
    const queryTab = params.get('tab');

    if (onTryIt) {
      return onTryIt === tab ? 'active' : '';
    } else {
      return currentTab === tab || queryTab === tab ? 'active' : '';
    }
  };

  if (location.pathname.endsWith('/new')) return null;


  return (
    <div
      className="d-flex"
      style={{
        padding: openedSidebar ? 'inherit' : '12px 0 6px',
      }}
    >
      <ul className="nav flex-column nav-sidebar">
        <li className={`nav-item mb-3 ${openedSidebar ? 'nav-item--open' : ''}`} key="Routes">
          <Link
            to={`/routes`}
            {...createTooltip(`routes - All your routes`)}
            className={`d-flex align-items-center nav-link ${openedSidebar ? 'ms-3' : ''} m-0`}
          >
            <div style={{ width: '20px' }} className="d-flex justify-content-center">
              <i className={`fas fa-road`} />
            </div>
            <div className="title"> {openedSidebar ? 'Routes' : ''}</div>
          </Link>
        </li>
        {openedSidebar && <p className="sidebar-title">Route</p>}
        {LINKS(route).map(({ to, icon, title, tooltip, tab }) => {
          const queryParams = new URLSearchParams(window.location.search)
          const queryVersion = queryParams.get('version')

          return <li className={`nav-item ${openedSidebar ? 'nav-item--open' : ''}`} key={title}>
            <Link
              to={to.includes("?") ? `${to}&version=${queryVersion}` : `${to}?version=${queryVersion}`}
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
        })}

        {openedSidebar && <p className="sidebar-title mt-3">Extensions</p>}
        {Otoroshi.extensions()
          .flatMap((ext) => ext.routeDesignerTabs || [])
          .map((item) => {
            const queryParams = new URLSearchParams(window.location.search)
            const queryVersion = queryParams.get('version')

            const to = `/routes/${route.id}?tab=${item.id}&version=${queryVersion}`;
            const tab = ''; // todo
            return (
              <li className={`nav-item ${openedSidebar ? 'nav-item--open' : ''}`} key={item.id}>
                <Link
                  to={to}
                  className={`d-flex align-items-center nav-link ${isActive(tab)} ${openedSidebar ? 'ms-3' : ''
                    } m-0 ${isActive(tab)}`}
                >
                  <div style={{ width: '20px' }} className="d-flex justify-content-center">
                    <i className={`fas ${item.icon}`} />
                  </div>
                  <div className="title"> {openedSidebar ? item.label : ''}</div>
                </Link>
              </li>
            );
          })}
      </ul>
    </div>
  );
};
