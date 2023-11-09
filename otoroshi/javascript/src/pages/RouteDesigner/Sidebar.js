import React, { useContext } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { createTooltip } from '../../tooltips';
import { useEntityFromURI } from '../../util';
import { SidebarContext } from '../../apps/BackOfficeApp';

const LINKS = (entity, route) =>
  [
    {
      to: `/${entity}/${route.id}?tab=informations`,
      icon: 'fa-file-alt',
      title: 'Informations',
      tab: 'informations',
      tooltip: { ...createTooltip(`Show informations tab`) },
    },
    {
      to: `/${entity}/${route.id}?tab=flow`,
      icon: 'fa-pencil-ruler',
      title: 'Designer',
      tab: 'flow',
      tooltip: { ...createTooltip(`Show designer tab`) },
    },
    {
      to: `/${entity}/${route.id}?tab=flow&showTryIt=true`,
      icon: 'fa-vials',
      title: 'Tester',
      tab: 'showTryIt',
      tooltip: { ...createTooltip(`Show tester tab`) },
    },
    {
      to: `/${entity}/${route.id}/health`,
      icon: 'fa-heart',
      title: 'Health',
      tab: 'health',
      tooltip: { ...createTooltip(`Show healthcheck report`) },
    },
    {
      to: `/${entity}/${route.id}/stats`,
      icon: 'fa-chart-bar',
      title: 'Live metrics',
      tab: 'stats',
      tooltip: { ...createTooltip(`Show live metrics report`) },
    },
    {
      to: `/${entity}/${route.id}/analytics`,
      icon: 'fa-signal',
      title: 'Analytics',
      tab: 'analytics',
      tooltip: { ...createTooltip(`Show analytics report`) },
    },
    {
      to: `/${entity}/${route.id}/events`,
      icon: 'fa-list',
      title: 'Events',
      tab: 'events',
      tooltip: { ...createTooltip(`Show raw events report`) },
    },
    {
      to: `/${entity}/${route.id}/apikeys`,
      icon: 'fa-key',
      title: 'API Keys',
      tab: 'apikeys',
      tooltip: { ...createTooltip(`Manage all API keys that can access`) },
    },
  ].filter((link) => !link.enabled || link.enabled.includes(entity));

export default ({ route }) => {

  const entity = useEntityFromURI();
  const location = useLocation();
  const { openedSidebar } = useContext(SidebarContext);

  const currentTab = location.pathname.split('/').slice(-1)[0];
  const isActive = (tab) => {
    const params = new URLSearchParams(window.location.search)
    const onTryIt = window.location.search.includes('showTryIt') ? 'showTryIt' : '';
    const queryTab = params.get('tab');
    console.log(currentTab, tab, onTryIt, queryTab)

    if (onTryIt) {
      return onTryIt === tab ? 'active' : ''
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
      }}>
      <ul className="nav flex-column nav-sidebar">
        {openedSidebar && <p className="ps-2">Route</p>}
        {LINKS(entity.link, route).map(({ to, icon, title, tooltip, tab }) => (
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

        {openedSidebar && <p className="ps-2 mt-3">Extensions</p>}
        {Otoroshi.extensions()
          .flatMap((ext) => ext.routeDesignerTabs || [])
          //               visible: () => (item.visible ? item.visible(entity, value, isOnViewPlugins) : true),
          .map((item) => {
            const to = `/${entity.link}/${route.id}?tab=${item.id}`;
            const tab = "" // todo
            return <li className={`nav-item ${openedSidebar ? 'nav-item--open' : ''}`} key={item.id}>
              <Link
                to={to}
                className={`d-flex align-items-center nav-link ${isActive(tab)} ${openedSidebar ? 'ms-3' : ''
                  } m-0 ${isActive(tab)}`}>
                <div style={{ width: '20px' }} className="d-flex justify-content-center">
                  <i className={`fas ${item.icon}`} />
                </div>
                <div className="title"> {openedSidebar ? item.label : ''}</div>
              </Link>
            </li>
          })}
      </ul>
    </div>
  );
};