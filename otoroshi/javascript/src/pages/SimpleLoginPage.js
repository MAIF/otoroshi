import React, { Component } from 'react';

export class SimpleLoginPage extends Component {

  state = {
    email: ''
  }

  getLink = email => {
    if (this.props.redirect.length <= 0) {
      return `/privateapps/generic/login?route=${this.props.route}&email=${email}`
    } else {
      return `/privateapps/generic/login?redirect=${this.props.redirect}&route=${this.props.route}&email=${email}`
    }
  }

  redirect = e => {
    e.preventDefault();

    const loginPage = this.getLink(this.state.email);
    console.log(loginPage)
    try {
      fetch(loginPage, {
        credentials: 'include',
        redirect: 'manual'
      })
        .then(r => {
          if (r.status === 500) {
            return { 'Otoroshi-Error': 'Something wrong happened, try again.' };
          } else if (r.status > 300 && r.status < 400) {
            window.location.replace(r.response.Location)
          } else if (r.headers.get('Content-Type') === 'application/json') {
            return r.json();
          } else {
            window.location.replace(loginPage)
          }
        })
        .then(error => {
          console.log("ERROR")
          this.setState({ error })
        })
    } catch (err) {
      console.log("ERROROROR")
    }
  }

  render() {
    return (
      <div style={{
        background: '#494948',
        border: `5px solid ${this.state.error ? 'var(--color-red)' : '#fff'}`,
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
          <form onSubmit={this.redirect}>
            <input
              type="email"
              value={this.state.email}
              onChange={(e) => this.setState({
                email: e.target.value,
                error: undefined
              })}
              className="form-control"
              placeholder="Email"
              autoFocus
            />

            {this.state.error && <p className='my-3 text-center' style={{
              color: 'var(--color-red)',
              fontWeight: 'bold'
            }}>{this.state.error['Otoroshi-Error']}</p>}

            <div style={{
              display: 'flex'
            }}>
              <button className='btn btn-primaryColor mt-3 flex-fill' type="submit">Continue</button>
            </div>
          </form>
        </div>
      </div>
    );
  }
}
