import React, { Component } from 'react';

export class PasswordLessLoginPage extends Component {
  state = {
    phase: 1,
    username: '',
    code: ''
  };

  sendCode = () => {
    this.setState({ fetching: true });
    const url = window.location.href
    fetch(url + "/passwordless/start", {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ username: this.state.username })
    }).then(r => r.json()).then(r => {
      console.log(r)
      this.setState({ phase: 2, fetching: false });
    })
  }

  login = () => {
    this.setState({ fetching: true });
    const url = window.location.href
    fetch(url + "/passwordless/end", {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ username: this.state.username, code: this.state.code })
    }).then(r => r.json()).then(r => {
      console.log(r)
      // window.location.reload();
    })
  }

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
          <p>Log in to continue</p>
        </div>

        <div className="login-card-body">
          <form>
            <input
              type="text"
              disabled={this.state.phase === 2}
              value={this.state.username}
              onChange={(e) =>
                this.setState({
                  username: e.target.value,
                  error: undefined,
                })
              }
              className="form-control"
              placeholder="Username"
              autoFocus
            />
            {this.state.phase === 2 && <input
              type="text"
              value={this.state.code}
              onChange={(e) =>
                this.setState({
                  code: e.target.value,
                  error: undefined,
                })
              }
              className="form-control"
              placeholder="Received code"
              autoFocus
            />}


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
              {this.state.phase === 1 &&
                <button className="btn btn-primaryColor mt-3" type="button" disabled={this.setState.fetching} onClick={this.sendCode}>
                  Send me a code
                </button>}
              {this.state.phase === 2 &&
                <button className="btn btn-primaryColor mt-3" type="button" disabled={this.setState.fetching} onClick={this.login}>
                  Login
                </button>}
            </div>
          </form>
        </div>
      </div>
    );
  }
}
