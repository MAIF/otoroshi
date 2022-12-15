import React, { Component } from 'react';
import PropTypes from 'prop-types';
import { Link } from 'react-router-dom';
import { createTooltip } from '../tooltips';

/*export const ServiceSidebar = ({ env, serviceId, name = 'Service', nolink }) => (
  <ul className="nav nav-sidebar no-margin-left">
    <li>
      <h3 style={{ marginTop: 0 }}>
        {nolink && <span><span className="fas fa-cube" /> {name}</span>}
        {!nolink && <Link to={`/lines/${env}/services/${serviceId}`} style={{ color: '#f9b000' }}>
          <span className="fas fa-cube" /> {name}
        </Link>}
      </h3>
    </li>
    <li>
      <Link to={`/lines/${env}/services/${serviceId}/health`}><i className="fas fa-heart" /> Health</Link>
    </li>
    <li>
      <Link to={`/lines/${env}/services/${serviceId}/stats`}><i className="fas fa-chart-bar" /> Live stats</Link>
    </li>
    <li>
      <Link to={`/lines/${env}/services/${serviceId}/analytics`}><i className="fas fa-signal" /> Analytics</Link>
    </li>
    <li>
      <Link to={`/lines/${env}/services/${serviceId}/events`}><i className="fas fa-list" /> Events</Link>
    </li>
    <li>
      <Link to={`/lines/${env}/services/${serviceId}/apikeys`}><i className="fas fa-lock" /> API Keys</Link>
    </li>
    <li>
      <Link to={`/lines/${env}/services/${serviceId}/doc`}><i className="fas fa-folder" /> Documentation</Link>
    </li>
  </ul>
);*/

export class ServiceSidebar extends Component {
  render() {
    const { env, serviceId, name = 'Service' } = this.props;
    const pathname = window.location.pathname;
    const base = `/bo/dashboard/lines/${env}/services/${serviceId}/`;
    const className = (part) => (`${base}${part}` === pathname ? 'active' : '');
    return (
      <ul className="nav flex-column nav-sidebar no-margin-left">
        <li className="nav-item">
          <Link
            to={`/lines/${env}/services/${serviceId}`}
            style={{ color: '#f9b000' }}
            className="active">
            <h3 {...createTooltip(`Back to the service descriptor of ${name}`)} className="p-2 m-0">
              <i className="fas fa-cube" /> {name}
            </h3>
          </Link>
        </li>
        {!this.props.noSideMenu && (
          <li className="nav-item ms-3">
            <Link
              {...createTooltip(`Show healthcheck report for ${name}`)}
              to={`/lines/${env}/services/${serviceId}/health`}
              className={`nav-link ${className('health')}`}>
              <h3 className="p-2 m-0">
                <i className="fas fa-heart" /> Health
              </h3>
            </Link>
          </li>
        )}
        {!this.props.noSideMenu && (
          <li className="nav-item ms-3">
            <Link
              to={`/lines/${env}/services/${serviceId}/stats`}
              {...createTooltip(`Show live metrics report for ${name}`)}
              className={`nav-link ${className('stats')}`}>
              <h3 className="p-2 m-0">
                <i className="fas fa-chart-bar" /> Live metrics
              </h3>
            </Link>
          </li>
        )}
        {!this.props.noSideMenu && (
          <li className="nav-item ms-3">
            <Link
              to={`/lines/${env}/services/${serviceId}/analytics`}
              {...createTooltip(`Show analytics report for ${name}`)}
              className={`nav-link ${className('analytics')}`}>
              <h3 className="p-2 m-0">
                <i className="fas fa-signal" /> Analytics
              </h3>
            </Link>
          </li>
        )}
        {!this.props.noSideMenu && (
          <li className="nav-item ms-3">
            <Link
              to={`/lines/${env}/services/${serviceId}/events`}
              {...createTooltip(`Show raw events report for ${name}`)}
              className={`nav-link ${className('events')}`}>
              <h3 className="p-2 m-0">
                <i className="fas fa-list" /> Events
              </h3>
            </Link>
          </li>
        )}
        {!this.props.noSideMenu && (
          <li className="nav-item ms-3">
            <Link
              to={`/lines/${env}/services/${serviceId}/apikeys`}
              {...createTooltip(`Manage all API keys that can access ${name}`)}
              className={`nav-link ${className('apikeys')}`}>
              <h3 className="p-2 m-0">
                <i className="fas fa-lock" /> API Keys
              </h3>
            </Link>
          </li>
        )}
        {!this.props.noSideMenu && (
          <li className="nav-item ms-3">
            <Link
              to={`/lines/${env}/services/${serviceId}/doc`}
              {...createTooltip(`Show open API documentation for ${name}`)}
              className={`nav-link ${className('doc')}`}>
              <h3 className="p-2 m-0">
                <i className="fas fa-folder" /> Documentation
              </h3>
            </Link>
          </li>
        )}
      </ul>
    );
  }
}
