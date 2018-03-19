import React, { Component } from 'react';
import PropTypes from 'prop-types';

export class U2FLoginPage extends Component {
  state = {
    email: '',
    password: '',
    error: null,
    message: null,
  };

  onChange = e => {
    this.setState({ [e.target.name]: e.target.value });
  };

  handleError = (mess, t) => {
    return err => {
      console.log(err && err.message ? err.message : err);
      this.setState({ error: mess });
      throw err;
    };
  };

  login = e => {
    if (e && e.preventDefault) {
      e.preventDefault();
    }
    const username = this.state.email;
    const password = this.state.password;
    this.setState({ message: null });
    fetch(`/bo/u2f/login/start`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        username,
      }),
    })
      .then(r => {
        if (r.ok) {
          return r.json();
        } else {
          throw new Error('Bad Request ...');
        }
      }, this.handleError('Login error, sorry ...'))
      .then(payload => {
        const username = payload.username;
        const request = payload.data;
        this.setState({ message: 'now touch your blinking U2F device ...' });
        u2f.sign(request.authenticateRequests, data => {
          console.log(data);
          if (data.errorCode) {
            this.setState({ error: `Login error, sorry ... ${data.errorCode}` });
          } else {
            this.setState({ message: 'Finishing login ...' });
            fetch(`/bo/u2f/login/finish`, {
              method: 'POST',
              credentials: 'include',
              headers: {
                Accept: 'application/json',
                'Content-Type': 'application/json',
              },
              body: JSON.stringify({
                username: username,
                password,
                tokenResponse: data,
              }),
            })
              .then(r => {
                if (r.ok) {
                  return r.json();
                } else {
                  throw new Error('Bad Request ...');
                }
              }, this.handleError('Authentication error, sorry ...'))
              .then(data => {
                console.log(data);
                this.setState(
                  { error: null, email: '', password: '', message: `Login successfully` },
                  () => {
                    window.location.href = '/bo/dashboard';
                  }
                );
              }, this.handleError('Login error, sorry ...'));
          }
        });
      }, this.handleError('Login error, sorry ...'));
  };

  simpleLogin = e => {
    if (e && e.preventDefault) {
      e.preventDefault();
    }
    const username = this.state.email;
    const password = this.state.password;
    this.setState({ message: null });
    return fetch(`/bo/simple/login`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        username,
        password,
      }),
    }).then(r => {
      if (r.ok) {
        window.location.href = '/bo/dashboard';
      } else {
        this.handleError('Something is wrong ...')();
      }
    }, this.handleError('Login and/or password error, sorry ...'));
  };

  render() {
    return (
      <div className="jumbotron">
        <h3 style={{ marginBottom: 40 }}>Admin login</h3>
        <form className="form-horizontal" style={{ textAlign: 'left' }}>
          <div className="form-group">
            <label className="col-sm-2 control-label">Username</label>
            <div className="col-sm-10">
              <input
                type="text"
                name="email"
                className="form-control"
                value={this.props.email}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label">Password</label>
            <div className="col-sm-10">
              <input
                type="password"
                name="password"
                className="form-control"
                value={this.props.password}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label" />
            <div className="col-sm-10">
              <button
                type="button"
                className="btn"
                style={{ marginLeft: 0 }}
                onClick={this.simpleLogin}>
                Login
              </button>
              <button type="button" className="btn" style={{ marginLeft: 10 }} onClick={this.login}>
                Login with FIDO U2F
              </button>
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label" />
            <div className="col-sm-10">
              <p>{!this.state.error && this.state.message}</p>
              <p style={{ color: 'red', width: '100%', textAlign: 'left' }}>
                {!!this.state.error && this.state.error}
              </p>
            </div>
          </div>
        </form>
        <p>
          <img src="/__otoroshi_assets/images/otoroshi-logo-color.png" style={{ width: 300 }} />
        </p>
      </div>
    );
  }
}
