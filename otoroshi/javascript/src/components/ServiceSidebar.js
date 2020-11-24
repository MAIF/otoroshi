import React, { Component } from 'react';
import PropTypes from 'prop-types';
import { Link } from 'react-router-dom';
import { createTooltip } from '../tooltips';

/*export const ServiceSidebar = ({ env, serviceId, name = 'Service', nolink }) => (
  <ul className="nav nav-sidebar">
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
    const { env, serviceId, name = 'Service', nolink } = this.props;
    const pathname = window.location.pathname;
    const base = `/bo/dashboard/lines/${env}/services/${serviceId}/`;
    const className = (part) => (`${base}${part}` === pathname ? 'active' : '');
    return (
      <ul className="nav nav-sidebar">
        <li>
          <h3
            style={{ marginTop: 0 }}
            {...createTooltip(`Back to the service descriptor of ${name}`)}>
            {nolink && (
              <span>
                <span className="fas fa-cube" /> {name}
              </span>
            )}
            {!nolink && (
              <Link to={`/lines/${env}/services/${serviceId}`} style={{ color: '#f9b000' }}>
                <span className="fas fa-cube" /> {name}
              </Link>
            )}
          </h3>
        </li>
        {!this.props.noSideMenu && (
          <li>
            <Link
              {...createTooltip(`Show healthcheck report for ${name}`)}
              to={`/lines/${env}/services/${serviceId}/health`}
              className={className('health')}>
              <i className="fas fa-heart" /> Health
            </Link>
          </li>
        )}
        {!this.props.noSideMenu && (
          <li>
            <Link
              to={`/lines/${env}/services/${serviceId}/stats`}
              {...createTooltip(`Show live metrics report for ${name}`)}
              className={className('stats')}>
              <i className="fas fa-chart-bar" /> Live metrics
            </Link>
          </li>
        )}
        {!this.props.noSideMenu && (
          <li>
            <Link
              to={`/lines/${env}/services/${serviceId}/analytics`}
              {...createTooltip(`Show analytics report for ${name}`)}
              className={className('analytics')}>
              <i className="fas fa-signal" /> Analytics
            </Link>
          </li>
        )}
        {!this.props.noSideMenu && (
          <li>
            <Link
              to={`/lines/${env}/services/${serviceId}/events`}
              {...createTooltip(`Show raw events report for ${name}`)}
              className={className('events')}>
              <i className="fas fa-list" /> Events
            </Link>
          </li>
        )}
        {!this.props.noSideMenu && (
          <li>
            <Link
              to={`/lines/${env}/services/${serviceId}/apikeys`}
              {...createTooltip(`Manage all API keys that can access ${name}`)}
              className={className('apikeys')}>
              <i className="fas fa-lock" /> API Keys
            </Link>
          </li>
        )}
        {!this.props.noSideMenu && (
          <li>
            <Link
              to={`/lines/${env}/services/${serviceId}/doc`}
              {...createTooltip(`Show open API documentation for ${name}`)}
              className={className('doc')}>
              <i className="fas fa-folder" /> Documentation
            </Link>
          </li>
        )}
      </ul>
    );
  }
}
