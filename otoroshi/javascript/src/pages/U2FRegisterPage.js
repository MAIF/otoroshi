import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table, Form, TextInput, ArrayInput, SelectInput } from '../components/inputs';
import { Link } from 'react-router-dom';
import moment from 'moment';
import faker from 'faker';
import bcrypt from 'bcryptjs';
import { Separator } from '../components/Separator';
import { JsonObjectAsCodeInput } from '../components/inputs/CodeInput';

function Base64Url() {
  let chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';

  // Use a lookup table to find the index.
  let lookup = new Uint8Array(256);
  for (let i = 0; i < chars.length; i++) {
    lookup[chars.charCodeAt(i)] = i;
  }

  let encode = function (arraybuffer) {
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

  let decode = function (base64string) {
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
      content: (item) => item.username,
    },
    {
      title: 'Label',
      content: (item) => item.label,
    },
    {
      title: 'Created At',
      content: (item) => (item.createdAt ? item.createdAt : 0),
      cell: (v, item) =>
        item.createdAt ? moment(item.createdAt).format('DD/MM/YYYY HH:mm:ss') : '',
    },
    {
      title: 'Type',
      content: (item) => item.type && item.type === 'WEBAUTHN',
      notFilterable: true,
      cell: (v, item) => {
        if (item.type && item.type === 'WEBAUTHN') {
          return <i className="fas fa-key" />;
        } else {
          return <i className="far fa-user" />;
        }
      },
      style: { width: 50, textAlign: 'center' },
    },
    {
      title: 'Actions',
      style: { textAlign: 'right', width: 250 },
      notSortable: true,
      notFilterable: true,
      content: (item) => item,
      cell: (v, item, table) => {
        return (
          <>
            <button
              type="button"
              className="btn-success btn-xs mr-5"
              onClick={(e) => this.updateOtherUser(item)}>
              <i className="fas fa-edit" /> Edit User
            </button>
            <button
              type="button"
              className="btn-danger btn-xs"
              onClick={(e) =>
                this.discardAdmin(
                  e,
                  item.username,
                  item.registration ? item.registration.keyHandle : null,
                  table,
                  item.type
                )
              }>
              <i className="fas fa-fire" /> Discard User
            </button>
          </>
        );
      },
    },
  ];

  componentDidMount() {
    this.props.setTitle(`Local admins`);
  }

  onChange = (e) => {
    this.setState({ [e.target.name]: e.target.value });
  };

  handleError = (err) => {
    this.setState({ error: err.message });
  };

  handleErrorWithMessage = (message) => () => {
    this.setState({ error: message });
  };

  discardAdmin = (e, username, id, table, type) => {
    if (e && e.preventDefault) e.preventDefault();
    window
      .newConfirm(`Are you sure that you want to discard admin user for ${username} ?`)
      .then((ok) => {
        if (ok) {
          BackOfficeServices.discardAdmin(username, id, type).then(() => {
            setTimeout(() => {
              table.update();
              if (username === window.__userid) {
                // force logout
                fetch('/backoffice/auth0/logout', {
                  credentials: 'include',
                }).then(() => {
                  window.location.href = '/bo/dashboard/admins';
                });
              }
            }, 1000);
          });
        }
      });
  };

  createAdmin = () => {
    BackOfficeServices.fetchAdmins().then((admins) => {
      window
        .popup(
          'Register new admin',
          (ok, cancel) => (
            <RegisterAdminModal ok={ok} cancel={cancel} users={admins} mode="create" />
          ),
          { style: { width: '100%' } }
        )
        .then((form) => {
          if (this.table) this.table.update();
        });
    });
  };

  updateCurrentUser = () => {
    BackOfficeServices.fetchAdmins().then((admins) => {
      const user = admins.filter((a) => a.username === window.__userid)[0];
      window
        .popup(
          'Update admin',
          (ok, cancel) => (
            <RegisterAdminModal ok={ok} cancel={cancel} user={user} users={admins} mode="update" />
          ),
          { style: { width: '100%' } }
        )
        .then((form) => {
          if (this.table) this.table.update();
        });
    });
  };

  updateOtherUser = (_user) => {
    BackOfficeServices.fetchAdmins().then((admins) => {
      const user = admins.filter((a) => a.username === _user.username)[0];
      window
        .popup(
          'Update admin',
          (ok, cancel) => (
            <AdminEditionModal ok={ok} cancel={cancel} user={user} users={admins} mode="update" />
          ),
          { style: { width: '100%' } }
        )
        .then((form) => {
          if (this.table) this.table.update();
        });
    });
  };

  render() {
    // TODO: see if tenant admin can do it too
    if (!window.__user.tenantAdmin) {
      return null;
    }
    return (
      <div>
        {this.props.env && this.props.env.changePassword && (
          <div class="alert alert-danger" role="alert">
            You are using the default admin account. You should create a new admin account and
            delete the default one.
          </div>
        )}
        <form className="display--none">
          <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr">
            <label >Label</label>
            <div>
              <input
                type="text"
                name="label"
                value={this.state.label}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr">
            <label >Email</label>
            <div>
              <input
                type="text"
                name="email"
                value={this.state.email}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr">
            <label >Password</label>
            <div>
              <input
                type="password"
                name="password"
                value={this.state.password}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr">
            <label >Re-type Password</label>
            <div>
              <input
                type="password"
                name="passwordcheck"
                value={this.state.passwordcheck}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr">
            <label  />
            <div>
              <button type="button" className="btn-success" onClick={this.simpleRegister}>
                Register Admin
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
          <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr">
            <label  />
            <div>
              <p>{!this.state.error && this.state.message}</p>
              <p style={{ color: 'red' }}>{!!this.state.error && this.state.error}</p>
            </div>
          </div>
        </form>
        <hr className="display--none" />
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
          injectTable={(t) => (this.table = t)}
          extractKey={(item) => (item.registration ? item.registration.keyHandle : item.username)}
          injectTopBar={() => (
            <>
              <div className="btn__group">
                <button type="button" className="btn-info mt-5" onClick={this.createAdmin}>
                  <i className="fas fa-plus-circle" /> Register admin.
                </button>
              </div>
            </>
          )}
        />
      </div>
    );
  }
}

