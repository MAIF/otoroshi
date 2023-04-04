import React, { useContext } from 'react';
import { Link } from 'react-router-dom';

import { createTooltip } from '../tooltips';
import { SidebarContext } from '../apps/BackOfficeApp';

export function DefaultSidebar(props) {
  const pathname = window.location.pathname;
  const search = (window.location.search || '?').substring(1);
  const base = `/bo/dashboard/services`;
  const rootClassName = (part) =>
    pathname === `/bo/dashboard/${part}` && search === '' ? 'active' : '';
  const className = (part) =>
    base === pathname && search.indexOf(`env=${part}`) > -1 ? 'active' : '';

  const clearSidebar = () => {
    if (props.setSidebarContent) props.setSidebarContent(null);
  };

  const sidebarContext = useContext(SidebarContext);
  const { openedSidebar } = sidebarContext;

  return (
    <ul className="nav flex-column nav-sidebar">
      {props.env && !props.env.initWithNewEngine && (
        <>
          <li className={`nav-item mt-2 ${openedSidebar ? 'nav-item--open' : ''}`}>
            <Link
              to="/services"
              className={`nav-link ${rootClassName('services')}`}
              {...createTooltip('List all services declared in Otoroshi')}
              onClick={clearSidebar}>
              <i className="fas fa-cubes" /> {!openedSidebar ? '' : 'SERVICES'}
            </Link>
          </li>
        </>
      )}
      <li className={`nav-item mt-2 ${openedSidebar ? 'nav-item--open' : ''}`}>
        <Link
          to="/routes"
          className={`nav-link ${rootClassName('routes')}`}
          {...createTooltip('List all routes declared in Otoroshi')}
          onClick={clearSidebar}>
          <i className="fas fa-road" /> {!openedSidebar ? '' : 'ROUTES'}
        </Link>
      </li>
      <li className={`nav-item mt-2 ${openedSidebar ? 'nav-item--open' : ''}`}>
        <Link
          to="/backends"
          className={`nav-link ${rootClassName('backends')}`}
          {...createTooltip('List all backends declared in Otoroshi')}
          onClick={clearSidebar}>
          <i className="fas fa-microchip" /> {!openedSidebar ? '' : 'BACKENDS'}
        </Link>
      </li>
      <li className={`nav-item mt-2 ${openedSidebar ? 'nav-item--open' : ''}`}>
        <Link
          to="/apikeys"
          className={`nav-link ${rootClassName('apikeys')}`}
          {...createTooltip('List all apikeys declared in Otoroshi')}
          onClick={clearSidebar}>
          <i className="fas fa-key" /> {!openedSidebar ? '' : 'APIKEYS'}
        </Link>
      </li>
      <li className={`nav-item mt-2 ${openedSidebar ? 'nav-item--open' : ''}`}>
        <Link
          to="/certificates"
          className={`nav-link ${rootClassName('certificates')}`}
          {...createTooltip('List all certificates declared in Otoroshi')}
          onClick={clearSidebar}>
          <i className="fas fa-certificate" /> {!openedSidebar ? '' : 'CERTIFICATES'}
        </Link>
      </li>
      <li className={`nav-item mt-2 ${openedSidebar ? 'nav-item--open' : ''}`}>
        <Link
          to="/auth-configs"
          className={`nav-link ${rootClassName('auth-configs')}`}
          {...createTooltip('List all auth. modules declared in Otoroshi')}
          onClick={clearSidebar}>
          <i className="fas fa-lock" /> {!openedSidebar ? '' : 'AUTH. MODULES'}
        </Link>
      </li>
      <li className={`nav-item mt-2 ${openedSidebar ? 'nav-item--open' : ''}`}>
        <Link
          to="/jwt-verifiers"
          className={`nav-link ${rootClassName('jwt-verifiers')}`}
          {...createTooltip('List all jwt verifiers declared in Otoroshi')}
          onClick={clearSidebar}>
          <i className="fas fa-circle-check" /> {!openedSidebar ? '' : 'JWT VERIFIERS'}
        </Link>
      </li>
      <li className={`nav-item mt-2 ${openedSidebar ? 'nav-item--open' : ''}`}>
        <Link
          to="/tcp/services"
          className={`nav-link ${rootClassName('tcp/services')}`}
          {...createTooltip('List all Tcp services declared in Otoroshi')}
          onClick={clearSidebar}>
          <i className="fas fa-cubes" /> {!openedSidebar ? '' : 'TCP SERVICES'}
        </Link>
      </li>
      <li className={`nav-item mt-2 ${openedSidebar ? 'nav-item--open' : ''}`}>
        <Link
          to="/exporters"
          className={`nav-link ${rootClassName('exporters')}`}
          {...createTooltip('List all data exporters declared in Otoroshi')}
          onClick={clearSidebar}>
          <i className="fas fa-paper-plane" /> {!openedSidebar ? '' : 'DATA EXPORTERS'}
        </Link>
      </li>
      <li className={`nav-item mt-2 ${openedSidebar ? 'nav-item--open' : ''}`}>
        <Link
          to="/wasm-plugins"
          className={`nav-link ${rootClassName('wasm-plugins')}`}
          {...createTooltip('List all wasm plugins declared in Otoroshi')}
          onClick={clearSidebar}>
          <i className="fas fa-plug" /> {!openedSidebar ? '' : 'WASM PLUGINS'}
        </Link>
      </li>
      <li className={`nav-item mt-2 ${openedSidebar ? 'nav-item--open' : ''}`}>
        <Link
          to="/features"
          className={`nav-link ${rootClassName('features')} d-flex align-items-center`}
          {...createTooltip('All features')}
          style={{ marginTop: 30 }}
          onClick={clearSidebar}>
          <div className='icon-menu'
            style={{
              marginRight: openedSidebar ? '1em' : '',
              '-webkit-mask': `url('/assets/images/svgs/menu-icon.svg') no-repeat center`,
              mask: `url('/assets/images/svgs/menu-icon.svg') no-repeat center`
            }} />
          {!openedSidebar ? '' : 'FEATURES'}
        </Link>
      </li>
    </ul>
  );
}
