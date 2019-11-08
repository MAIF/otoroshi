import React, { Component } from 'react';
import moment from 'moment';

function Base64Url() {
  let chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';

  // Use a lookup table to find the index.
  let lookup = new Uint8Array(256);
  for (let i = 0; i < chars.length; i++) {
    lookup[chars.charCodeAt(i)] = i;
  }

  let encode = function(arraybuffer) {
    let bytes = new Uint8Array(arraybuffer),
      i,
      len = bytes.length,
      base64url = '';

    for (i = 0; i < len; i += 3) {
      base64url += chars[bytes[i] >> 2];
      base64url += chars[((bytes[i] & 3) << 4) | (bytes[i + 1] >> 4)];
      base64url += chars[((bytes[i + 1] & 15) << 2) | (bytes[i + 2] >> 6)];
      base64url += chars[bytes[i + 2] & 63];
    }

    if (len % 3 === 2) {
      base64url = base64url.substring(0, base64url.length - 1);
    } else if (len % 3 === 1) {
      base64url = base64url.substring(0, base64url.length - 2);
    }

    return base64url;
  };

  let decode = function(base64string) {
    let bufferLength = base64string.length * 0.75,
      len = base64string.length,
      i,
      p = 0,
      encoded1,
      encoded2,
      encoded3,
      encoded4;

    let bytes = new Uint8Array(bufferLength);

    for (i = 0; i < len; i += 4) {
      encoded1 = lookup[base64string.charCodeAt(i)];
      encoded2 = lookup[base64string.charCodeAt(i + 1)];
      encoded3 = lookup[base64string.charCodeAt(i + 2)];
      encoded4 = lookup[base64string.charCodeAt(i + 3)];

      bytes[p++] = (encoded1 << 2) | (encoded2 >> 4);
      bytes[p++] = ((encoded2 & 15) << 4) | (encoded3 >> 2);
      bytes[p++] = ((encoded3 & 3) << 6) | (encoded4 & 63);
    }

    return bytes.buffer;
  };

  return {
    decode: decode,
    encode: encode,
    fromByteArray: encode,
    toByteArray: decode,
  };
}

const base64url = Base64Url();

function responseToObject(response) {
  if (response.u2fResponse) {
    return response;
  } else {
    let clientExtensionResults = {};

    try {
      clientExtensionResults = response.getClientExtensionResults();
    } catch (e) {
      console.error('getClientExtensionResults failed', e);
    }

    if (response.response.attestationObject) {
      return {
        type: response.type,
        id: response.id,
        response: {
          attestationObject: base64url.fromByteArray(response.response.attestationObject),
          clientDataJSON: base64url.fromByteArray(response.response.clientDataJSON),
        },
        clientExtensionResults,
      };
    } else {
      return {
        type: response.type,
        id: response.id,
        response: {
          authenticatorData: base64url.fromByteArray(response.response.authenticatorData),
          clientDataJSON: base64url.fromByteArray(response.response.clientDataJSON),
          signature: base64url.fromByteArray(response.response.signature),
          userHandle:
            response.response.userHandle && base64url.fromByteArray(response.response.userHandle),
        },
        clientExtensionResults,
      };
    }
  }
}

export class SelfUpdatePage extends Component {
  state = {
    email: this.props.user.email,
    name: this.props.user.name,
    password: '',
    newPassword: '',
    reNewPassword: '',
    webauthn: this.props.webauthn,
    mustRegWebauthnDevice: this.props.user.mustRegWebauthnDevice,
    hasWebauthnDeviceReg: this.props.user.hasWebauthnDeviceReg,
    duration: moment.duration(this.props.expires, 'ms'),
    expired: false,
  };

  updateDuration = () => {
    const duration = this.state.duration;
    if (duration.hours() <= 0 && duration.minutes() <= 0 && duration.seconds() <= 0) {
      this.setState({ expired: true });
      clearInterval(this.interval);
    } else {
      this.setState({ duration: this.state.duration.subtract(1, 'seconds') });
    }
  };

  componentDidMount() {
    this.interval = setInterval(this.updateDuration, 1000);
  }

  componentWillUnmount() {
    clearInterval(this.interval);
  }

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

