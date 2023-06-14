import React, { Component } from 'react';

const COLORS = {
  basic: '#2980b9',
  saml: '#c0392b',
  oauth1: '#16a085',
  oauth2: '#f39c12',
  ldap: '#27ae60',
  custom: '#f9b000',
};

function Provider({ name, link, type }) {
  return (
    <a
      style={{
        textDecoration: 'none',
        cursor: 'pointer',
      }}
      href={link}>
      <div
        style={{
          background: COLORS[type] || '#f9b000',
          minHeight: 46,
          display: 'flex',
          justifyContent: 'flex-start',
          alignItems: 'center',
          color: '#fff',
          textTransform: 'uppercase',
          borderRadius: 4,
        }}>
        {/* <div style={{
        minWidth: 46,
        minHeight: 46,
        background: '#fff',
        color: '#000',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center'
      }}>
        {name.substring(0, 1)}
      </div> */}
        <p
          style={{
            margin: 0,
            padding: '0 .25em 0 1em',
            overflow: 'hidden',
            whiteSpace: 'nowrap',
            textOverflow: 'ellipsis',
            // fontSize: '.85rem'
          }}>
          CONTINUE WITH
        </p>
        <p
          className="m-0"
          style={{
            fontWeight: 'bold',
          }}>
          {name}
        </p>
      </div>
    </a>
  );
}

export class MultiLoginPage extends Component {
  getLink = (id) => {
    if (this.props.redirect.length <= 0) {
      return `/privateapps/generic/login?ref=${id}&route=${this.props.route}`;
    } else {
      return `/privateapps/generic/login?ref=${id}&redirect=${this.props.redirect}&route=${this.props.route}`;
    }
  };

  render() {
    const auths = JSON.parse(this.props.auths);

    const { types, ...authenticationModules } = auths;

    return (
      <div className="login-card">
        <img src={this.props.otoroshiLogo} />
        <div className="login-card-title">
          <h1>Welcome</h1>
          <p>Log in to Otoroshi to continue</p>
        </div>

        <div className="login-card-body">
          {Object.entries(authenticationModules).map(([id, name]) => {
            return <Provider name={name} link={this.getLink(id)} key={id} type={types[id]} />;
          })}
        </div>
      </div>
    );
  }
}
