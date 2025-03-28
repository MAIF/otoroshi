import React, { Component } from 'react';

export class SimpleLoginPage extends Component {
  state = {
    email: '',
  };

  getLink = (email) => {
    if (this.props.redirect.length <= 0) {
      return `/privateapps/generic/login?route=${this.props.route}&email=${email}`;
    } else {
      return `/privateapps/generic/login?redirect=${this.props.redirect}&route=${this.props.route}&email=${email}`;
    }
  };

  redirect = (e) => {
    e.preventDefault();

    const loginPage = this.getLink(this.state.email);
    try {
      fetch(loginPage, {
        credentials: 'include',
        redirect: 'manual',
      })
        .then((r) => {
          if (r.status === 500) {
            return { 'Otoroshi-Error': 'Something wrong happened, try again.' };
          } else if (r.status > 300 && r.status < 400) {
            window.location.replace(r.response.Location);
          } else if (r.headers.get('Content-Type') === 'application/json') {
            return r.json();
          } else {
            window.location.replace(loginPage);
          }
        })
        .then((error) => {
          console.log('ERROR');
          this.setState({ error });
        });
    } catch (err) {
      console.log('ERROROROR');
    }
  };

  render() {
    return (
      <div
        className="login-card"
        style={{
          borderColor: `${this.state.error ? 'var(--color-red)' : '#fff'}`,
        }}
      >
        <img src={this.props.otoroshiLogo} />
        <div className="login-card-title">
          <h1>Welcome</h1>
          <p>Log in to Otoroshi to continue</p>
        </div>

        <div className="login-card-body">
          <form onSubmit={this.redirect}>
            <input
              type="email"
              value={this.state.email}
              onChange={(e) =>
                this.setState({
                  email: e.target.value,
                  error: undefined,
                })
              }
              className="form-control"
              placeholder="Email"
              autoFocus
            />

            {this.state.error && (
              <p
                className="my-3 text-center"
                style={{
                  color: 'var(--color-red)',
                  fontWeight: 'bold',
                }}
              >
                {this.state.error['Otoroshi-Error']}
              </p>
            )}

            <div className="d-flex justify-content-center">
              <button className="btn btn-primaryColor mt-3" type="submit">
                Continue
              </button>
            </div>
          </form>
        </div>
      </div>
    );
  }
}
