import React, { Component } from 'react';
import PropTypes from 'prop-types';
import { Link } from 'react-router-dom';
import { createTooltip } from '../tooltips';

/*export const ServiceSidebar = ({ env, serviceId, name = 'Service', nolink }) => (
  <ul className="nav nav-sidebar">
    <li>
      <h3 style={{ marginTop: 0 }}>
        {nolink && <span><span className="fa fa-cube" /> {name}</span>}
        {!nolink && <Link to={`/lines/${env}/services/${serviceId}`} style={{ color: '#f9b000' }}>
          <span className="fa fa-cube" /> {name}
        </Link>}
      </h3>
    </li>
    <li>
      <Link to={`/lines/${env}/services/${serviceId}/health`}><i className="glyphicon glyphicon-heart" /> Health</Link>
    </li>
    <li>
      <Link to={`/lines/${env}/services/${serviceId}/stats`}><i className="glyphicon glyphicon-stats" /> Live stats</Link>
    </li>
    <li>
      <Link to={`/lines/${env}/services/${serviceId}/analytics`}><i className="glyphicon glyphicon-signal" /> Analytics</Link>
    </li>
    <li>
      <Link to={`/lines/${env}/services/${serviceId}/events`}><i className="glyphicon glyphicon-list" /> Events</Link>
    </li>
    <li>
      <Link to={`/lines/${env}/services/${serviceId}/apikeys`}><i className="glyphicon glyphicon-lock" /> API Keys</Link>
    </li>
    <li>
      <Link to={`/lines/${env}/services/${serviceId}/doc`}><i className="glyphicon glyphicon-folder-close" /> Documentation</Link>
    </li>
  </ul>
);*/

export class ServiceSidebar extends Component {
  render() {
    const { env, serviceId, name = 'Service', nolink } = this.props;
    const pathname = window.location.pathname;
    const base = `/bo/dashboard/lines/${env}/services/${serviceId}/`;
    const className = part => (`${base}${part}` === pathname ? 'active' : '');
    return (
      <ul className="nav nav-sidebar">
        <li>
          <h3
            style={{ marginTop: 0 }}
            {...createTooltip(`Back to the service descriptor of ${name}`)}>
            {nolink && (
              <span>
                <span className="fa fa-cube" /> {name}
              </span>
            )}
            {!nolink && (
              <Link to={`/lines/${env}/services/${serviceId}`} style={{ color: '#f9b000' }}>
                <span className="fa fa-cube" /> {name}
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
              <i className="glyphicon glyphicon-heart" /> Health
            </Link>
          </li>
        )}
        {!this.props.noSideMenu && (
          <li>
            <Link
              to={`/lines/${env}/services/${serviceId}/stats`}
              {...createTooltip(`Show live metrics report for ${name}`)}
              className={className('stats')}>
              <i className="glyphicon glyphicon-stats" /> Live metrics
            </Link>
          </li>
        )}
        {!this.props.noSideMenu && (
          <li>
            <Link
              to={`/lines/${env}/services/${serviceId}/analytics`}
              {...createTooltip(`Show analytics report for ${name}`)}
              className={className('analytics')}>
              <i className="glyphicon glyphicon-signal" /> Analytics
            </Link>
          </li>
        )}
        {!this.props.noSideMenu && (
          <li>
            <Link
              to={`/lines/${env}/services/${serviceId}/events`}
              {...createTooltip(`Show raw events report for ${name}`)}
              className={className('events')}>
              <i className="glyphicon glyphicon-list" /> Events
            </Link>
          </li>
        )}
        {!this.props.noSideMenu && (
          <li>
            <Link
              to={`/lines/${env}/services/${serviceId}/apikeys`}
              {...createTooltip(`Manage all API keys that can access ${name}`)}
              className={className('apikeys')}>
              <i className="glyphicon glyphicon-lock" /> API Keys
            </Link>
          </li>
        )}
        {!this.props.noSideMenu && (
          <li>
            <Link
              to={`/lines/${env}/services/${serviceId}/doc`}
              {...createTooltip(`Show open API documentation for ${name}`)}
              className={className('doc')}>
              <i className="glyphicon glyphicon-folder-close" /> Documentation
            </Link>
          </li>
        )}
      </ul>
    );
  }
}
