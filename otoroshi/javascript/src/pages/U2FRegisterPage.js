import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import { Link } from 'react-router-dom';
import moment from 'moment';
import faker from 'faker';

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

export class U2FRegisterPage extends Component {
  state = {
    email: '',
    label: '',
    password: '',
    passwordcheck: '',
    error: null,
    message: null,
  };

  columns = [
    {
      title: 'Username',
      content: item => item.username,
    },
    {
      title: 'Label',
      content: item => item.label,
    },
    {
      title: 'Created At',
      content: item => (item.createdAt ? item.createdAt : 0),
      cell: (v, item) =>
        item.createdAt ? moment(item.createdAt).format('DD/MM/YYYY HH:mm:ss') : '',
    },
    {
      title: 'Type',
      content: item => item.type && item.type === 'U2F',
      notFilterable: true,
      cell: (v, item) => {
        if (item.type && item.type === 'U2F') {
          return <i className="fas fa-key" />;
        } else if (item.type && item.type === 'WEBAUTHN') {
          return <i className="fas fa-key" />;
        } else {
          return <i className="far fa-user" />;
        }
      },
      style: { width: 50, textAlign: 'center' },
    },
    {
      title: 'Action',
      style: { textAlign: 'center', width: 150 },
      notSortable: true,
      notFilterable: true,
      content: item => item,
      cell: (v, item, table) => {
        return (
          <button
            type="button"
            className="btn btn-danger btn-xs"
            onClick={e =>
              this.discardAdmin(
                e,
                item.username,
                item.registration ? item.registration.keyHandle : null,
                table,
                item.type
              )
            }>
            <i className="glyphicon glyphicon-fire" /> Discard User
          </button>
        );
      },
    },
  ];

  componentDidMount() {
    this.props.setTitle(`Register new admin`);
  }

  onChange = e => {
    this.setState({ [e.target.name]: e.target.value });
  };

  handleError = err => {
    this.setState({ error: err.message });
  };

  handleErrorWithMessage = message => () => {
    this.setState({ error: message });
  };

