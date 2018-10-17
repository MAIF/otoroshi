import React, { Component } from 'react';
import PropTypes from 'prop-types';
import Select, { Async } from 'react-select';
import _ from 'lodash';
import fuzzy from 'fuzzy';
import { DefaultAdminPopover } from '../components/inputs';

import * as BackOfficeServices from '../services/BackOfficeServices';

function extractEnv(value = '') {
  const parts = value.split(' ');
  const env = _.last(parts.filter(i => i.startsWith(':')));
  const finalValue = parts.filter(i => !i.startsWith(':')).join(' ');
  if (env) {
    return [env.replace(':', ''), finalValue];
  } else {
    return [null, value];
  }
}

// http://yokai.com/otoroshi/
export class TopBar extends Component {
  searchServicesOptions = query => {
    return fetch(`/bo/api/search/services`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ query: '' }),
    })
      .then(r => r.json())
      .then(results => {
        const options = results.map(v => ({
          label: v.name,
          value: v.serviceId,
          env: v.env,
          action: () => this.gotoService({ env: v.env, value: v.serviceId }),
        }));
        options.sort((a, b) => a.label.localeCompare(b.label));
        options.push({
          action: () => (window.location.href = '/bo/dashboard/admins'),
          env: <span className="glyphicon glyphicon-user" />,
          label: 'Admins',
          value: 'Admins',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/alerts'),
          env: <span className="glyphicon glyphicon-list" />,
          label: 'Alerts Log',
          value: 'Alerts-Log',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/audit'),
          env: <span className="glyphicon glyphicon-list" />,
          label: 'Audit Log',
          value: 'Audit-Log',
        });
        options.push({
          label: 'CleverCloud Apps',
          value: 'CleverCloud-Apps',
          env: <i className="glyphicon glyphicon-list-alt" />,
          action: () => (window.location.href = '/bo/dashboard/clever'),
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/dangerzone'),
          env: <span className="glyphicon glyphicon-alert" />,
          label: 'Danger Zone',
          value: 'Danger-Zone',
        });
        options.push({
          label: 'Documentation',
          value: 'Documentation',
          env: <i className="glyphicon glyphicon-book" />,
          action: () => (window.location.href = '/docs/index.html'),
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/stats'),
          env: <span className="glyphicon glyphicon-signal" />,
          label: 'Global Analytics',
          value: 'Global-Analytics',
        });
        options.push({
          label: 'Groups',
          value: 'Groups',
          env: <i className="glyphicon glyphicon-folder-open" />,
          action: () => (window.location.href = '/bo/dashboard/groups'),
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/loggers'),
          env: <span className="glyphicon glyphicon-book" />,
          label: 'Loggers level',
          value: 'Loggers-level',
        });
        options.push({
          label: 'Services',
          value: 'Services',
          env: <i className="fa fa-cubes" />,
          action: () => (window.location.href = '/bo/dashboard/services'),
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/map'),
          env: <span className="glyphicon glyphicon-globe" />,
          label: 'Services map',
          value: 'Services-map',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/sessions/admin'),
          env: <span className="glyphicon glyphicon-user" />,
          label: 'Admin. sessions',
          value: 'Admin-sessions',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/sessions/private'),
          env: <span className="glyphicon glyphicon-lock" />,
          label: 'Priv. apps sessions',
          value: 'Priv-apps-sessions',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/top10'),
          env: <span className="glyphicon glyphicon-fire" />,
          label: 'Top 10 services',
          value: 'Top-10-services',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/jwt-verifiers'),
          env: <span className="fa fa-key" />,
          label: 'Global Jwt Verifiers',
          value: 'Jwt-Verifiers',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/auth-configs'),
          env: <span className="glyphicon glyphicon-lock" />,
          label: 'Global auth. configs',
          value: 'auth-configs',
        });
        // options.push({
        //   action: () => (window.location.href = '/bo/dashboard/certificates'),
        //   env: <span className="fa fa-certificate" />,
        //   label: 'SSL Certificates',
        //   value: 'certificates',
        // });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/snowmonkey'),
          env: (
            <span>
              <img className="monkeyMenu" src="/__otoroshi_assets/images/nihonzaru.svg" />
            </span>
          ),
          label: 'Snow Monkey',
          value: 'SnowMonkey',
        });
        return { options };
      });
  };

  gotoService = e => {
    if (e) {
      window.location.href = `/bo/dashboard/lines/${e.env}/services/${e.value}`;
    }
  };

  color(env) {
    if (env === 'prod') {
      return 'label-success';
    } else if (env === 'preprod') {
      return 'label-primary';
    } else if (env === 'experiments') {
      return 'label-warning';
    } else if (env === 'dev') {
      return 'label-info';
    } else {
      return 'yellow';
    }
  }

  listenToSlash = e => {
    if (e.keyCode === 191 && e.target.tagName.toLowerCase() !== 'input' && e.target.className && e.target.className.indexOf('ace_text-input') === -1) {
      setTimeout(() => this.selector.focus());
    }
  };

  componentDidMount() {
    if (!this.mounted) {
      this.mounted = true;
      document.addEventListener('keydown', this.listenToSlash, false);
    }
  }

  componentWillUnmount() {
    if (this.mounted) {
      this.mounted = false;
      document.removeEventListener('keydown', this.listenToSlash);
    }
  }

  render() {
    const selected = (this.props.params || {}).lineId;
    return (
      <nav className="navbar navbar-inverse navbar-fixed-top">
        <div className="container-fluid">
          <div className="row">
            <div className="navbar-header col-sm-2">
              <button
                id="toggle-sidebar"
                type="button"
                className="navbar-toggle collapsed menu"
                data-toggle="collapse"
                data-target="#sidebar"
                aria-expanded="false"
                aria-controls="sidebar">
                <span className="sr-only">Toggle sidebar</span>
                <span>Menu</span>
              </button>
              <a className="navbar-brand" href="/bo/dashboard" style={{ display: 'flex' }}>
                <span>おとろし</span> &nbsp; Otoroshi
              </a>
            </div>
            <ul className="nav navbar-nav navbar-right">
              {window.__apiReadOnly && (
                <li>
                  <a style={{ color: '#c44141' }} title="Admin API in read-only mode">
                    <span className="fa fa-lock fa-lg" />
                  </a>
                </li>
              )}
              {this.props.changePassword && (
                <li
                  onClick={e => (window.location = '/bo/dashboard/admins')}
                  style={{ verticalAlign: 'top' }}>
                  <a
                    href="/bo/dashboard/admins"
                    className="dropdown-toggle"
                    data-toggle="dropdown"
                    role="button"
                    aria-haspopup="true"
                    aria-expanded="false">
                    <span
                      className="badge"
                      data-toggle="tooltip"
                      data-placement="bottom"
                      title="You are using the default admin account with the default (very unsecured) password. You should create a new admin account quickly."
                      style={{ backgroundColor: '#c9302c', marginBottom: 5 }}>
                      <i className="glyphicon glyphicon-alert" />
                    </span>
                  </a>
                </li>
              )}
              <li className="dropdown userManagement">
                <a
                  href="#"
                  className="dropdown-toggle"
                  data-toggle="dropdown"
                  role="button"
                  aria-haspopup="true"
                  aria-expanded="false">
                  <i className="fa fa-cog fa-2" aria-hidden="true" />
                </a>
                <ul className="dropdown-menu">
                  {/*<li>
                    <a href="/bo/dashboard/users"><span className="glyphicon glyphicon-user" /> All users</a>
                  </li>*/}
                  <li>
                    <a href="/docs/index.html" target="_blank">
                      <span className="glyphicon glyphicon-book" /> User manual
                    </a>
                  </li>
                  <li role="separator" className="divider" />
                  <li>
                    <a href="/bo/dashboard/groups">
                      <span className="glyphicon glyphicon-folder-open" /> All service groups
                    </a>
                    <a href="/bo/dashboard/jwt-verifiers">
                      <span className="fa fa-key" /> Global Jwt Verifiers
                    </a>
                    <a href="/bo/dashboard/auth-configs">
                      <span className="glyphicon glyphicon-lock" /> Global auth. configs
                    </a>
                    {/*<a href="/bo/dashboard/certificates">
                      <span className="fa fa-certificate" /> SSL Certificates
                    </a>*/}
                  </li>
                  <li>
                    <a href="/bo/dashboard/clever">
                      <span className="glyphicon glyphicon-list-alt" /> Clever apps
                    </a>
                  </li>
                  <li role="separator" className="divider" />
                  <li>
                    <a href="/bo/dashboard/stats">
                      <i className="glyphicon glyphicon-signal" /> Global Analytics
                    </a>
                  </li>
                  <li>
                    <a href="/bo/dashboard/top10">
                      <span className="glyphicon glyphicon-fire" /> Top 10 services
                    </a>
                  </li>
                  <li>
                    <a href="/bo/dashboard/map">
                      <span className="glyphicon glyphicon-globe" /> Services map
                    </a>
                  </li>
                  <li role="separator" className="divider" />
                  <li>
                    <a href="/bo/dashboard/loggers">
                      <span className="glyphicon glyphicon-book" /> Loggers level
                    </a>
                  </li>
                  <li>
                    <a href="/bo/dashboard/audit">
                      <span className="glyphicon glyphicon-list" /> Audit Log
                    </a>
                  </li>
                  <li>
                    <a href="/bo/dashboard/alerts">
                      <span className="glyphicon glyphicon-list" /> Alerts Log
                    </a>
                  </li>
                  <li role="separator" className="divider" />
                  <li>
                    <a href="/bo/dashboard/admins">
                      <span className="glyphicon glyphicon-user" /> Admins
                    </a>
                  </li>
                  <li>
                    <a href="/bo/dashboard/sessions/admin">
                      <span className="glyphicon glyphicon-user" /> Admins sessions
                    </a>
                  </li>
                  <li>
                    <a href="/bo/dashboard/sessions/private">
                      <span className="glyphicon glyphicon-lock" /> Priv. apps sessions
                    </a>
                  </li>
                  <li role="separator" className="divider" />
                  <li>
                    <a href="/bo/dashboard/snowmonkey">
                      <img className="monkeyMenu" src="/__otoroshi_assets/images/nihonzaru.svg" />{' '}
                      Snow Monkey
                    </a>
                  </li>
                  <li role="separator" className="divider" />
                  <li>
                    <a href="/bo/dashboard/dangerzone">
                      <span className="glyphicon glyphicon-alert" /> Danger Zone
                    </a>
                  </li>
                  <li role="separator" className="divider" />
                  <li>
                    <a href="/backoffice/auth0/logout" className="link-logout">
                      <span className="glyphicon glyphicon-off" />
                      <span className="topbar-userName"> {window.__userid} </span>
                    </a>
                  </li>
                </ul>
              </li>
            </ul>
            <form id="navbar" className="navbar-form navbar-left">
              {selected && (
                <div className="form-group" style={{ marginRight: 10 }}>
                  <span
                    title="Current line"
                    className="label label-success"
                    style={{ fontSize: 20, cursor: 'pointer' }}>
                    {selected}
                  </span>
                </div>
              )}
              <div className="form-group" style={{ marginLeft: 10, marginRight: 10 }}>
                <Async
                  ref={r => (this.selector = r)}
                  name="service-search"
                  value="one"
                  placeholder="Search service, line, etc ..."
                  loadOptions={this.searchServicesOptions}
                  openOnFocus={true}
                  onChange={i => i.action()}
                  arrowRenderer={a => {
                    return (
                      <span
                        style={{ display: 'flex', height: 20 }}
                        title="You can jump directly into the search bar from anywhere just by typing '/'">
                        <svg xmlns="http://www.w3.org/2000/svg" width="19" height="20">
                          <defs>
                            <rect id="a" width="19" height="20" rx="3" />
                          </defs>
                          <g fill="none" fillRule="evenodd">
                            <rect stroke="#5F6165" x=".5" y=".5" width="18" height="19" rx="3" />
                            <path fill="#979A9C" d="M11.76 5.979l-3.8 9.079h-.91l3.78-9.08z" />
                          </g>
                        </svg>
                      </span>
                    );
                  }}
                  filterOptions={(opts, value, excluded, conf) => {
                    const [env, searched] = extractEnv(value);
                    const filteredOpts = !!env ? opts.filter(i => i.env === env) : opts;
                    const matched = fuzzy.filter(searched, filteredOpts, {
                      extract: i => i.label,
                      pre: '<',
                      post: '>',
                    });
                    return matched.map(i => i.original);
                  }}
                  optionRenderer={p => {
                    return (
                      <div style={{ display: 'flex' }}>
                        <div style={{ width: 60 }}>
                          {p.env &&
                            _.isString(p.env) && (
                              <span className={`label ${this.color(p.env)}`}>
                                {p.env.replace('experiments', 'exps.')}
                              </span>
                            )}
                          {p.env && !_.isString(p.env) && p.env}
                        </div>
                        <span>{p.label}</span>
                      </div>
                    );
                  }}
                  style={{ width: 400 }}
                />
              </div>
            </form>
          </div>
        </div>
      </nav>
    );
  }
}

// https://assets-cdn.github.com/images/search-shortcut-hint.svg