export class RegisterAdminModal extends Component {
  state = {
    mode: this.props.mode || 'create',
    email: this.props.mode === 'update' && this.props.user ? this.props.user.username : '',
    label: this.props.mode === 'update' && this.props.user ? this.props.user.label : '',
    rights: this.props.mode === 'update' && this.props.user ? this.props.user.rights : [],
    oldPassword: '',
    password: '',
    passwordcheck: '',
    error: null,
    message: null,
  };

  onChange = (e) => {
    this.setState({ [e.target.name]: e.target.value });
  };

  handleError = (err) => {
    this.setState({ error: err.message });
  };

  handleErrorWithMessage = (message) => (e) => {
    this.setState({ error: message + ' ' + e.message ? e.message : e });
  };

  verifyAndDestroy = (f) => {
    if (this.props.user && this.props.mode === 'update' && this.state.oldPassword) {
      if (bcrypt.compareSync(this.state.oldPassword, this.props.user.password)) {
        BackOfficeServices.discardAdmin(
          this.props.user.username,
          this.props.user.registration ? this.props.user.registration.keyHandle : null,
          this.props.user.type
        ).then(() => {
          f();
        });
      } else {
        this.props.ok(this.state);
        setTimeout(() => {
          window.newAlert('Old passsword does not match !', 'Error');
        }, 500);
      }
    } else {
      // TODO: test if password
      // if (!this.state.oldPassword) {
      //   this.props.ok(this.state);
      //   setTimeout(() => {
      //     window.newAlert('You did not provide your password', 'Error');
      //   }, 500);
      // } else {
      f();
      // }
    }
  };

