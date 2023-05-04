import React, { useContext } from 'react';
import { Link } from 'react-router-dom';

import { createTooltip } from '../tooltips';
import { SidebarContext } from '../apps/BackOfficeApp';
import { firstLetterUppercase } from '../util';

function SidebarLink({ openedSidebar, clearSidebar, path, title, text, icon, rootClassName }) {
  return (
    <li className={`nav-item mt-0 ${openedSidebar ? 'nav-item--open' : ''}`}>
      <Link
        to={`/${path}`}
        className={`nav-link ${rootClassName(path)}`}
        {...createTooltip(text)}
        onClick={clearSidebar}>
        <i className={`fas fa-${icon}`} />{' '}
        {!openedSidebar ? '' : title ? firstLetterUppercase(title) : firstLetterUppercase(path)}
      </Link>
    </li>
  );
}

export function DefaultSidebar(props) {
  const pathname = window.location.pathname;
  const search = (window.location.search || '?').substring(1);
  const rootClassName = (part) =>
    pathname === `/bo/dashboard/${part}` && search === '' ? 'active' : '';

  const clearSidebar = () => {
    if (props.setSidebarContent) props.setSidebarContent(null);
  };

  const sidebarContext = useContext(SidebarContext);
  const { openedSidebar } = sidebarContext;

  return (
    <ul className="nav flex-column nav-sidebar" style={{ marginTop: 20 }}>
      {props.env && !props.env.initWithNewEngine && (
        <SidebarLink
          rootClassName={rootClassName}
          openedSidebar={openedSidebar}
          clearSidebar={clearSidebar}
          path="services"
          text="List all services declared in Otoroshi"
          icon="cubes"
        />
      )}
      <SidebarLink
        rootClassName={rootClassName}
        openedSidebar={openedSidebar}
        clearSidebar={clearSidebar}
        path="routes"
        text="List all routes declared in Otoroshi"
        icon="road"
      />
      <SidebarLink
        rootClassName={rootClassName}
        openedSidebar={openedSidebar}
        clearSidebar={clearSidebar}
        path="backends"
        text="List all backends declared in Otoroshi"
        icon="microchip"
      />
      <SidebarLink
        rootClassName={rootClassName}
        openedSidebar={openedSidebar}
        clearSidebar={clearSidebar}
        path="apikeys"
        text="List all apikeys declared in Otoroshi"
        icon="key"
      />
      <SidebarLink
        rootClassName={rootClassName}
        openedSidebar={openedSidebar}
        clearSidebar={clearSidebar}
        path="certificates"
        text="List all certificates declared in Otoroshi"
        icon="certificate"
      />
      <SidebarLink
        rootClassName={rootClassName}
        openedSidebar={openedSidebar}
        clearSidebar={clearSidebar}
        path="auth-configs"
        text="List all auth. modules declared in Otoroshi"
        icon="lock"
      />
      <SidebarLink
        rootClassName={rootClassName}
        openedSidebar={openedSidebar}
        clearSidebar={clearSidebar}
        path="jwt-verifiers"
        text="List all jwt verifiers declared in Otoroshi"
        icon="circle-check"
      />
      <SidebarLink
        rootClassName={rootClassName}
        openedSidebar={openedSidebar}
        clearSidebar={clearSidebar}
        path="tcp/services"
        text="List all Tcp services declared in Otoroshi"
        icon="cubes"
      />
      <SidebarLink
        rootClassName={rootClassName}
        openedSidebar={openedSidebar}
        clearSidebar={clearSidebar}
        path="exporters"
        text="List all data exporters declared in Otoroshi"
        icon="paper-plane"
      />
      <SidebarLink
        rootClassName={rootClassName}
        openedSidebar={openedSidebar}
        clearSidebar={clearSidebar}
        path="wasm-plugins"
        text="List all wasm-plugins declared in Otoroshi"
        icon="plug"
      />
      {Otoroshi.extensions()
        .flatMap((ext) => ext.sidebarItems)
        .map((item) => (
          <SidebarLink
            rootClassName={rootClassName}
            openedSidebar={openedSidebar}
            clearSidebar={clearSidebar}
            path={item.path}
            text={item.text}
            title={item.title}
            icon={item.icon}
          />
        ))}
      <li className={`nav-item ${openedSidebar ? 'nav-item--open' : ''} pt-3 mt-1`}>
        <Link
          to="/features"
          className={`nav-link ${rootClassName('features')} d-flex align-items-center`}
          {...createTooltip('All features')}
          onClick={clearSidebar}>
          <div
            className="icon-menu icon-svg"
            style={{
              marginRight: openedSidebar ? '1em' : '',
              '-webkit-mask': `url('/assets/images/svgs/menu-icon.svg') no-repeat center`,
              mask: `url('/assets/images/svgs/menu-icon.svg') no-repeat center`,
            }}
          />
          {!openedSidebar ? '' : 'Features'}
        </Link>
      </li>
    </ul>
  );
}
