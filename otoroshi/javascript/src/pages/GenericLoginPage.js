import React, { Component } from 'react';

export class GenericLoginPage extends Component {
  state = {
    username: '',
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

  render() {
    return (
      <div className="jumbotron">
        <h3 style={{ marginBottom: 40 }}>Login</h3>
        <form
          className="form-horizontal"
          style={{ textAlign: 'left' }}
          method={this.props.method}
          action={this.props.action}>
          <input type="hidden" name="token" className="form-control" value={this.props.token} />
          <div className="form-group">
            <label className="col-sm-2 control-label">Username</label>
            <div className="col-sm-10">
              <input
                type="text"
                name="username"
                className="form-control"
                value={this.props.username}
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
                type="submit"
                className="btn"
                style={{ marginLeft: 0 }}
                onClick={this.simpleLogin}>
                Login
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
