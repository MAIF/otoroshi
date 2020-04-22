import React, { Component } from 'react';

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
          <img src={this.props.otoroshiLogo} style={{ width: 300 }} />
        </p>
      </div>
    );
  }
}

export class GenericLoginPageWithWebAuthn extends Component {
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

  webAuthnLogin = e => {
    if (e && e.preventDefault) {
      e.preventDefault();
    }
    const username = this.state.username;
    const password = this.state.password;
    const label = this.state.label;
    this.setState({ message: null });
    console.log('fetch1');
    fetch(this.props.action, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        'WebAuthn-Login-Step': 'start',
      },
      body: JSON.stringify({
        username,
        password,
        label,
        origin: window.location.origin,
      }),
    })
      .then(r => {
        if (r.status === 200 || r.status == 201) {
          return r.json();
        } else {
          throw new Error('Login error, sorry ...');
        }
      }, this.handleError('Login error, sorry ...'))
      .then(payload => {
        const requestId = payload.requestId;
        const options = payload.request.publicKeyCredentialRequestOptions;
        options.challenge = base64url.decode(options.challenge);
        options.allowCredentials = options.allowCredentials.map(c => {
          c.id = base64url.decode(c.id);
          return c;
        });
        console.log(options);
        return navigator.credentials
          .get(
            {
              publicKey: options,
            },
            this.handleError('Webauthn error, sorry ...')
          )
          .then(credentials => {
            const json = responseToObject(credentials);
            console.log('fetch2');
            return fetch(this.props.action, {
              method: 'POST',
              credentials: 'include',
              redirect: 'manual',
              headers: {
                Accept: 'application/json',
                'Content-Type': 'application/json',
                'WebAuthn-Login-Step': 'finish',
              },
              body: JSON.stringify({
                requestId,
                webauthn: json,
                otoroshi: {
                  origin: window.location.origin,
                  username,
                  password,
                },
              }),
            })
              .then(r => r.json())
              .then(r => {
                const location = r.location;
                this.setState(
                  { error: null, email: '', password: '', message: `Login successfully` },
                  () => {
                    window.location.href = location;
                  }
                );
              }, this.handleError('Authentication error, sorry ...'));
          });
      }, this.handleError('Login error, sorry ...'));
  };

  render() {
    return (
      <div className="jumbotron">
        <h3 style={{ marginBottom: 40 }}>Login</h3>
        <form
          className="form-horizontal"
          style={{ textAlign: 'left' }}
          onSubmit={this.webAuthnLogin}
          _method={this.props.method}
          _action={this.props.action}>
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
                onClick={this.webAuthnLogin}>
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
          <img src={this.props.otoroshiLogo} style={{ width: 300 }} />
        </p>
      </div>
    );
  }
}
