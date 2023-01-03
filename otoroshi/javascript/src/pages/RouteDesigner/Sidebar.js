import React from 'react';
import { Link, useHistory, useLocation } from 'react-router-dom';
import { createTooltip } from '../../tooltips';
import { useEntityFromURI } from '../../util';

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

  const currentTab = location.pathname.split('/').slice(-1)[0];
  const isActive = (tab) => {
    return currentTab === tab ? 'active' : '';
  };

  if (location.pathname.endsWith('/new')) return null;

  return (
      <ul className="nav flex-column sidebar-bloc">
        <li
          className="nav-item mb-1"
          onClick={() => history.push(`/${entity.link}/${route.id}?tab=flow`)}
          style={{ cursor: 'pointer' }}>
          <h3>
            <span className="fas fa-road" /> {route.name}
          </h3>
        </li>
        {LINKS(entity.link, route).map(({ to, icon, title, tooltip, tab }) => (
          <li className="nav-item" key={title}>
            <Link to={to} {...(tooltip || {})} className={`nav-link ${isActive(tab)}`}>
              <h3 className={`ms-3 p-2 m-0 ${isActive(tab)}`}>
                <i className={`fas ${icon}`} /> {title}
              </h3>
            </Link>
          </li>
        ))}
      </ul>
  );
};
