import React, { Component } from "react";
import PropTypes from "prop-types";
import { Link } from "react-router-dom";
import { createTooltip } from "../tooltips";
import { SidebarContext } from "../apps/BackOfficeApp";

export class ServiceSidebar extends Component {
  render() {
    const { env, serviceId, name = "Service" } = this.props;
    const pathname = window.location.pathname;
    const base = `/bo/dashboard/lines/${env}/services/${serviceId}/`;
    const className = (part) => (`${base}${part}` === pathname ? "active" : "");

    return (
      <SidebarContext.Consumer>
        {({ openedSidebar }) => (
          <ul className="nav flex-column nav-sidebar">
            <li className="nav-item">
              <Link
                {...createTooltip(`Back to the service descriptor of ${name}`)}
                to={`/lines/${env}/services/${serviceId}`}
                className="nav-link active"
              >
                <i className="fas fa-cube" />{" "}
                {!openedSidebar ? "" : name.toUpperCase()}
              </Link>
            </li>
            {!this.props.noSideMenu && (
              <li className="nav-item">
                <Link
                  {...createTooltip(`Show healthcheck report for ${name}`)}
                  to={`/lines/${env}/services/${serviceId}/health`}
                  className={`nav-link ${className("health")} ${openedSidebar ? 'ms-3' : ''}`}
                >
                  <i className="fas fa-heart" />{" "}
                  {!openedSidebar ? "" : "Health"}
                </Link>
              </li>
            )}
            {!this.props.noSideMenu && (
              <li className="nav-item">
                <Link
                  to={`/lines/${env}/services/${serviceId}/stats`}
                  {...createTooltip(`Show live metrics report for ${name}`)}
                  className={`nav-link ${className("stats")} ${openedSidebar ? 'ms-3' : ''}`}
                >
                  <i className="fas fa-chart-bar" />{" "}
                  {!openedSidebar ? "" : "Live metrics"}
                </Link>
              </li>
            )}
            {!this.props.noSideMenu && (
              <li className="nav-item">
                <Link
                  to={`/lines/${env}/services/${serviceId}/analytics`}
                  {...createTooltip(`Show analytics report for ${name}`)}
                  className={`nav-link ${className("analytics")} ${openedSidebar ? 'ms-3' : ''}`}
                >
                  <i className="fas fa-signal" />{" "}
                  {!openedSidebar ? "" : "Analytics"}
                </Link>
              </li>
            )}
            {!this.props.noSideMenu && (
              <li className="nav-item">
                <Link
                  to={`/lines/${env}/services/${serviceId}/events`}
                  {...createTooltip(`Show raw events report for ${name}`)}
                  className={`nav-link ${className("events")} ${openedSidebar ? 'ms-3' : ''}`}
                >
                  <i className="fas fa-list" /> {!openedSidebar ? "" : "Events"}
                </Link>
              </li>
            )}
            {!this.props.noSideMenu && (
              <li className="nav-item">
                <Link
                  to={`/lines/${env}/services/${serviceId}/apikeys`}
                  {...createTooltip(
                    `Manage all API keys that can access ${name}`
                  )}
                  className={`nav-link ${className("apikeys")} ${openedSidebar ? 'ms-3' : ''}`}
                >
                  <i className="fas fa-lock" />{" "}
                  {!openedSidebar ? "" : "API Keys"}
                </Link>
              </li>
            )}
            {!this.props.noSideMenu && (
              <li className="nav-item">
                <Link
                  to={`/lines/${env}/services/${serviceId}/doc`}
                  {...createTooltip(`Show open API documentation for ${name}`)}
                  className={`nav-link ${className("doc")} ${openedSidebar ? 'ms-3' : ''}`}
                >
                  <i className="fas fa-folder" />{" "}
                  {!openedSidebar ? "" : "Documentation"}
                </Link>
              </li>
            )}
            <li className="dropdown-divider" style={{borderColor:"var(--color_level1)",  marginLeft:"5px",  marginRight:"5px" }}/>
          </ul>
        )}
      </SidebarContext.Consumer>
    );
  }
}
