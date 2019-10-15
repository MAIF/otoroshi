import React, { Component } from 'react';

export class SelfUpdatePage extends Component {
  state = {
    user: this.props.user,
    name: this.props.user.names,
    password: '',
    password2: ''
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
        <h3 style={{ marginBottom: 40 }}>Update your profile</h3>
        <form
          className="form-horizontal"
          style={{ textAlign: 'left' }}>
          <div className="form-group">
            <label className="col-sm-2 control-label">Email</label>
            <div className="col-sm-10">
              <input
                type="text"
                name="username"
                className="form-control"
                disabled
                value={this.state.user.email}
              />
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label">Name</label>
            <div className="col-sm-10">
              <input
                type="text"
                name="name"
                className="form-control"
                value={this.state.name}
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
                value={this.state.password}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label">Password (again)</label>
            <div className="col-sm-10">
              <input
                type="password"
                name="password"
                className="form-control"
                value={this.state.password2}
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
                onClick={this.update}>
                Update name and/or password
              </button>
              {this.props.webauthn && <button
                type="submit"
                className="btn"
                style={{ marginLeft: 0 }}
                onClick={this.register}>
                Register webauthn device
              </button>}
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
      </div>
    );
  }
}