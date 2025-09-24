import React, { useContext, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';

import { createTooltip } from '../tooltips';
import { SidebarContext } from '../apps/BackOfficeApp';
import { firstLetterUppercase } from '../util';
import { graph } from '../pages/FeaturesPage';
import { useHistory } from 'react-router-dom/';
import { icon as snowmonkeyIcon } from '../components/SnowMonkeyConfig.js';
import isString from 'lodash/isString';
import isObject from 'lodash/isObject';

const addShortcutButton = true;

export function DefaultSidebar(props) {
  const pathname = window.location.pathname;
  const rootClassName = (part) => {
    if (pathname.startsWith('/bo/dashboard/routes')) {
      if (pathname.startsWith('/bo/dashboard/routes/new') || pathname === '/bo/dashboard/routes') {
        return pathname.startsWith(`/bo/dashboard/${part}`) ? 'active' : '';
      } else {
        return '';
      }
    } else {
      return pathname.startsWith(`/bo/dashboard/${part}`) ? 'active' : '';
    }
  };

  const clearSidebar = () => {
    if (props.setSidebarContent) props.setSidebarContent(null);
  };

  const preferences = (props.env.user_preferences || { preferences: {} }).preferences;
  const stored_shortcuts = preferences.backoffice_sidebar_shortcuts || [];
  const [shortcuts, setShortcuts] = useState(stored_shortcuts);
  const [hightlighted, setHighlighted] = useState();

  const [start, setStart] = useState({ clientY: 0 });
  const [client, setClient] = useState({ clientY: 0 });
  const [draggingIndex, setDraggingIndex] = useState(-1);

  useEffect(() => {
    reloadStorage();
  }, [props.env]);

  const reloadStorage = () => {
    fetch('/bo/api/me/preferences/backoffice_sidebar_shortcuts', {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    })
      .then((r) => {
        if (r.status === 200) {
          return r.json();
        } else {
          return [];
        }
      })
      .then((newShortCuts) => {
        setShortcuts(newShortCuts);
      });
  };

  const writeStorage = (newShortCuts) => {
    fetch('/bo/api/me/preferences/backoffice_sidebar_shortcuts', {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(newShortCuts),
    })
      .then((r) => r.json())
      .then((newShortCuts) => {
        setShortcuts(newShortCuts);
      });
  };

  const removeShortcut = (shortcut) => {
    if (shortcut.custom) {
      const newShortcuts = shortcuts.filter((f) => {
        if (isObject(f)) {
          return f.link !== shortcut.link;
        } else {
          return true;
        }
      });
      writeStorage(newShortcuts);
    } else {
      const newShortcuts = shortcuts.filter((f) => isObject(f) || !f.includes(shortcut.title));
      writeStorage(newShortcuts);
    }
  };

  const sortCategory = (a, b) => {
    const fa = a.title.toLowerCase(),
      fb = b.title.toLowerCase();

    if (fa < fb) {
      return -1;
    }
    if (fa > fb) {
      return 1;
    }
    return 0;
  };

  const setPosition = (offset) => {
    const index = Math.round(draggingIndex + offset);

    const item = shortcuts[draggingIndex];
    const b = shortcuts[index];

    let newShortcuts = [...shortcuts];
    newShortcuts[draggingIndex] = b;
    newShortcuts[index] = item;

    setDraggingIndex(index);
    setShortcuts(newShortcuts);
  };

  const sidebarContext = useContext(SidebarContext);

  const { openedSidebar } = sidebarContext;

  const onRouteTab =
    window.location.pathname.startsWith('/bo/dashboard/routes/') ||
    window.location.pathname.startsWith('/bo/dashboard/apis/');

  const links = graph(props.env);

  const features = links.flatMap((l) =>
    l.features
      .map((f) => ({ ...f, title: f.title.toLowerCase() }))
      .sort((a, b) => a.title.localeCompare(b.title))
  );

  if (window.location.pathname.startsWith('/bo/dashboard/extensions/workflows/') &&
    window.location.pathname !== '/bo/dashboard/extensions/workflows/workflows') {
    return null;
  }

  return (
    <>
      <ul
        className="nav flex-column nav-sidebar"
        style={{
          opacity: !hightlighted ? 1 : 0.5,
        }}
        onMouseMove={(ev) => {
          if (!isNaN(draggingIndex) && draggingIndex !== -1) {
            if (start.clientY === 0) {
              setStart({ clientY: ev.clientY });
            } else {
              // console.log(Math.floor(ev.clientY - start.clientY), Math.floor(ev.clientY - start.clientY) / 32)
              setClient({ clientY: ev.clientY });

              const offset = Math.floor(ev.clientY - start.clientY) / 32;

              if (offset < -1 || offset >= 1) {
                const index = Math.round(draggingIndex + offset);
                if (!(index < 0 || index > shortcuts.length - 1 || index === draggingIndex)) {
                  setStart({ clientY: ev.clientY });
                  setPosition(offset);
                }
              }
            }
          }
        }}
        onMouseUp={() => {
          setTimeout(() => {
            if (!isNaN(draggingIndex) && draggingIndex !== -1) {
              setDraggingIndex(undefined);
              writeStorage(shortcuts.filter((f) => f));
            }
          }, 50); // delay to avoid simple click
        }}
      >
        {openedSidebar && !onRouteTab && shortcuts.length > 0 && (
          <p className="sidebar-title">Shortcuts</p>
        )}

        {!onRouteTab &&
          shortcuts
            .map((shortcut) => {
              if (isObject(shortcut)) {
                shortcut.link = shortcut.link || shortcut.path;
                shortcut.icon = shortcut.icon || (() => 'fa-star');
                shortcut.custom = true;
                return shortcut;
              } else {
                return features.find((feat) => feat.title.includes(shortcut));
              }
            })
            .filter((s) => s)
            .map((shortcut, initialIndex) => {
              return (
                <SidebarLink
                  key={shortcut.title}
                  dragging={
                    draggingIndex === initialIndex
                      ? {
                        clientY: client.clientY - start.clientY,
                      }
                      : undefined
                  }
                  startDragging={(clientY) => {
                    setStart({ clientY });
                    setDraggingIndex(initialIndex);
                  }}
                  removeShortcut={() => removeShortcut(shortcut)}
                  rootClassName={rootClassName}
                  openedSidebar={openedSidebar}
                  clearSidebar={clearSidebar}
                  {...shortcut}
                />
              );
            })}
      </ul>
      {openedSidebar && !onRouteTab && (
        <ul className="nav flex-column nav-sidebar me-2" style={{ marginTop: 20 }}>
          <p className="sidebar-title">Categories</p>
          <div className="d-flex flex-column">
            {links.sort(sortCategory).map((item, i) => {
              return (
                <Block
                  key={item.title}
                  {...item}
                  first={i === 0}
                  last={i === links.length - 1}
                  shortcuts={shortcuts}
                  writeStorage={writeStorage}
                  hightlighted={!hightlighted || item.title === hightlighted}
                  setHighlighted={() => setHighlighted(item.title)}
                  onClose={() => setHighlighted(undefined)}
                />
              );
            })}
          </div>

          <li
            className={`nav-item ${openedSidebar ? 'nav-item--open' : ''} mt-3`}
            style={{
              opacity: !hightlighted ? 1 : 0.5,
            }}
          >
            <Link
              to="/features"
              className={`nav-link ${rootClassName('features')} d-flex align-items-center`}
              {...createTooltip('All features')}
              onClick={clearSidebar}
            >
              <img
                className="icon-menu icon-svg"
                src="/assets/images/svgs/menu-icon.svg"
                style={{
                  marginRight: openedSidebar ? '1em' : '',
                }}
              />
              <span style={{ marginTop: '4px' }}>{!openedSidebar ? '' : 'Features'}</span>
            </Link>
          </li>
        </ul>
      )}
    </>
  );
}

function CustomIcon({ icon, title }) {
  const iconValue = icon ? (typeof icon === 'function' ? icon() : icon) : null;
  const className = isString(iconValue)
    ? iconValue.indexOf(' ') > -1
      ? iconValue
      : `fa ${iconValue}`
    : null;
  let zeIcon = iconValue ? (
    isString(iconValue) ? (
      <i className={className} title={title} />
    ) : (
      iconValue
    )
  ) : null;
  if (iconValue === 'fa-snow-monkey') {
    zeIcon = snowmonkeyIcon;
  }
  if (isObject(zeIcon) && zeIcon.type === 'svg' && !zeIcon['$$typeof']) {
    return <i className="fas fa-thumbtack" title={title} />;
  } else {
    return zeIcon;
  }
}

function Block({
  title,
  description,
  features,
  first,
  last,
  hightlighted,
  setHighlighted,
  onClose,
  shortcuts,
  writeStorage,
}) {
  const [open, setOpen] = useState(false);
  const history = useHistory();

  return (
    <div
      key={title}
      style={{
        background: 'var(--bg-color_level1)',
        borderTopLeftRadius: first ? 6 : 0,
        borderTopRightRadius: first ? 6 : 0,
        borderBottomLeftRadius: last ? 6 : 0,
        borderBottomRightRadius: last ? 6 : 0,
        cursor: 'pointer',
        marginBottom: 1,
        opacity: hightlighted ? 1 : 0.5,
      }}
      className="py-2"
      onClick={() => {
        if (!open === false) {
          onClose();
        } else {
          setHighlighted();
        }
        setOpen(!open);
      }}
    >
      <div
        className="d-flex justify-content-between align-items-center px-3"
        style={{ color: 'var(--text)' }}
      >
        {title}
        <i className="fas fa-chevron-down" />
      </div>

      {open && (
        <div style={{ display: 'flex', flexDirection: 'column' }} className="mt-2 animOpacity">
          {features
            .filter((d) => d.display === undefined || d.display())
            .sort((a, b) => a.title.toLowerCase().localeCompare(b.title.toLowerCase()))
            .map(({ title, link, icon, tag }) => {
              const alreadyInShortcuts = !!shortcuts.find((s) => s === title.toLowerCase());
              if (link.indexOf('http') === 0) {
                const iconTitle = description ? `${title} - ${description}` : title;
                return (
                  <a
                    href={link}
                    target="_blank"
                    key={title}
                    className="sidebar-feature p-3 py-1 mx-1"
                    style={{
                      height: 'initial',
                      borderRadius: 6,
                      display: 'flex',
                      flexDirection: 'row',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                    }}
                    onClick={(e) => {
                      if (!addShortcutButton) {
                        writeStorage([...new Set([...shortcuts, title.toLowerCase()])]);
                      }
                    }}
                  >
                    <div
                      style={{
                        display: 'flex',
                        flexDirection: 'row',
                        alignItems: 'center',
                        color: alreadyInShortcuts ? '#888' : null,
                      }}
                    >
                      <CustomIcon icon={icon} title={iconTitle} />
                      <div
                        title={iconTitle}
                        style={{
                          overflow: 'hidden',
                          whiteSpace: 'nowrap',
                          textOverflow: 'ellipsis',
                          marginLeft: 15,
                          maxWidth: 130,
                        }}
                      >
                        {title}
                      </div>
                    </div>
                    {addShortcutButton && (
                      <i
                        className="fas fa-thumbtack"
                        title={
                          alreadyInShortcuts ? 'Already added to shortcuts' : 'Add to shortcuts'
                        }
                        disabled={alreadyInShortcuts}
                        style={{ cursor: 'pointer', color: alreadyInShortcuts ? '#888' : null }}
                        onClick={(e) => {
                          if (!alreadyInShortcuts && addShortcutButton) {
                            writeStorage([...new Set([...shortcuts, title.toLowerCase()])]);
                            e.preventDefault();
                            e.stopPropagation();
                          }
                        }}
                      />
                    )}
                  </a>
                );
              }

              const iconTitle = description ? `${title} - ${description}` : title;

              return (
                <Link
                  to={link}
                  key={title}
                  className="sidebar-feature p-3 py-1 mx-1"
                  style={{
                    height: 'initial',
                    borderRadius: 6,
                    display: 'flex',
                    flexDirection: 'row',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                  }}
                  onClick={(e) => {
                    if (!addShortcutButton) {
                      writeStorage([...new Set([...shortcuts, title.toLowerCase()])]);
                    }
                  }}
                >
                  <div
                    style={{
                      display: 'flex',
                      flexDirection: 'row',
                      alignItems: 'center',
                      color: alreadyInShortcuts ? '#888' : null,
                    }}
                  >
                    <CustomIcon icon={icon} title={iconTitle} />
                    <div
                      title={iconTitle}
                      style={{
                        overflow: 'hidden',
                        whiteSpace: 'nowrap',
                        textOverflow: 'ellipsis',
                        marginLeft: 15,
                        maxWidth: 130,
                      }}
                      className="pe-2"
                    >
                      {title}
                    </div>
                    {tag}
                  </div>
                  {addShortcutButton && (
                    <i
                      className="fas fa-thumbtack"
                      title={alreadyInShortcuts ? 'Already added to shortcuts' : 'Add to shortcuts'}
                      disabled={alreadyInShortcuts}
                      style={{ cursor: 'pointer', color: alreadyInShortcuts ? '#888' : null }}
                      onClick={(e) => {
                        if (!alreadyInShortcuts && addShortcutButton) {
                          writeStorage([...new Set([...shortcuts, title.toLowerCase()])]);
                          e.preventDefault();
                          e.stopPropagation();
                        }
                      }}
                    />
                  )}
                </Link>
              );
            })}
        </div>
      )}
    </div>
  );
}

function SidebarLink({
  openedSidebar,
  clearSidebar,
  title,
  description,
  text,
  icon,
  rootClassName,
  removeShortcut,
  startDragging,
  dragging,
  ...props
}) {
  const path = props.path || props.link;
  const iconTitle = description ? `${title} - ${description}` : title;

  return (
    <li
      className={`nav-item mt-0 d-flex align-items-center animOpacity ${openedSidebar ? 'nav-item--open' : ''
        }`}
      draggable={false}
      style={{
        position: dragging ? 'asbolute' : 'relative',
        top: dragging ? dragging.clientY : 'initial',
        border: openedSidebar
          ? dragging
            ? '1px solid var(--bg-color_level3)'
            : '2px solid transparent'
          : 'none',
        zIndex: dragging ? 100 : 1,
        background: dragging ? 'var(--bg-color_level1)' : 'inherit',
      }}
    >
      {openedSidebar && (
        <i
          className="fas fa-grip-vertical nav-item-eye d-flex align-items-center m-0"
          title="Move shortcut"
          onMouseDown={(e) => {
            startDragging(e.clientY);
          }}
          style={{
            position: 'absolute',
            top: 0,
            left: 6,
            bottom: 0,
            cursor: 'move',
          }}
        />
      )}
      {path.indexOf('http') < 0 && (
        <Link
          to={`/${path}`.replace('//', '/')}
          className={`nav-link ${rootClassName(path)}`}
          {...createTooltip(text)}
          onClick={clearSidebar}
          style={{ flex: 1, marginLeft: openedSidebar ? 4 : 0 }}
        >
          <CustomIcon icon={icon} title={iconTitle} />{' '}
          <span
            style={{ marginTop: '4px' }}
            className="d-flex align-items-center"
            title={iconTitle}
          >
            {!openedSidebar ? '' : title ? firstLetterUppercase(title) : firstLetterUppercase(path)}
            <div className="ms-2">{props.tag}</div>
          </span>
        </Link>
      )}
      {path.indexOf('http') === 0 && (
        <a
          href={path}
          target="_blank"
          className={`nav-link`}
          {...createTooltip(text)}
          onClick={clearSidebar}
          style={{ flex: 1, marginLeft: openedSidebar ? 4 : 0 }}
        >
          <CustomIcon icon={icon} title={iconTitle} />{' '}
          <span
            style={{ marginTop: '4px' }}
            className="d-flex align-items-center"
            title={iconTitle}
          >
            {!openedSidebar ? '' : title ? firstLetterUppercase(title) : firstLetterUppercase(path)}
            <div className="ms-2">{props.tag}</div>
          </span>
        </a>
      )}
      <i
        className="fas fa-eye-slash nav-item-eye me-auto"
        onClick={removeShortcut}
        title="Remove shortcut"
      />
    </li>
  );
}
