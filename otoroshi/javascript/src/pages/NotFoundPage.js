import React, { Component } from 'react';
import { Link } from 'react-router-dom';

export class NotFoundPage extends Component {
  routeTo = (url) => {
    if (this.props.history) {
      this.props.history.push(url);
    } else {
      window.location.href = `/bo/dashboard${url}`;
    }
  };

  options = [
    {
      label: 'HTTP Routes',
      icon: 'fa-road',
      color: 'hsla(210,70%,55%,.15)',
      action: () => this.routeTo('/routes'),
    },
    {
      label: 'APIs',
      icon: 'fa-brush',
      color: 'hsla(210,70%,55%,.15)',
      action: () => this.routeTo('/apis'),
    },
    {
      label: 'Exporters',
      icon: 'fa-paper-plane',
      color: 'hsla(40,94%,58%,.15)',
      action: () => this.routeTo('/exporters'),
    },
    {
      label: 'Global Jwt Verifiers',
      icon: 'fa-key',
      color: 'hsla(280,60%,55%,.15)',
      action: () => this.routeTo('/jwt-verifiers'),
    },
    {
      label: 'Global auth. modules',
      icon: 'fa-lock',
      color: 'hsla(150,52%,51%,.15)',
      action: () => this.routeTo('/auth-configs'),
    },
    {
      label: 'SSL Certificates',
      icon: 'fa-certificate',
      color: 'hsla(60,50%,45%,.15)',
      action: () => this.routeTo('/certificates'),
    },
  ];

  render() {
    return (
      <div className="hp-root">
        <div
          className="hp-section"
          style={{
            background: 'var(--bg-color_level3)',
            borderRadius: '.5rem',
            padding: '2.5rem 2rem',
            display: 'flex',
            alignItems: 'center',
            gap: '1.5rem',
          }}
        >
          <div style={{ flex: 1 }}>
            <div
              style={{
                fontSize: '5rem',
                fontWeight: 800,
                lineHeight: 1,
                color: 'var(--color-primary)',
                letterSpacing: '-.04em',
              }}
            >
              404
            </div>
            <div
              style={{
                fontSize: '1.25rem',
                fontWeight: 600,
                color: 'var(--text)',
                marginTop: '.5rem',
              }}
            >
              Page not found
            </div>
            <div
              style={{
                fontSize: '.9rem',
                color: 'var(--text)',
                opacity: 0.55,
                marginTop: '.25rem',
              }}
            >
              The link is broken or the page has been removed.
            </div>
          </div>
          {this.props.env?.otoroshiLogo && (
            <img
              src={this.props.env.otoroshiLogo}
              className="logoOtoroshi"
              style={{ maxHeight: 120 }}
              alt=""
            />
          )}
        </div>

        <div className="hp-section">
          <div className="hp-section-title">Try these pages instead</div>
          <div className="hp-entity-grid">
            {this.options.map(({ action, icon, label, color }) => (
              <Link
                key={label}
                to="#"
                onClick={(e) => {
                  e.preventDefault();
                  action();
                }}
                className="hp-entity-tile"
              >
                <div className="hp-entity-tile-icon" style={{ backgroundColor: color }}>
                  <i className={`fas ${icon}`} />
                </div>
                <div className="hp-entity-tile-body">
                  <span className="hp-entity-tile-label">Go to</span>
                  <span
                    style={{
                      fontSize: '1rem',
                      fontWeight: 600,
                      color: 'var(--text)',
                    }}
                  >
                    {label}
                  </span>
                </div>
              </Link>
            ))}
          </div>
        </div>
      </div>
    );
  }
}
