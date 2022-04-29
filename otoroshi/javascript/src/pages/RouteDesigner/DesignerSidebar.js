import React from 'react';
import { Link, useLocation } from 'react-router-dom';
import { createTooltip } from '../../tooltips';

export default ({ route }) => {
  const { pathname } = useLocation()
  const base = `/bo/dashboard/routes/${route.id}/`;
  const className = (part) => (`${base}${part}` === pathname ? 'active' : '');
  return (
    <ul className="nav flex-column nav-sidebar">
      <li className="nav-item">
        <h3>
          <span>
            <span className="fas fa-server" /> {route.name}
          </span>
        </h3>
      </li>
      <li className="nav-item">
        <Link to={`/routes/${route.id}?tab=informations`} className="nav-link">
          <i class="fas fa-file-alt" />  Informations
        </Link>
      </li>
      <li className="nav-item">
        <Link to={`/routes/${route.id}?tab=flow`} className="nav-link">
          <i class="fas fa-pencil-ruler" /> Designer
        </Link>
      </li>
      <li className="nav-item">
        <Link to={`/routes/${route.id}?tab=try-it`} className="nav-link">
          <i class="fas fa-vials" /> Tester
        </Link>
      </li>
      <li className="nav-item">
        <Link
          {...createTooltip(`Show healthcheck report for ${name}`)}
          to={`/routes/${route.id}/health`}
          className={`nav-link ${className('health')}`}>
          <i className="fas fa-heart" /> Health
        </Link>
      </li>
      <li>
        <Link
          to={`/routes/${route.id}/stats`}
          {...createTooltip(`Show live metrics report for ${name}`)}
          className={`nav-link ${className('stats')}`}>
          <i className="fas fa-chart-bar" /> Live metrics
        </Link>
      </li>
      <li className="nav-item">
        <Link
          to={`/routes/${route.id}/analytics`}
          {...createTooltip(`Show analytics report for ${name}`)}
          className={`nav-link ${className('analytics')}`}>
          <i className="fas fa-signal" /> Analytics
        </Link>
      </li>
      <li>
        <Link
          to={`/routes/${route.id}/events`}
          {...createTooltip(`Show raw events report for ${name}`)}
          className={`nav-link ${className('events')}`}>
          <i className="fas fa-list" /> Events
        </Link>
      </li>
      <li className="nav-item">
        <Link
          to={`/routes/${route.id}/apikeys`}
          {...createTooltip(`Manage all API keys that can access ${name}`)}
          className={`nav-link ${className('apikeys')}`}>
          <i className="fas fa-lock" /> API Keys
        </Link>
      </li>
    </ul>
  );
}
