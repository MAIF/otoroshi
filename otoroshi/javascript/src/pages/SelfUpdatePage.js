import React, { Component } from 'react';

export class SelfUpdatePage extends Component {
  state = {
    email: this.props.user.email,
    name: this.props.user.name,
    password: '',
    newPassword: '',
    reNewPassword: ''
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
    console.log(this.props.user)
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
                value={this.state.email}
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
            <label className="col-sm-2 control-label">Password <small style={{ color: 'rgb(181, 179, 179)' }}>(required)</small></label>
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
            <label className="col-sm-2 control-label">New password</label>
            <div className="col-sm-10">
              <input
                type="password"
                name="newPassword"
                className="form-control"
                value={this.state.newPassword}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label">New password <small style={{ color: 'rgb(181, 179, 179)' }}>(again)</small></label>
            <div className="col-sm-10">
              <input
                type="password"
                name="reNewPassword"
                className="form-control"
                value={this.state.reNewPassword}
                onChange={this.onChange}
              />
            </div>
          </div>
          {this.props.webauthn && this.props.user.mustRegWebauthnDevice && <div className="form-group">
            <label className="col-sm-2 control-label" />
            <div className="col-sm-10">
              {this.props.user.hasWebauthnDeviceReg && <p style={{ width: '100%', textAlign: 'left' }}>The auth. module requires strong authentication with Webauthn compatible device</p>}
              {!this.props.user.hasWebauthnDeviceReg && <p style={{ color: 'red', width: '100%', textAlign: 'left' }}>The auth. module requires strong authentication with Webauthn compatible device. You have to register a Webauthn compatible device or you won't be able to log in.</p>}
            </div>
          </div>}
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