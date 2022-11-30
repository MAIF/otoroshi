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
    <ul className="nav flex-column nav-sidebar">
      {props.env && !props.env.initWithNewEngine && <>
        <li className="nav-item">
          <h3 className="">
            <i className="fas fa-cubes" /> Services
          </h3>
        </li>
        <li className="nav-item" key="all">
          <Link
            to="/services"
            className={`nav-link ${rootClassName('services')}`}
            {...createTooltip('List all services declared in Otoroshi')}>
            {' '}
            All services
          </Link>
        </li>
        {props.lines.map((line) => (
          <li className="nav-item" key={line}>
            <Link
              to={`/services?env=${line}`}
              className={`nav-link ${className(line)}`}
              {...createTooltip(`List all services declared in Otoroshi for line ${line}`)}>
              {' '}
              For line {line}
            </Link>
          </li>
        ))}
        <li className="nav-item">
          <Link
            to="#"
            onClick={props.addService}
            className="nav-link"
            {...createTooltip('Create a new service descriptor')}>
            <i className="fas fa-plus" /> Add service
          </Link>
        </li>
      </>}
      <>
        <li className="nav-item">
          <h3 className="">
            <i className="fas fa-road" /> Routes
          </h3>
        </li>
        <li className="nav-item" key="all">
          <Link
            to="/routes"
            className={`nav-link ${rootClassName('routes')}`}
            {...createTooltip('List all routes declared in Otoroshi')}>
            {' '}
            All routes
          </Link>
        </li>
        <li className="nav-item">
          <Link
            to="/routes/new?tab=informations"
            className="nav-link"
            {...createTooltip('Create a new route')}>
            <i className="fas fa-plus" /> Add route
          </Link>
        </li>
    </>
    <>
        <li className="nav-item">
          <h3 className="">
            <i className="fas fa-microchip" /> Backends
          </h3>
        </li>
        <li className="nav-item" key="all">
          <Link
            to="/backends"
            className={`nav-link ${rootClassName('backends')}`}
            {...createTooltip('List all backends declared in Otoroshi')}>
            {' '}
            All backends
          </Link>
        </li>
        <li className="nav-item">
          <Link
            to="/backends/add"
            className="nav-link"
            {...createTooltip('Create a new backend')}>
            <i className="fas fa-plus" /> Add backend
          </Link>
        </li>
      </>

      {props.env && props.env.clevercloud && (
        <li className="nav-item">
          <Link
            to="/clever"
            className={`nav-link ${rootClassName('clever')}`}
            {...createTooltip(
              'Create a new service descriptor based on an existing Clever Cloud application'
            )}>
            <i className="fas fa-plus" /> Add service from a CleverApp
          </Link>
        </li>
      )}
      <li className="nav-item">
        <h3 className="mt-3">
          <i className="fas fa-key" /> Apikeys
        </h3>
      </li>
      <li className="nav-item" key="all-apikeys">
        <Link
          to="/apikeys"
          className={`nav-link ${rootClassName('apikeys')}`}
          {...createTooltip('List all apikeys declared in Otoroshi')}>
          {' '}
          All apikeys
        </Link>
      </li>
      <li className="nav-item">
        <Link to="/apikeys/add" className="nav-link" {...createTooltip('Create a new apikey')}>
          <i className="fas fa-plus" /> Add apikey
        </Link>
      </li>
      <li className="nav-item">
        <h3 className="mt-3">
          <i className="fas fa-cubes" /> Tcp Services
        </h3>
      </li>
      <li className="nav-item" key="all-tcp-services">
        <Link
          to="/tcp/services"
          className={`nav-link ${rootClassName('tcp-services')}`}
          {...createTooltip('List all Tcp services declared in Otoroshi')}>
          {' '}
          All services
        </Link>
      </li>
      <li className="nav-item">
        <Link
          to="/tcp/services/add"
          className="nav-link"
          {...createTooltip('Create a new Tcp service')}>
          <i className="fas fa-plus" /> Add Tcp service
        </Link>
      </li>
    </ul>
  );
}
