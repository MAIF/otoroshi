import React from 'react';
import { Link } from 'react-router-dom';

import { createTooltip } from '../tooltips';

export function DefaultSidebar(props) {
  const pathname = window.location.pathname;
  const search = (window.location.search || '?').substring(1);
  const base = `/bo/dashboard/services`;
  const rootClassName = (part) =>
    pathname === `/bo/dashboard/${part}` && search === '' ? 'active' : '';
  const className = (part) =>
    base === pathname && search.indexOf(`env=${part}`) > -1 ? 'active' : '';

  return (
    <ul className="nav flex-column nav-sidebar no-margin-left">
      {props.env && !props.env.initWithNewEngine && (
        <>
          <li className="nav-item mt-2">
            <Link
              to="/services"
              className={`nav-link ${rootClassName('services')}`}
              {...createTooltip('List all services declared in Otoroshi')}>
              <h3 className="p-2 m-0">
                <i className="fas fa-cubes" /> SERVICES
              </h3>
            </Link>
          </li>
        </>
      )}
      <li className="nav-item mt-2">
        <Link
          to="/routes"
          className={`nav-link ${rootClassName('routes')}`}
          {...createTooltip('List all routes declared in Otoroshi')}>
          <h3 className="p-2 m-0">
            <i className="fas fa-road" /> ROUTES
          </h3>
        </Link>
      </li>
      <li className="nav-item mt-2">
        <Link
          to="/backends"
          className={`nav-link ${rootClassName('backends')}`}
          {...createTooltip('List all backends declared in Otoroshi')}>
          <h3 className="p-2 m-0">
            <i className="fas fa-microchip" /> BACKENDS
          </h3>
        </Link>
      </li>
      <li className="nav-item mt-2">
        <Link
          to="/apikeys"
          className={`nav-link ${rootClassName('apikeys')}`}
          {...createTooltip('List all apikeys declared in Otoroshi')}>
          <h3 className="p-2 m-0">
            <i className="fas fa-key" /> APIKEYS
          </h3>
        </Link>
      </li>
      <li className="nav-item mt-2">
        <Link
          to="/tcp/services"
          className={`nav-link ${rootClassName('tcp-services')}`}
          {...createTooltip('List all Tcp services declared in Otoroshi')}>
          <h3 className="p-2 m-0">
            <i className="fas fa-cubes" /> TCP SERVICES
          </h3>
        </Link>
      </li>
    </ul>
  );
}
