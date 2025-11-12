import React, { Component } from 'react';

const COLORS = {
  basic: 'hsl(217, 91%, 60%)',   // vibrant blue — professional, trustworthy
  saml: 'hsl(0, 79%, 58%)',      // strong red — enterprise / secure
  oauth1: 'hsl(168, 70%, 44%)',  // teal — techy but calm
  oauth2: 'hsl(32, 100%, 52%)',  // vivid orange — energetic, modern
  ldap: 'hsl(142, 71%, 45%)',    // green — stable, reliable
  custom: 'hsl(47, 100%, 52%)',  // yellow — creative, custom logic
};

function adjustHSLColor(hsl, index, total) {
  const match = hsl.match(/hsl\((\d+),\s*([\d.]+)%,\s*([\d.]+)%\)/);
  if (!match) return hsl;
  const [_, h, s, l] = match.map(Number);
  const variation = total > 1 ? (index / (total - 1)) * 15 - 7.5 : 0; // -7.5% → +7.5%
  const newL = Math.min(100, Math.max(0, l + variation));
  return `hsl(${h}, ${s}%, ${newL}%)`;
}

function Provider({ name, link, type, index = 0, count = 1 }) {
  const baseColor = COLORS[type] || COLORS.custom;
  const bg = adjustHSLColor(baseColor, index, count);

  return (
    <a
      href={link}
      className="text-decoration-none"
      style={{ color: 'inherit' }}
    >
      <div
        className="d-flex align-items-center justify-content-start shadow-sm rounded-3 mb-1 px-3 py-2 transition-all"
        style={{
          background: bg,
          color: '#fff',
          cursor: 'pointer',
          transition: 'transform 0.15s ease, box-shadow 0.15s ease',
        }}
        onMouseEnter={e => {
          e.currentTarget.style.transform = 'scale(1.02)';
          e.currentTarget.style.boxShadow = '0 0.5rem 1rem rgba(0,0,0,0.2)';
        }}
        onMouseLeave={e => {
          e.currentTarget.style.transform = 'scale(1)';
          e.currentTarget.style.boxShadow = '0 .125rem .25rem rgba(0,0,0,0.075)';
        }}
      >
        <div>
          <small className="opacity-75 d-block">Continue with</small>
          <strong style={{ fontSize: '1.1rem' }}>{name}</strong>
        </div>
      </div>
    </a>
  );
}


export class MultiLoginPage extends Component {

  getLink = (id) => {
    if (this.props.redirect.length <= 0) {
      return `/privateapps/generic/login?ref=${id}&route=${this.props.route}&hash=${this.props.hash}`;
    } else {
      return `/privateapps/generic/login?ref=${id}&redirect=${btoa(this.props.redirect)}&route=${this.props.route}&hash=${this.props.hash}`;
    }
  };

  render() {
    const auths = JSON.parse(this.props.auths);

    const { types, ...authenticationModules } = auths;

    const grouped = Object.entries(authenticationModules).reduce((acc, [id, name]) => {
      const type = types[id];
      if (!acc[type]) acc[type] = [];
      acc[type].push({ id, name });
      return acc;
    }, {});

    return (
      <div className="login-card">
        <img src={this.props.otoroshiLogo} />
        <div className="login-card-title">
          <h1>Welcome</h1>
          <p>Log in to Otoroshi to continue</p>
        </div>

        <div className="login-card-body">
          {Object.entries(grouped).map(([type, list]) =>
            list.map((p, index) => (
              <Provider
                key={p.id}
                name={p.name}
                link={this.getLink(p.id)}
                type={type}
                index={index}
                count={list.length}
              />
            ))
          )}
        </div>
      </div>
    );
  }
}
