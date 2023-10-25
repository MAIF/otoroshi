import React, { useContext, useState } from "react";
import { Link } from "react-router-dom";

import { createTooltip } from "../tooltips";
import { SidebarContext } from "../apps/BackOfficeApp";
import { firstLetterUppercase } from "../util";
import { graph } from "../pages/FeaturesPage";

function SidebarLink({
  openedSidebar,
  clearSidebar,
  path,
  title,
  text,
  icon,
  rootClassName,
}) {
  const shortcuts = JSON.parse(localStorage.getItem('shortcuts') || "[]");

  console.log(title, path)
  if (!shortcuts.includes(title) && !shortcuts.includes(path))
    return null

  return (
    <li className={`nav-item mt-0 ${openedSidebar ? "nav-item--open" : ""}`}>
      <Link
        to={`/${path}`}
        className={`nav-link ${rootClassName(path)}`}
        {...createTooltip(text)}
        onClick={clearSidebar}
      >
        <i className={`fas fa-${icon}`} />{" "}
        <span style={{ marginTop: "4px" }}>
          {!openedSidebar
            ? ""
            : title
              ? firstLetterUppercase(title)
              : firstLetterUppercase(path)}
        </span>
      </Link>
    </li>
  );
}

export function DefaultSidebar(props) {
  const pathname = window.location.pathname;
  const rootClassName = (part) => {
    if (pathname.startsWith("/bo/dashboard/routes")) {
      if (
        pathname.startsWith("/bo/dashboard/routes/new") ||
        pathname === "/bo/dashboard/routes"
      ) {
        return pathname.startsWith(`/bo/dashboard/${part}`) ? "active" : "";
      } else {
        return "";
      }
    } else {
      return pathname.startsWith(`/bo/dashboard/${part}`) ? "active" : "";
    }
  };

  const clearSidebar = () => {
    if (props.setSidebarContent) props.setSidebarContent(null);
  };

  const sidebarContext = useContext(SidebarContext);
  const { openedSidebar } = sidebarContext;

  const links = graph(props.env);

  return <>
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
        title="auth. modules"
        text="List all auth. modules declared in Otoroshi"
        icon="lock"
      />
      <SidebarLink
        rootClassName={rootClassName}
        openedSidebar={openedSidebar}
        clearSidebar={clearSidebar}
        path="jwt-verifiers"
        title="Jwt verifiers"
        text="List all jwt verifiers declared in Otoroshi"
        icon="circle-check"
      />
      <SidebarLink
        rootClassName={rootClassName}
        openedSidebar={openedSidebar}
        clearSidebar={clearSidebar}
        path="tcp/services"
        title="TCP services"
        text="List all Tcp services declared in Otoroshi"
        icon="cubes"
      />
      <SidebarLink
        rootClassName={rootClassName}
        openedSidebar={openedSidebar}
        clearSidebar={clearSidebar}
        path="exporters"
        title="Data exporters"
        text="List all data exporters declared in Otoroshi"
        icon="paper-plane"
      />
      <SidebarLink
        rootClassName={rootClassName}
        openedSidebar={openedSidebar}
        clearSidebar={clearSidebar}
        path="wasm-plugins"
        title="wasm plugins"
        text="List all wasm-plugins declared in Otoroshi"
        icon="plug"
      />
      {Otoroshi.extensions()
        .flatMap((ext) => ext.sidebarItems)
        .map((item) => (
          <SidebarLink
            key={item.text}
            rootClassName={rootClassName}
            openedSidebar={openedSidebar}
            clearSidebar={clearSidebar}
            path={item.path}
            text={item.text}
            title={item.title}
            icon={item.icon}
          />
        ))}
    </ul>
    <ul className="nav flex-column nav-sidebar" style={{ marginTop: 20 }}>
      {links.map((item, i) => {
        return <Block key={item.title} {...item} first={i === 0} last={i === (links.length - 1)} />
      })}

      <li className={`nav-item ${openedSidebar ? "nav-item--open" : ""} pt-3 mt-1`}>
        <Link
          to="/features"
          className={`nav-link ${rootClassName(
            "features"
          )} d-flex align-items-center`}
          {...createTooltip("All features")}
          onClick={clearSidebar}
        >
          <img
            className="icon-menu icon-svg"
            src="/assets/images/svgs/menu-icon.svg"
            style={{
              marginRight: openedSidebar ? "1em" : "",
            }}
          />
          <span style={{ marginTop: "4px" }}>
            {!openedSidebar ? "" : "Features"}
          </span>
        </Link>
      </li>
    </ul>
  </>
}

function Block({ title, features, first, last }) {
  const [open, setOpen] = useState(false)

  return <div key={title} style={{
    background: 'var(--bg-color_level1)',
    borderTopLeftRadius: first ? 6 : 0,
    borderTopRightRadius: first ? 6 : 0,
    borderBottomLeftRadius: last ? 6 : 0,
    borderBottomRightRadius: last ? 6 : 0,
    cursor: 'pointer',
    marginBottom: 1
  }} className="p-2 me-2" onClick={() => setOpen(!open)}>
    <div style={{
      display: 'flex',
      justifyContent: 'space-between',
      alignItems: 'center',
    }}>
      {title}
      <i className="fas fa-chevron-down" />
    </div>

    {open && <div style={{ display: 'flex', flexDirection: 'column' }} className="p-2">
      {features
        .filter((d) => d.display === undefined || d.display())
        .map(({
          title, link, icon,
        }) => {
          const iconValue = icon ? icon() : null;
          const className = _.isString(iconValue)
            ? iconValue.indexOf(' ') > -1
              ? iconValue
              : `fa ${iconValue}`
            : null;
          const zeIcon = iconValue ? _.isString(iconValue) ? <i className={className} /> : iconValue : null;
          return <Link to={link} key={title} onClick={() => {
            const shortcuts = JSON.parse(localStorage.getItem('shortcuts') || "[]");

            localStorage.setItem("shortcuts", JSON.stringify([...new Set([...shortcuts, title.toLowerCase()])]))
          }}>
            {zeIcon}
            {title}
          </Link>
        })}
    </div>}
  </div>
}