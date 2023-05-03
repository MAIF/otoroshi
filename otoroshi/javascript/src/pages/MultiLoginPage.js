import React, { Component } from 'react';

const COLORS = {
  basic: '#2980b9',
  saml: '#c0392b',
  oauth1: '#16a085',
  oauth2: '#f39c12',
  ldap: '#27ae60',
  custom: '#f9b000',
}

function Provider({ name, link, type }) {

  return <a style={{
    textDecoration: 'none',
    cursor: 'pointer'
  }} href={link}>
    <div style={{
      background: COLORS[type] || '#f9b000',
      minHeight: 46,
      display: 'flex',
      justifyContent: 'flex-start',
      alignItems: 'center',
      color: '#fff',
      textTransform: 'uppercase'
    }}>
      <div style={{
        minWidth: 46,
        minHeight: 46,
        background: '#fff',
        color: '#000',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center'
      }}>
        {name.substring(0, 1)}
      </div>
      <p style={{
        margin: 0, padding: '0 .5em 0 1em',
        overflow: 'hidden',
        whiteSpace: 'nowrap',
        textOverflow: 'ellipsis'
      }}>LOG IN WITH {name}</p>
    </div>
  </a >
}

export class MultiLoginPage extends Component {

  getLink = id => {
    if (this.props.redirect.length <= 0) {
      return `/privateapps/generic/login?ref=${id}&route=${this.props.route}`
    } else {
      return `/privateapps/generic/login?ref=${id}&redirect=${this.props.redirect}&route=${this.props.route}`
    }
  }

  render() {
    const auths = JSON.parse(this.props.auths);

    const { types, ...authenticationModules } = auths;

    return (
      <div style={{
        background: '#494948',
        border: '5px solid #fff',
        borderRadius: 12,
        width: 375,
        margin: 'auto',
        padding: '1rem',

        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center'
      }}>
        <img src={this.props.otoroshiLogo} style={{ width: 400 }} />
        <div style={{ color: '#fff', width: '100%', textAlign: 'center' }}>
          <h1 style={{ margin: 0 }}>Welcome</h1>
          <p>Log in to Otoroshi to continue</p>
        </div>

        <div style={{
          display: 'flex',
          flexDirection: 'column',
          width: '100%',
          gap: 12
        }}>
          {Object.entries(authenticationModules).map(([id, name]) => {
            return <Provider name={name} link={this.getLink(id)} key={id} type={types[id]} />
          })}
        </div>
      </div>
    );
  }
}
