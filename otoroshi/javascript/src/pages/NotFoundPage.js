import React, { Component } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../components/Button';

export class NotFoundPage extends Component {
  routeTo = (url) => {
    if (this.props.history) {
      this.props.history.push(url);
    } else {
      window.location.href = `/bo/dashboard${url}`;
    }
  };

  options = [
    // {
    //   action: () => this.routeTo('/routes'),
    //   icon:<span className="fas fa-road" />,
    //   label: 'Routes',
    //   value: 'Routes',
    // },
    // {
    //   action: () => this.routeTo('/route-compositions'),
    //   icon:<span className="fas fa-road" />,
    //   label: 'Route compositions',
    //   value: 'compositions',
    // },
    {
      label: 'Services',
      icon: 'fa-cubes',
      action: () => this.routeTo('/services'),
    },
    {
      action: () => this.routeTo('/exporters'),
      icon: 'fa-paper-plane',
      label: 'Exporters',
    },
    {
      action: () => this.routeTo('/jwt-verifiers'),
      icon: 'fa-key',
      label: 'Global Jwt Verifiers',
    },
    {
      action: () => this.routeTo('/auth-configs'),
      icon: 'fa-lock',
      label: 'Global auth. configs',
    },
    {
      action: () => this.routeTo('/certificates'),
      icon: 'fa-certificate',
      label: 'SSL Certificates',
    },
  ];

  render() {
    return (
      <div>
        <div className="d-flex justify-content-betwen">
          <p
            className="m-0"
            style={{
              fontSize: 60,
              flex: 1,
              alignSelf: 'flex-end',
              color: 'var(--bs-white)',
            }}>
            404
          </p>
          <img src={this.props.env ? this.props.env.otoroshiLogo : ''} className="logoOtoroshi" />
        </div>
        <div style={{ fontSize: 20 }} className="my-3">
          The link is broken or the page has been removed. Try these pages instead:
          <div className="d-flex flex-wrap justify-content-between mt-3" style={{ gap: '12px' }}>
            {this.options.map(({ action, icon, label }) => {
              return (
                <Link onClick={action} key={label}>
                  <Button>
                    <i className={`fas ${icon} me-2`} />
                    {label}
                  </Button>
                </Link>
              );
            })}
          </div>
        </div>
      </div>
    );
  }
}
