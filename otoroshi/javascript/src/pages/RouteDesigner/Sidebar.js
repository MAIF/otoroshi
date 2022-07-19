import React from 'react';
import { Link, useHistory } from 'react-router-dom';
import { createTooltip } from '../../tooltips';
import { useEntityFromURI, useQuery } from '../../util';

const LINKS = (entity, route) =>
  [
    {
      to: `/${entity}/${route.id}/health`,
      icon: 'fa-heart',
      title: 'Health',
      tooltip: { ...createTooltip(`Show healthcheck report`) },
    },
    {
      to: `/${entity}/${route.id}/stats`,
      icon: 'fa-chart-bar',
      title: 'Live metrics',
      tooltip: { ...createTooltip(`Show live metrics report`) },
    },
    {
      to: `/${entity}/${route.id}/analytics`,
      icon: 'fa-signal',
      title: 'Analytics',
      tooltip: { ...createTooltip(`Show analytics report`) },
    },
    {
      to: `/${entity}/${route.id}/events`,
      icon: 'fa-list',
      title: 'Events',
      tooltip: { ...createTooltip(`Show raw events report`) },
    },
    {
      to: `/${entity}/${route.id}/apikeys`,
      icon: 'fa-lock',
      title: 'API Keys',
      tooltip: { ...createTooltip(`Manage all API keys that can access`) },
    },
  ].filter((link) => !link.enabled || link.enabled.includes(entity));

export default ({ route, setSidebarContent }) => {
  const query = useQuery();
  const history = useHistory();
  const entity = useEntityFromURI();

  const currentTab = query.get('tab');
  const isActive = (tab) => (currentTab === tab ? 'active' : '');

  return <div className="d-flex" style={{
    flex: 1,
    flexDirection: 'column'
  }}>
    <ul className="nav flex-column nav-sidebar" style={{ flex: 1 }}>
      <li
        className="nav-item"
        onClick={() => history.push(`/${entity.link}/${route.id}?tab=flow`)}
        style={{ cursor: 'pointer' }}>
        <h3>
          <span className="fas fa-road" /> {route.name}
        </h3>
      </li>
      {LINKS(entity.link, route).map(({ to, icon, title, tooltip, tab }) => (
        <li className="nav-item" key={title}>
          <Link to={to} {...(tooltip || {})} className={`nav-link ${isActive(tab)}`}>
            <i className={`fas ${icon}`} /> {title}
          </Link>
        </li>
      ))}
    </ul>
    <Link to="/routes" onClick={() => setSidebarContent(null)} style={{ textAlign: 'center' }}>
      <i className="fas fa-arrow-left me-3" />Back to routes
    </Link>
  </div>
};