  simpleRegister = (e) =>
    this.verifyAndDestroy(() => {
      if (e && e.preventDefault) {
        e.preventDefault();
      }
      const username = this.state.email;
      const password =
        this.props.mode === 'update' && !this.state.password && this.state.oldPassword
          ? this.state.oldPassword
          : this.state.password;
      const passwordcheck =
        this.props.mode === 'update' && !this.state.password && this.state.oldPassword
          ? this.state.oldPassword
          : this.state.passwordcheck;
      const label = this.state.label;
      if (password !== passwordcheck) {
        return window.newAlert('Password does not match !!!', 'Password error');
      }
      // fetch(`/bo/simple/admins`, {
      fetch(`/bo/api/proxy/api/admins/simple`, {
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
        .then((r) => r.json(), this.handleError)
        .then((data) => {
          if (this.table) this.table.update();
          this.setState({
            error: null,
            email: '',
            label: '',
            password: '',
            passwordcheck: '',
            message: `Registration done for '${data.username}'`,
          });
          this.props.ok(this.state);
        }, this.handleError);
    });

  registerWebAuthn = (e) =>
    this.verifyAndDestroy(() => {
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
        .then((r) => r.json())
        .then((resp) => {
          const requestId = resp.requestId;
          const publicKeyCredentialCreationOptions = { ...resp.request };
          const handle = publicKeyCredentialCreationOptions.user.id + '';
          publicKeyCredentialCreationOptions.challenge = base64url.decode(
            publicKeyCredentialCreationOptions.challenge
          );
          publicKeyCredentialCreationOptions.user.id = base64url.decode(
            publicKeyCredentialCreationOptions.user.id
          );
          publicKeyCredentialCreationOptions.excludeCredentials = publicKeyCredentialCreationOptions.excludeCredentials.map(
            (c) => {
              return { ...c, id: base64url.decode(c.id) };
            }
          );
          return navigator.credentials
            .create(
              {
                publicKey: publicKeyCredentialCreationOptions,
              },
              this.handleErrorWithMessage('Webauthn error')
            )
            .then((credentials) => {
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
                .then((r) => r.json())
                .then((resp) => {
                  if (this.table) this.table.update();
                  this.setState({
                    error: null,
                    email: '',
                    label: '',
                    password: '',
                    passwordcheck: '',
                    message: `Registration done for '${username}'`,
                  });
                  this.props.ok(this.state);
                });
            }, this.handleErrorWithMessage('Webauthn error'))
            .catch(this.handleError);
        });
    });

  render() {
    return (
      <>
        <div className="modal-body">
          <form>
            <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr">
              <label >Label</label>
              <div>
                <input
                  type="text"
                  name="label"
                  value={this.state.label}
                  onChange={this.onChange}
                />
              </div>
            </div>
            <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr">
              <label >Username</label>
              <div>
                <input
                  type="text"
                  name="email"
                  disabled={this.props.mode === 'update'}
                  value={this.state.email}
                  onChange={this.onChange}
                />
              </div>
            </div>
            {this.props.mode === 'update' && (
              <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr">
                <label >Old Password</label>
                <div>
                  <input
                    type="password"
                    name="oldPassword"
                    placeholder="type your current password (required)"
                    value={this.state.oldPassword}
                    onChange={this.onChange}
                  />
                </div>
              </div>
            )}
            <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr">
              <label >Password</label>
              <div>
                <input
                  type="password"
                  name="password"
                  placeholder={
                    this.props.mode === 'update' ? 'type your new password' : 'type your password'
                  }
                  value={this.state.password}
                  onChange={this.onChange}
                />
              </div>
            </div>
            <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr">
              <label >Re-type Password</label>
              <div>
                <input
                  type="password"
                  name="passwordcheck"
                  placeholder={
                    this.props.mode === 'update'
                      ? 're-type your new password'
                      : 're-type your password'
                  }
                  value={this.state.passwordcheck}
                  onChange={this.onChange}
                />
              </div>
            </div>
            <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr display--none">
              <label  />
              <div></div>
            </div>
            <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr">
              <label  />
              <div>
                <p>{!this.state.error && this.state.message}</p>
                <p style={{ color: 'red' }}>{!!this.state.error && this.state.error}</p>
              </div>
            </div>
          </form>
        </div>
        <div className="modal-footer">
          <button type="button" className="btn-danger mr-5 mb-5" onClick={this.props.cancel}>
            Cancel
          </button>
          <button type="button" className="btn-success mr-5 mb-5" onClick={this.simpleRegister}>
            {this.props.mode === 'update' ? 'Update' : 'Register'} Admin
          </button>
          <button
            type="button"
            className="btn-success mb-5"
            style={{ marginLeft: 10 }}
            onClick={this.registerWebAuthn}>
            {this.props.mode === 'update' ? 'Update' : 'Register'} Admin with WebAuthn
          </button>
          <button
            type="button"
            className="btn btn-success display--none"
            ref={(r) => (this.okRef = r)}
            onClick={(e) => this.props.ok(this.state)}>
            Create
          </button>
        </div>
      </>
    );
  }
}

export class AdminEditionModal extends Component {
  state = {
    mode: this.props.mode || 'create',
    user: { ...this.props.user },
    password1: '',
    password2: '',
  };

  onChange = (user) => {
    this.setState({ user });
  };

  handleError = (err) => {
    this.setState({ error: err.message });
  };

  handleErrorWithMessage = (message) => (e) => {
    this.setState({ error: message + ' ' + e.message ? e.message : e });
  };

  schema = {
    username: {
      type: 'string',
      props: {
        label: 'Username',
      },
    },
    label: {
      type: 'string',
      props: {
        label: 'Label',
      },
    },
    password: {
      type: 'string',
      props: {
        disabled: true,
        label: 'Password',
      },
    },
    type: {
      type: 'string',
      props: {
        disabled: true,
        label: 'Type',
      },
    },
    createdAt: {
      type: 'string',
      props: {
        disabled: true,
        label: 'Created at',
      },
    },
    metadata: {
      type: 'object',
      props: {
        label: 'Metadata',
      },
    },
    _rights: {
      type: UserRights,
      props: {},
    },
    rights: {
      type: JsonObjectAsCodeInput,
      props: {
        height: '200px',
      },
    },
    _loc: {
      type: 'location',
      props: {},
    },
  };

  flow = ['_loc', 'username', 'label', 'password', 'type', 'createdAt', 'metadata', 'rights'];

  onChange = (user) => {
    this.setState({ user });
  };

  save = (e) => {
    if (this.state.user.type === 'SIMPLE') {
      BackOfficeServices.updateSimpleAdmin(this.state.user);
    }
    if (this.state.user.type === 'WEBAUTHN') {
      BackOfficeServices.updateWebAuthnAdmin(this.state.user);
    }
    this.props.ok();
  };

  setPassword = (e) => {
    if (
      this.state.password1 &&
      this.state.password2 &&
      this.state.password1 !== '' &&
      this.state.password1 == this.state.password2
    ) {
      const salted = bcrypt.hashSync(this.state.password1, bcrypt.genSaltSync());
      this.setState({
        password1: '',
        password2: '',
        user: { ...this.state.user, password: salted },
      });
    }
  };

  render() {
    return (
      <>
        <div className="modal-body" style={{ maxHeight: '70vh', overflowY: 'auto' }}>
          <Form
            value={this.state.user}
            onChange={this.onChange}
            flow={this.flow}
            schema={this.schema}
            style={{ marginTop: 5 }}
          />
          <Separator />
          <div style={{ height: 10 }} />
          <form>
            <TextInput
              label="New password"
              type="password"
              value={this.state.password1}
              onChange={(e) => this.setState({ password1: e })}
            />
            <TextInput
              label="New password again"
              type="password"
              value={this.state.password2}
              onChange={(e) => this.setState({ password2: e })}
            />
            <div className="form__group mb-20 grid-template-col-xs-up__1fr-5fr">
              <label ></label>
              <div style={{ display: 'flex' }}>
                <button type="button" className="btn btn-success" onClick={this.setPassword}>
                  Change password
                </button>
              </div>
            </div>
          </form>
        </div>
        <div className="modal-footer">
          <button type="button" className="btn btn-danger" onClick={this.props.cancel}>
            Cancel
          </button>
          <button
            type="button"
            className="btn btn-success"
            style={{ marginLeft: 10 }}
            onClick={this.save}>
            Save
          </button>
        </div>
      </>
    );
  }
}

class UserRights extends Component {
  render() {
    console.log(this.props);
    return (
      <div style={{ outline: '1px solid red' }}>
        <ArrayInput
          label="Rights"
          value={this.props.rawValue.rights}
          onChange={(e) => this.props.changeValue('rights', e)}
          component={UserRight}
        />
        {/*<JsonObjectAsCodeInput {...this.props} height="200px" />*/}
      </div>
    );

    // this.props.rawValue.rights.map(r => <UserRight right={r} />);
  }
}

class UserRight extends Component {
  render() {
    return (
      <div style={{ outline: '1px solid green' }}>
        <SelectInput
          label="Organization"
          value={this.props.itemValue.tenant ? this.props.itemValue.tenant.split(':')[0] : '*'}
          onChange={(e) => console.log(e)}
          valuesFrom="/bo/api/proxy/api/tenants"
          transformer={(a) => ({
            value: a.id,
            label: a.name + ' - ' + a.description,
          })}
        />
        <ArrayInput
          label="Teams"
          value={
            this.props.itemValue.teams
              ? this.props.itemValue.teams.map((t) => (t ? t.split(':')[0] : '*'))
              : []
          }
          onChange={(e) => console.log(e)}
          valuesFrom="/bo/api/proxy/api/teams"
          transformer={(a) => ({
            value: a.id,
            label: a.name + ' - ' + a.description,
          })}
        />
      </div>
    );
  }
}