  register = e => {
    if (e && e.preventDefault) {
      e.preventDefault();
    }
    const username = this.state.email;
    const password = this.state.password;
    const passwordcheck = this.state.passwordcheck;
    const label = this.state.label;
    if (password !== passwordcheck) {
      return window.newAlert('Password does not match !!!', 'Password error');
    }
    fetch(`/bo/u2f/register/start`, {
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
      .then(r => r.json(), this.handleError)
      .then(payload => {
        const username = payload.username;
        const request = payload.data;
        this.setState({
          message: 'Initializing registration, now touch your blinking U2F device ...',
        });
        u2f.register(request.registerRequests, request.authenticateRequests, data => {
          console.log(data);
          if (data.errorCode) {
            this.setState({ error: `U2F failed with error ${data.errorCode}` });
          } else {
            this.setState({ message: 'Finishing registration ...' });
            fetch(`/bo/u2f/register/finish`, {
              method: 'POST',
              credentials: 'include',
              headers: {
                Accept: 'application/json',
                'Content-Type': 'application/json',
              },
              body: JSON.stringify({
                username: username,
                password,
                label,
                tokenResponse: data,
              }),
            })
              .then(r => r.json(), this.handleError)
              .then(data => {
                console.log(data);
                this.setState({
                  error: null,
                  email: '',
                  label: '',
                  password: '',
                  passwordcheck: '',
                  message: `Registration done for '${data.username}'`,
                });
                if (this.table) this.table.update();
              }, this.handleError);
          }
        });
      }, this.handleError);
  };

  simpleRegister = e => {
    if (e && e.preventDefault) {
      e.preventDefault();
    }
    const username = this.state.email;
    const password = this.state.password;
    const passwordcheck = this.state.passwordcheck;
    const label = this.state.label;
    if (password !== passwordcheck) {
      return window.newAlert('Password does not match !!!', 'Password error');
    }
    fetch(`/bo/simple/admins`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        username,
        password,
        label,
      }),
    })
      .then(r => r.json(), this.handleError)
      .then(data => {
        if (this.table) this.table.update();
        this.setState({
          error: null,
          email: '',
          label: '',
          password: '',
          passwordcheck: '',
          message: `Registration done for '${data.username}'`,
        });
      }, this.handleError);
  };

  registerWebAuthn = e => {
    if (e && e.preventDefault) {
      e.preventDefault();
    }

    const username = this.state.email;
    const password = this.state.password;
    const passwordcheck = this.state.passwordcheck;
    const label = this.state.label;

    if (password !== passwordcheck) {
      return window.newAlert('Password does not match !!!', 'Password error');
    }

    return fetch('/bo/webauthn/register/start', {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        username,
        password,
        label,
        origin: window.location.origin,
      }),
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
            this.handleErrorWithMessage('Webauthn error')
          )
          .then(credentials => {
            const json = responseToObject(credentials);
            return fetch('/bo/webauthn/register/finish', {
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
                if (this.table) this.table.update();
                this.setState({
                  error: null,
                  email: '',
                  label: '',
                  password: '',
                  passwordcheck: '',
                  message: `Registration done for '${username}'`,
                });
              });
          }, this.handleErrorWithMessage('Webauthn error'))
          .catch(this.handleError);
      });
  };

  discardAdmin = (e, username, id, table, type) => {
    if (e && e.preventDefault) e.preventDefault();
    window
      .newConfirm(`Are you sure that you want to discard admin user for ${username} ?`)
      .then(ok => {
        if (ok) {
          BackOfficeServices.discardAdmin(username, id, type).then(() => {
            setTimeout(() => {
              table.update();
              //window.location.href = '/bo/dashboard/admins';
            }, 1000);
          });
        }
      });
  };

  render() {
    return (
      <div>
        {this.props.env && this.props.env.changePassword && (
          <div class="alert alert-danger" role="alert">
            You are using the default admin account with the default password. You should create a
            new admin account.
          </div>
        )}
        <form className="form-horizontal">
          <div className="form-group">
            <label className="col-sm-2 control-label">Label</label>
            <div className="col-sm-10">
              <input
                type="text"
                className="form-control"
                name="label"
                value={this.state.label}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label">Email</label>
            <div className="col-sm-10">
              <input
                type="text"
                className="form-control"
                name="email"
                value={this.state.email}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label">Password</label>
            <div className="col-sm-10">
              <input
                type="password"
                className="form-control"
                name="password"
                value={this.state.password}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label">Re-type Password</label>
            <div className="col-sm-10">
              <input
                type="password"
                className="form-control"
                name="passwordcheck"
                value={this.state.passwordcheck}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label" />
            <div className="col-sm-10">
              <button type="button" className="btn btn-success" onClick={this.simpleRegister}>
                Register Admin
              </button>
              <button
                type="button"
                className="btn btn-success hide"
                style={{ marginLeft: 10 }}
                onClick={this.register}>
                Register FIDO U2F Admin
              </button>
              <button
                type="button"
                className="btn btn-success"
                style={{ marginLeft: 10 }}
                onClick={this.registerWebAuthn}>
                Register Admin with WebAuthn
              </button>
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label" />
            <div className="col-sm-10">
              <p>{!this.state.error && this.state.message}</p>
              <p style={{ color: 'red' }}>{!!this.state.error && this.state.error}</p>
            </div>
          </div>
        </form>
        <hr />
        <Table
          parentProps={this.props}
          selfUrl="admins"
          defaultTitle="Register new admin"
          defaultValue={() => ({})}
          itemName="session"
          columns={this.columns}
          fetchItems={BackOfficeServices.fetchAdmins}
          showActions={false}
          showLink={false}
          injectTable={t => (this.table = t)}
          extractKey={item => (item.registration ? item.registration.keyHandle : item.username)}
        />
      </div>
    );
  }
}
