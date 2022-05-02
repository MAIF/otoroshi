import React from 'react';
import { Link } from 'react-router-dom';
import { createTooltip } from '../../tooltips';
import { useEntityFromURI, useQuery } from '../../util';

const LINKS = (entity, route) => [
  { to: `/${entity}/${route.id}?tab=informations`, icon: 'fa-file-alt', title: 'Informations', tab: 'informations' },
  { to: `/${entity}/${route.id}?tab=routes`, icon: 'fa-road', title: 'Routes', tab: 'routes', enabled: ['unnamed'] },
  { to: `/${entity}/${route.id}?tab=flow`, icon: 'fa-pencil-ruler', title: 'Designer', tab: 'flow' },
  { to: `/${entity}/${route.id}?tab=try-it`, icon: 'fa-vials', title: 'Tester', tab: 'try-it' },
  { to: `/${entity}/${route.id}/health`, icon: 'fa-heart', title: 'Health', tooltip: { ...createTooltip(`Show healthcheck report`) } },
  { to: `/${entity}/${route.id}/stats`, icon: 'fa-chart-bar', title: 'Live metrics', tooltip: { ...createTooltip(`Show live metrics report`) } },
  { to: `/${entity}/${route.id}/analytics`, icon: 'fa-signal', title: 'Analytics', tooltip: { ...createTooltip(`Show analytics report`) } },
  { to: `/${entity}/${route.id}/events`, icon: 'fa-list', title: 'Events', tooltip: { ...createTooltip(`Show raw events report`) } },
  { to: `/${entity}/${route.id}/apikeys`, icon: 'fa-lock', title: 'API Keys', tooltip: { ...createTooltip(`Manage all API keys that can access`) } }
].filter(link => !link.enabled || link.enabled.includes(entity))

export default ({ route }) => {
  const query = useQuery()
  const currentTab = query.get('tab')
  const isActive = tab => currentTab === tab ? 'active' : '';

  const entity = useEntityFromURI()

  return (
    <ul className="nav flex-column nav-sidebar">
      <li className="nav-item">
        <h3>
          <span>
            <span className="fas fa-server" /> {route.name}
          </span>
        </h3>
      </li>
      {LINKS(entity.link, route).map(({ to, icon, title, tooltip, tab }) => (
        <li className="nav-item" key={title}>
          <Link
            to={to}
            {...(tooltip || {})}
            className={`nav-link ${isActive(tab)}`}>
            <i className={`fas ${icon}`} /> {title}
          </Link>
        </li>
      ))}
    </ul>
  );
}
