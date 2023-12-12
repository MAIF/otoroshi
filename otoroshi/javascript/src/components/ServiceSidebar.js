import React, { Component } from 'react';
import PropTypes from 'prop-types';
import { Link } from 'react-router-dom';
import { createTooltip } from '../tooltips';
import { SidebarContext } from '../apps/BackOfficeApp';

export class ServiceSidebar extends Component {
  render() {
    const { env, serviceId, name = 'Service' } = this.props;
    const pathname = window.location.pathname;
    const base = `/bo/dashboard/lines/${env}/services/${serviceId}/`;
    const className = (part) => (`${base}${part}` === pathname ? 'active' : '');

    return (
      <SidebarContext.Consumer>
        {({ openedSidebar }) => (
          <ul className="nav flex-column nav-sidebar">
            <li className="nav-item">
              <Link
                {...createTooltip(`Back to the service descriptor of ${name}`)}
                to={`/lines/${env}/services/${serviceId}`}
                className="d-flex nav-link active"
              >
                <div style={{ width: '20px' }} className="d-flex justify-content-center">
                  <i className="fas fa-cube" />
                </div>
                <div className="ms-2">{!openedSidebar ? '' : name.toUpperCase()}</div>
              </Link>
            </li>
            {!this.props.noSideMenu && (
              <li className="nav-item">
                <Link
                  {...createTooltip(`Show healthcheck report for ${name}`)}
                  to={`/lines/${env}/services/${serviceId}/health`}
                  className={`d-flex nav-link ${className('health')} ${
                    openedSidebar ? 'ms-3' : ''
                  }`}
                >
                  <div style={{ width: '20px' }} className="d-flex justify-content-center">
                    <i className="fas fa-heart" />
                  </div>
                  <div className="ms-2">{!openedSidebar ? '' : 'Health'}</div>
                </Link>
              </li>
            )}
            {!this.props.noSideMenu && (
              <li className="nav-item">
                <Link
                  to={`/lines/${env}/services/${serviceId}/stats`}
                  {...createTooltip(`Show live metrics report for ${name}`)}
                  className={`d-flex nav-link ${className('stats')} ${openedSidebar ? 'ms-3' : ''}`}
                >
                  <div style={{ width: '20px' }} className="d-flex justify-content-center">
                    <i className="fas fa-chart-bar" />
                  </div>
                  <div className="ms-2">{!openedSidebar ? '' : 'Live metrics'}</div>
                </Link>
              </li>
            )}
            {!this.props.noSideMenu && (
              <li className="nav-item">
                <Link
                  to={`/lines/${env}/services/${serviceId}/analytics`}
                  {...createTooltip(`Show analytics report for ${name}`)}
                  className={`d-flex nav-link ${className('analytics')} ${
                    openedSidebar ? 'ms-3' : ''
                  }`}
                >
                  <div style={{ width: '20px' }} className="d-flex justify-content-center">
                    <i className="fas fa-signal" />
                  </div>
                  <div className="ms-2">{!openedSidebar ? '' : 'Analytics'}</div>
                </Link>
              </li>
            )}
            {!this.props.noSideMenu && (
              <li className="nav-item">
                <Link
                  to={`/lines/${env}/services/${serviceId}/events`}
                  {...createTooltip(`Show raw events report for ${name}`)}
                  className={`d-flex nav-link ${className('events')} ${
                    openedSidebar ? 'ms-3' : ''
                  }`}
                >
                  <div style={{ width: '20px' }} className="d-flex justify-content-center">
                    <i className="fas fa-list" />
                  </div>
                  <div className="ms-2">{!openedSidebar ? '' : 'Events'}</div>
                </Link>
              </li>
            )}
            {!this.props.noSideMenu && (
              <li className="nav-item">
                <Link
                  to={`/lines/${env}/services/${serviceId}/apikeys`}
                  {...createTooltip(`Manage all API keys that can access ${name}`)}
                  className={`d-flex nav-link ${className('apikeys')} ${
                    openedSidebar ? 'ms-3' : ''
                  }`}
                >
                  <div style={{ width: '20px' }} className="d-flex justify-content-center">
                    <i className="fas fa-lock" />
                  </div>
                  <div className="ms-2">{!openedSidebar ? '' : 'API Keys'}</div>
                </Link>
              </li>
            )}
            {!this.props.noSideMenu && (
              <li className="nav-item">
                <Link
                  to={`/lines/${env}/services/${serviceId}/doc`}
                  {...createTooltip(`Show open API documentation for ${name}`)}
                  className={`d-flex nav-link ${className('doc')} ${openedSidebar ? 'ms-3' : ''}`}
                >
                  <div style={{ width: '20px' }} className="d-flex justify-content-center">
                    <i className="fas fa-folder" />
                  </div>
                  <div className="ms-2">{!openedSidebar ? '' : 'Documentation'}</div>
                </Link>
              </li>
            )}
            {/*<li className="dropdown-divider" style={{borderColor:"var(--color_level1)",  marginLeft:"5px",  marginRight:"5px" }}/>*/}
          </ul>
        )}
      </SidebarContext.Consumer>
    );
  }
}
