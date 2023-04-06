import React, { useContext } from 'react';
import { Link, useHistory, useLocation } from 'react-router-dom';
import { createTooltip } from '../../tooltips';
import { useEntityFromURI } from '../../util';
import { SidebarContext } from '../../apps/BackOfficeApp';

const LINKS = (entity, route) =>
  [
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
      icon: 'fa-lock',
      title: 'API Keys',
      tab: 'apikeys',
      tooltip: { ...createTooltip(`Manage all API keys that can access`) },
    },
  ].filter((link) => !link.enabled || link.enabled.includes(entity));

export default ({ route, setSidebarContent }) => {
  const history = useHistory();
  const entity = useEntityFromURI();
  const location = useLocation();
  const { openedSidebar } = useContext(SidebarContext);

  const currentTab = location.pathname.split('/').slice(-1)[0];
  const isActive = (tab) => {
    return currentTab === tab ? 'active' : '';
  };

  if (location.pathname.endsWith('/new')) return null;

  return (
    <div
      className="d-flex"
      style={{
        flexDirection: 'column',
        position: 'relative',
        borderBottom: `${!openedSidebar ? '1px solid #fff' : 'none'}`,
        padding: openedSidebar ? 'inherit' : '12px 0 6px'
      }}>
      <ul className="nav flex-column nav-sidebar">
        <li className="nav-item mb-1">
          <Link to={`/${entity.link}/${route.id}?tab=flow`} className='p-2 m-0'>
            <i className="fas fa-road" /> {openedSidebar ? route.name : ''}
          </Link>
        </li>
        {LINKS(entity.link, route).map(({ to, icon, title, tooltip, tab }) => (
          <li className={`nav-item ${openedSidebar ? 'nav-item--open' : ''}`} key={title}>
            <Link
              to={to} {...(tooltip || {})}
              className={`nav-link ${isActive(tab)} ${openedSidebar ? 'ms-3' : ''} p-2 m-0 ${isActive(tab)}`}>
              <i className={`fas ${icon}`} /> {openedSidebar ? title : ''}
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
};