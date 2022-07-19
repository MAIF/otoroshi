import React from 'react';

import { createTooltip } from '../tooltips';

export function DefaultSidebar(props) {
  const pathname = window.location.pathname;
  const search = (window.location.search || '?').substring(1);
  const base = `/bo/dashboard/services`;
  const rootClassName = (part) =>
    pathname === `/bo/dashboard/${part}` && search === '' ? 'active' : '';
  const className = (part) =>
    base === pathname && search.indexOf(`env=${part}`) > -1 ? 'active' : '';

  const canShowSidebar = () => {
    return !['/bo/dashboard/routes/'].some(path => pathname.startsWith(path))
  }

  if (!canShowSidebar())
    return null

  return (
    <ul className="nav flex-column nav-sidebar">
      <li className="nav-item">
        <h3 className="">
          <i className="fas fa-cubes" /> Services
        </h3>
      </li>
      <li className="nav-item" key="all">
        <a
          href={`/bo/dashboard/services`}
          className={`nav-link ${rootClassName('services')}`}
          {...createTooltip('List all services declared in Otoroshi')}>
          {' '}
          All services
        </a>
      </li>
      {props.lines.map((line) => (
        <li className="nav-item" key={line}>
          <a
            href={`/bo/dashboard/services?env=${line}`}
            className={`nav-link ${className(line)}`}
            {...createTooltip(`List all services declared in Otoroshi for line ${line}`)}>
            {' '}
            For line {line}
          </a>
        </li>
      ))}
      <li className="nav-item">
        <a
          href="#"
          onClick={props.addService}
          className="nav-link"
          {...createTooltip('Create a new service descriptor')}>
          <i className="fas fa-plus" /> Add service
        </a>
      </li>
      {props.env && props.env.clevercloud && (
        <li className="nav-item">
          <a
            href="/bo/dashboard/clever"
            className={`nav-link ${rootClassName('clever')}`}
            {...createTooltip(
              'Create a new service descriptor based on an existing Clever Cloud application'
            )}>
            <i className="fas fa-plus" /> Add service from a CleverApp
          </a>
        </li>
      )}
      <li className="nav-item">
        <h3 className="mt-3">
          <i className="fas fa-key" /> Apikeys
        </h3>
      </li>
      <li className="nav-item" key="all-apikeys">
        <a
          href={`/bo/dashboard/apikeys`}
          className={`nav-link ${rootClassName('apikeys')}`}
          {...createTooltip('List all apikeys declared in Otoroshi')}>
          {' '}
          All apikeys
        </a>
      </li>
      <li className="nav-item">
        <a
          href={`/bo/dashboard/apikeys/add`}
          className="nav-link"
          {...createTooltip('Create a new apikey')}>
          <i className="fas fa-plus" /> Add apikey
        </a>
      </li>
      <li className="nav-item">
        <h3 className="mt-3">
          <i className="fas fa-cubes" /> Tcp Services
        </h3>
      </li>
      <li className="nav-item" key="all-tcp-services">
        <a
          href={`/bo/dashboard/tcp/services`}
          className={`nav-link ${rootClassName('tcp-services')}`}
          {...createTooltip('List all Tcp services declared in Otoroshi')}>
          {' '}
          All services
        </a>
      </li>
      <li className="nav-item">
        <a
          href={`/bo/dashboard/tcp/services/add`}
          className="nav-link"
          {...createTooltip('Create a new Tcp service')}>
          <i className="fas fa-plus" /> Add Tcp service
        </a>
      </li>
    </ul>
  );
}