  save = () => {
    const username = this.state.email;
    const password = this.state.password.trim();
    const newPassword = this.state.newPassword.trim();
    const reNewPassword = this.state.reNewPassword.trim();
    const label = this.state.name.trim();
    if (!password || password.length === 0) {
      window.newAlert('You have to provide your current password', 'Password error');
      return Promise.reject('You have to provide your current password');
    }
    if (!!newPassword && !reNewPassword) {
      window.newAlert('Passwords does not match', 'Password error');
      return Promise.reject('Passwords does not match');
    }
    if (!!newPassword && !!reNewPassword && newPassword !== reNewPassword) {
      window.newAlert('Passwords does not match', 'Password error');
      return Promise.reject('Passwords does not match');
    }
    return fetch(`/privateapps/profile?session=${this.props.session}`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        username,
        password: password.length > 0 ? password : null,
        label: label.length > 0 ? label : username,
        newPassword: newPassword.length > 0 ? newPassword : null,
        reNewPassword: reNewPassword.length > 0 ? reNewPassword : null,
        origin: window.location.origin,
      }),
    })
      .then(r => r.json())
      .then(r => {
        this.setState({
          error: null,
          password: '',
          newPassword: '',
          reNewPassword: '',
          webauthn: this.state.webauthn,
          mustRegWebauthnDevice: this.state.webauthn,
        });
        return {
          username: r.email,
          password: newPassword.length > 0 ? newPassword : password.length > 0 ? password : null,
          label: r.name,
        };
      });
  };

  handleErrorWithMessage = message => e => {
    console.log('error', message, e);
    this.setState({ error: message });
  };

  delete = () => {
    return fetch(`/privateapps/registration?session=${this.props.session}`, {
      method: 'DELETE',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    }).then(r => r.json());
  };

  registerWebAuthn = e => {
    if (e && e.preventDefault) {
      e.preventDefault();
    }

    this.setState({ message: null });

    return this.save()
      .then(user => {
        this.delete().then(() => this.setState({ message: null, mustRegWebauthnDevice: true }));
        return user;
      })
      .then(user => {
        const username = user.username;
        const password = user.password;
        const label = user.label;
        const payload = {
          username,
          password,
          label,
          origin: window.location.origin,
        };
        return fetch(`/privateapps/register/start?session=${this.props.session}`, {
          method: 'POST',
          credentials: 'include',
          headers: {
            Accept: 'application/json',
            'Content-Type': 'application/json',
          },
          body: JSON.stringify(payload),
        })
          .then(r => r.json())
          .then(resp => {
            const requestId = resp.requestId;
            const publicKeyCredentialCreationOptions = resp.request;
            const handle = publicKeyCredentialCreationOptions.user.id + '';
            publicKeyCredentialCreationOptions.challenge = base64url.decode(
              publicKeyCredentialCreationOptions.challenge
            );
            publicKeyCredentialCreationOptions.user.id = base64url.decode(
              publicKeyCredentialCreationOptions.user.id
            );
            return navigator.credentials
              .create(
                {
                  publicKey: publicKeyCredentialCreationOptions,
                },
                this.handleErrorWithMessage('Webauthn error 1')
              )
              .then(credentials => {
                const json = responseToObject(credentials);
                return fetch(`/privateapps/register/finish?session=${this.props.session}`, {
                  method: 'POST',
                  credentials: 'include',
                  headers: {
                    Accept: 'application/json',
                    'Content-Type': 'application/json',
                  },
                  body: JSON.stringify({
                    requestId,
                    webauthn: json,
                    otoroshi: {
                      origin: window.location.origin,
                      username,
                      password,
                      label,
                      handle,
                    },
                  }),
                })
                  .then(r => r.json())
                  .then(resp => {
                    this.setState({
                      error: null,
                      password: '',
                      newPassword: '',
                      reNewPassword: '',
                      webauthn: this.state.webauthn,
                      mustRegWebauthnDevice: this.state.webauthn,
                      hasWebauthnDeviceReg: true,
                      message: `Registration done for '${username}'`,
                    });
                  });
              }, this.handleErrorWithMessage('Webauthn error 2'))
              .catch(this.handleError);
          });
      });
  };

  render() {
    const duration = this.state.duration;
    if (this.state.expired) {
      return (
        <div className="jumbotron">
          <h3 style={{ marginBottom: 40 }}>Update your profile</h3>
          <h5>The link has expired, please ask another link to your administrator</h5>
        </div>
      );
    }
    return (
      <div className="jumbotron">
        <h3 style={{ marginBottom: 40 }}>Update your profile</h3>
        <h5>
          this link will expire in {duration.humanize()} ({('0' + duration.hours()).slice(-2)}:
          {('0' + duration.minutes()).slice(-2)}:{('0' + duration.seconds()).slice(-2)})
        </h5>
        <form className="form-horizontal" style={{ textAlign: 'left' }}>
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
            <label className="col-sm-2 control-label">
              Password <small style={{ color: 'rgb(181, 179, 179)' }}>(required)</small>
            </label>
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
            <label className="col-sm-2 control-label">
              New password <small style={{ color: 'rgb(181, 179, 179)' }}>(again)</small>
            </label>
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
          {this.state.webauthn && this.state.mustRegWebauthnDevice && (
            <div className="form-group">
              <label className="col-sm-2 control-label" />
              <div className="col-sm-10">
                {this.state.hasWebauthnDeviceReg && (
                  <p style={{ width: '100%', textAlign: 'left' }}>
                    The auth. module requires strong authentication with Webauthn compatible device.
                    You have one device already registered
                  </p>
                )}
                {!this.state.hasWebauthnDeviceReg && (
                  <p style={{ color: 'red', width: '100%', textAlign: 'left' }}>
                    The auth. module requires strong authentication with Webauthn compatible device.
                    You have to register a Webauthn compatible device or you won't be able to log
                    in.
                  </p>
                )}
              </div>
            </div>
          )}
          <div className="form-group">
            <label className="col-sm-2 control-label" />
            <div className="col-sm-10">
              <button type="button" className="btn" style={{ marginLeft: 0 }} onClick={this.save}>
                Update name and/or password
              </button>
              {this.state.webauthn && (
                <button
                  type="button"
                  className="btn"
                  style={{ marginLeft: 0 }}
                  onClick={this.registerWebAuthn}>
                  {this.state.hasWebauthnDeviceReg
                    ? 'Register another webauthn device'
                    : 'Register a new webauthn device'}
                </button>
              )}
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label" />
            <div className="col-sm-10">
              <p style={{ color: 'green', width: '100%', textAlign: 'left' }}>
                {!this.state.error && this.state.message}
              </p>
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
