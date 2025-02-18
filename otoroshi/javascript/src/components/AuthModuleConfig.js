import React, { Component, Suspense } from 'react';

import {
  TextInput,
  NumberInput,
  SelectInput,
  ObjectInput,
  BooleanInput,
  PasswordInput,
  ArrayInput,
  TextareaInput,
  Help,
} from './inputs';

const CodeInput = React.lazy(() => Promise.resolve(require('./inputs/CodeInput')));

import { Proxy } from './Proxy';
import { Separator } from './Separator';
import { AlgoSettings } from './JwtVerifier';
import { Collapse } from '../components/inputs/Collapse';
import { Location } from '../components/Location';

import * as BackOfficeServices from '../services/BackOfficeServices';

import deepSet from 'set-value';
import cloneDeep from 'lodash/cloneDeep';
import faker from 'faker';
import bcrypt from 'bcryptjs';
import { JsonObjectAsCodeInput } from './inputs/CodeInput';
import { Form } from './inputs';
import isString from 'lodash/isString';
import { LabelAndInput, NgCodeRenderer, NgForm } from './nginputs';

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

export class Oauth2ModuleConfig extends Component {
  state = {
    error: null,
  };

  static defaultConfig = {
    sessionMaxAge: 86400,
    clientId: 'client',
    clientSecret: 'secret',
    authorizeUrl: 'http://my.iam.local:8082/oauth/authorize',
    tokenUrl: 'http://my.iam.local:8082/oauth/token',
    userInfoUrl: 'http://my.iam.local:8082/userinfo',
    loginUrl: 'http://my.iam.local:8082/login',
    logoutUrl: 'http://my.iam.local:8082/logout',
    callbackUrl: 'http://privateapps.oto.tools:8080/privateapps/generic/callback',
    accessTokenField: 'access_token',
    scope: 'openid profile email name',
    refreshTokens: false,
    useJson: false,
    readProfileFromToken: false,
    jwtVerifier: {
      type: 'HSAlgoSettings',
      size: 512,
      secret: 'secret',
    },
    nameField: 'name',
    emailField: 'email',
    apiKeyMetaField: 'apkMeta',
    apiKeyTagsField: 'apkTags',
    otoroshiRightsField: 'otoroshi_rights',
    otoroshiDataField: 'app_metadata | otoroshi_data',
    extraMetadata: {},
    dataOverride: {},
    rightsOverride: {},
    adminEntityValidatorsOverride: {},
    allowedUsers: [],
    deniedUsers: [],
    mtlsConfig: {
      mtls: false,
      loose: false,
      certs: [],
    },
    sessionCookieValues: {
      httpOnly: true,
      secure: true,
    },
  };

  componentDidCatch(error) {
    const settings = this.props.value || this.props.settings;
    const path = this.props.path || '';
    console.log('Oauth2ModuleConfig did catch', error, path, settings);
    this.setState({ error });
  }

  changeTheValue = (name, value) => {
    if (this.props.onChange) {
      const clone = cloneDeep(this.props.value || this.props.settings);
      const path = name.startsWith('.') ? name.substr(1) : name;
      const newObj = deepSet(clone, path, value);
      this.props.onChange(newObj);
    } else {
      this.props.changeTheValue(name, value);
    }
  };

  fetchConfig = () => {
    window.newPrompt('URL of the OIDC config').then((url) => {
      if (url) {
        return fetch(`/bo/api/oidc/_fetchConfig`, {
          method: 'POST',
          credentials: 'include',
          headers: {
            Accept: 'application/json',
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            url,
            id: this.props.value.id,
            name: this.props.value.name,
            desc: this.props.value.desc,
            clientId: this.props.value.clientId,
            clientSecret: this.props.value.clientSecret,
          }),
        })
          .then((r) => r.json())
          .then((config) => {
            this.props.onChange(config);
          });
      }
    });
  };

  fetchKeycloakConfig = () => {
    window
      .newPrompt('Keycloak config', { value: '', textarea: true, rows: 12 })
      .then((strConfig) => {
        if (strConfig) {
          const config = JSON.parse(strConfig);
          const serverUrl = config['auth-server-url'];
          const realm = config.realm;
          const configUrl = `${serverUrl}/realms/${realm}/.well-known/openid-configuration`;
          const clientId = config.resource;
          const clientSecret = config.credentials
            ? config.credentials.secret
              ? config.credentials.secret
              : ''
            : '';
          return fetch(`/bo/api/oidc/_fetchConfig`, {
            method: 'POST',
            credentials: 'include',
            headers: {
              Accept: 'application/json',
              'Content-Type': 'application/json',
            },
            body: JSON.stringify({
              url: configUrl,
              id: this.props.value.id,
              name: this.props.value.name,
              desc: this.props.value.desc,
              clientId: clientId,
              clientSecret: clientSecret,
            }),
          })
            .then((r) => r.json())
            .then((config) => {
              this.props.onChange(config);
            });
        }
      });
  };

  render() {
    const settings = this.props.value || this.props.settings;
    settings.jwtVerifier = settings.jwtVerifier || {
      type: 'HSAlgoSettings',
      size: 512,
      secret: 'secret',
    };
    const path = this.props.path || '';
    const changeTheValue = this.changeTheValue;
    if (this.state.error) {
      return <span>{this.state.error.message ? this.state.error.message : this.state.error}</span>;
    }
    return (
      <div>
        <div className="row mb-3">
          <label
            htmlFor={`input-${this.props.label}`}
            className="col-xs-12 col-sm-2 col-form-label"
          />
          <div className="col-sm-10">
            <button type="button" className="btn btn-success me-2" onClick={this.fetchConfig}>
              Get from OIDC config
            </button>
            <button type="button" className="btn btn-success" onClick={this.fetchKeycloakConfig}>
              Get from Keycloak config
            </button>
          </div>
        </div>
        <TextInput
          label="Id"
          value={settings.id}
          disabled
          help="..."
          onChange={(v) => changeTheValue(path + '.id', v)}
        />
        <TextInput
          label="Name"
          value={settings.name}
          help="..."
          onChange={(v) => changeTheValue(path + '.name', v)}
        />
        <TextInput
          label="Description"
          value={settings.desc}
          help="..."
          onChange={(v) => changeTheValue(path + '.desc', v)}
        />
        <BooleanInput
          label="Use cookie"
          value={settings.useCookie}
          help="If your OAuth2 provider does not support query param in redirect uri, you can use cookies instead"
          onChange={(v) => changeTheValue(path + '.useCookie', v)}
        />
        <BooleanInput
          label="Client side session"
          value={settings.clientSideSessionEnabled}
          help="When using cluster mode, client side session will improve user experience with auth. modules. It allow to be logged in on a worker that has not been sync with leader yet."
          onChange={(v) => changeTheValue(path + '.clientSideSessionEnabled', v)}
        />
        <BooleanInput
          label="Use json payloads"
          value={settings.useJson}
          help="..."
          onChange={(v) => changeTheValue(path + '.useJson', v)}
        />
        <BooleanInput
          label="Enabled PKCE flow"
          value={settings.pkce ? settings.pkce.enabled : false}
          help="Prevent malicious attacker to exchange token without a code verifier"
          onChange={(v) => changeTheValue(path + '.pkce.enabled', v)}
        />
        {settings.pkce && settings.pkce.enabled && (
          <SelectInput
            label="PKCE Algorithm"
            value={settings.pkce.algorithm}
            defaultValue="S256"
            possibleValues={[
              { value: 'S256', label: 'HMAC-SHA256' },
              { value: 'plain', label: 'PLAIN (not recommended)' },
            ]}
            onChange={(v) => changeTheValue(path + '.pkce.algorithm', v)}
          />
        )}
        <BooleanInput
          label="Disable wildcard on redirect URIs"
          value={settings.noWildcardRedirectURI}
          help="As of OAuth 2.1, query parameters on redirect URIs are no longer allowed"
          onChange={(v) => changeTheValue(path + '.noWildcardRedirectURI', v)}
        />
        <BooleanInput
          label="Refresh tokens"
          value={settings.refreshTokens}
          help="Automatically refresh access token using the refresh token if available"
          onChange={(v) => changeTheValue(path + '.refreshTokens', v)}
        />
        <BooleanInput
          label="Read profile from token"
          value={settings.readProfileFromToken}
          help="..."
          onChange={(v) => changeTheValue(path + '.readProfileFromToken', v)}
        />
        <BooleanInput
          label="Super admins only"
          value={settings.superAdmins}
          help="All logged in users will have super admins rights"
          onChange={(v) => changeTheValue(path + '.superAdmins', v)}
        />
        <TextInput
          label="Client ID"
          value={settings.clientId}
          help="..."
          onChange={(v) => changeTheValue(path + '.clientId', v)}
        />
        <TextInput
          label="Client Secret"
          value={settings.clientSecret}
          help="..."
          onChange={(v) => changeTheValue(path + '.clientSecret', v)}
        />
        <Separator title="URLs" />
        <TextInput
          label="Authorize URL"
          value={settings.authorizeUrl}
          help="..."
          onChange={(v) => changeTheValue(path + '.authorizeUrl', v)}
        />
        <TextInput
          label="Token URL"
          value={settings.tokenUrl}
          help="..."
          onChange={(v) => changeTheValue(path + '.tokenUrl', v)}
        />
        <TextInput
          label="Introspection URL"
          value={settings.introspectionUrl}
          help="..."
          onChange={(v) => changeTheValue(path + '.introspectionUrl', v)}
        />
        <TextInput
          label="Userinfo URL"
          value={settings.userInfoUrl}
          help="..."
          onChange={(v) => changeTheValue(path + '.userInfoUrl', v)}
        />
        <TextInput
          label="Login URL"
          value={settings.loginUrl}
          help="..."
          onChange={(v) => changeTheValue(path + '.loginUrl', v)}
        />
        <TextInput
          label="Logout URL"
          value={settings.logoutUrl}
          help="..."
          onChange={(v) => changeTheValue(path + '.logoutUrl', v)}
        />
        <TextInput
          label="Callback URL"
          value={settings.callbackUrl}
          help="..."
          onChange={(v) => changeTheValue(path + '.callbackUrl', v)}
        />
        <Separator title="Token" />
        <TextInput
          label="Access token field name"
          value={settings.accessTokenField}
          help="..."
          onChange={(v) => changeTheValue(path + '.accessTokenField', v)}
        />
        <TextInput
          label="Scope"
          value={settings.scope}
          help="..."
          onChange={(v) => changeTheValue(path + '.scope', v)}
        />
        <TextInput
          label="Claims"
          value={settings.claims}
          help="..."
          onChange={(v) => changeTheValue(path + '.claims', v)}
        />
        <TextInput
          label="Name field name"
          value={settings.nameField}
          help="Retrieve name from token field"
          onChange={(v) => changeTheValue(path + '.nameField', v)}
        />
        <TextInput
          label="Email field name"
          value={settings.emailField}
          help="Retrieve email from token field"
          onChange={(v) => changeTheValue(path + '.emailField', v)}
        />
        <TextInput
          label="Otoroshi metadata field name"
          value={settings.otoroshiDataField}
          help="Retrieve metadata from token field"
          onChange={(v) => changeTheValue(path + '.otoroshiDataField', v)}
        />
        <TextInput
          label="Otoroshi rights field name"
          value={settings.otoroshiRightsField}
          help="Retrieve user rights from user profile"
          onChange={(v) => changeTheValue(path + '.otoroshiRightsField', v)}
        />
        <Separator title="Users" />
        <ArrayInput
          label="Allowed users"
          value={settings.allowedUsers}
          onChange={(v) => changeTheValue(path + '.allowedUsers', v)}
        />
        <ArrayInput
          label="Denied users"
          value={settings.deniedUsers}
          onChange={(v) => changeTheValue(path + '.deniedUsers', v)}
        />
        <ArrayInput
          label="User validators"
          component={UserValidator}
          value={settings.userValidators}
          onChange={(v) => changeTheValue(path + '.userValidators', v)}
          defaultValue={{
            path: '$.profile.admin',
            value: true,
          }}
        />
        <ArrayInput
          label="Remote validators"
          component={RemoteValidator}
          value={settings.remoteValidators}
          onChange={(v) => changeTheValue(path + '.remoteValidators', v)}
          defaultValue={{
            url: 'https://validator.oto.tools:3005/user_validation',
            headers: {},
            timeout: 3000,
          }}
        />
        <Suspense fallback={<div>loading ...</div>}>
          <CodeInput
            label="Extra metadata"
            mode="json"
            value={JSON.stringify(settings.extraMetadata, null, 2)}
            onChange={(e) => {
              if (e.trim() === '') {
                changeTheValue(path + '.extraMetadata', {});
              } else {
                changeTheValue(path + '.extraMetadata', JSON.parse(e));
              }
            }}
            example={{
              provider: 'Keycloak',
              foo: 'bar',
            }}
          />
        </Suspense>
        <JsonObjectAsCodeInput
          label="Data override"
          mode="json"
          value={settings.dataOverride || {}}
          onChange={(e) => changeTheValue(path + '.dataOverride', e)}
          example={{
            'no.name@oto.tools': {
              lastname: 'bar',
            },
          }}
        />
        <JsonObjectAsCodeInput
          label="Rights override"
          mode="json"
          value={settings.rightsOverride || {}}
          onChange={(e) => {
            changeTheValue(path + '.rightsOverride', e);
          }}
          example={{
            'jhon@otoroshi.tools': [
              {
                tenant: '*:r',
                teams: ['*:r'],
              },
            ],
          }}
        />
        <JsonObjectAsCodeInput
          label="Admin entity validators override"
          mode="json"
          value={settings.adminEntityValidatorsOverride || {}}
          onChange={(e) => {
            changeTheValue(path + '.adminEntityValidatorsOverride', e);
          }}
          example={{
            'john@otoroshi.tools': {
              route: [
                {
                  path: '$.*',
                  value: 'JsonContainsNot(${env.)',
                  error: 'You cannot use expression with env. variables',
                },
              ],
            },
          }}
        />
        <TextInput
          label="Api key metadata field name"
          value={settings.apiKeyMetaField}
          help="..."
          onChange={(v) => changeTheValue(path + '.apiKeyMetaField', v)}
        />
        <TextInput
          label="Api key tags field name"
          value={settings.apiKeyTagsField}
          help="..."
          onChange={(v) => changeTheValue(path + '.apiKeyTagsField', v)}
        />
        <Separator title="Proxy" />
        <Proxy value={settings.proxy} onChange={(v) => changeTheValue(path + '.proxy', v)} />
        <Separator title="OIDC Config" />
        <TextInput
          label="OIDC config url"
          value={settings.oidConfig}
          help="..."
          onChange={(v) => changeTheValue(path + '.oidConfig', v)}
        />
        <Separator title="Token validation" />
        <AlgoSettings
          algoTitle="Token verification"
          path={`jwtVerifier`}
          changeTheValue={this.changeTheValue}
          algo={settings.jwtVerifier}
        />
        <Separator title="TLS settings" />
        <BooleanInput
          label="Custom TLS Settings"
          value={settings.mtlsConfig.mtls}
          help="..."
          onChange={(v) => changeTheValue(path + '.mtlsConfig.mtls', v)}
        />
        <BooleanInput
          label="TLS loose"
          value={settings.mtlsConfig.loose}
          help="..."
          onChange={(v) => changeTheValue(path + '.mtlsConfig.loose', v)}
        />
        <BooleanInput
          label="Trust all"
          value={settings.mtlsConfig.trustAll}
          help="..."
          onChange={(v) => changeTheValue(path + '.mtlsConfig.trustAll', v)}
        />
        <ArrayInput
          label="Client certificates"
          placeholder="Choose a client certificate"
          value={settings.mtlsConfig.certs}
          valuesFrom="/bo/api/proxy/api/certificates"
          transformer={(a) => ({
            value: a.id,
            label: (
              <span>
                <span className="badge bg-success" style={{ minWidth: 63 }}>
                  {a.certType}
                </span>{' '}
                {a.name} - {a.description}
              </span>
            ),
          })}
          help="The certificates used when performing a mTLS call"
          onChange={(e) => changeTheValue(path + '.mtlsConfig.certs', e)}
        />
        <ArrayInput
          label="Trusted certificates"
          placeholder="Choose a trusted certificate"
          value={settings.mtlsConfig.trustedCerts}
          valuesFrom="/bo/api/proxy/api/certificates"
          transformer={(a) => ({
            value: a.id,
            label: (
              <span>
                <span className="badge bg-success" style={{ minWidth: 63 }}>
                  {a.certType}
                </span>{' '}
                {a.name} - {a.description}
              </span>
            ),
          })}
          help="The trusted certificates used when performing a mTLS call"
          onChange={(e) => changeTheValue(path + '.mtlsConfig.trustedCerts', e)}
        />
      </div>
    );
  }
}

export class User extends Component {
  state = {
    rawUser: JSON.stringify(this.props.user.metadata),
  };

  handleErrorWithMessage = (message) => () => {
    console.log('error', message);
    this.setState({ error: message });
  };

  registerWebAuthn = (e) => {
    if (e && e.preventDefault) {
      e.preventDefault();
    }

    const username = this.props.user.email;
    const label = this.props.user.name;

    return this.props.save().then(() => {
      return fetch(`/bo/api/proxy/api/auths/${this.props.authModuleId}/register/start`, {
        method: 'POST',
        credentials: 'include',
        headers: {
          Accept: 'application/json',
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({
          username,
          password: '',
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
          publicKeyCredentialCreationOptions.excludeCredentials =
            publicKeyCredentialCreationOptions.excludeCredentials.map((c) => {
              return { ...c, id: base64url.decode(c.id) };
            });
          return navigator.credentials
            .create(
              {
                publicKey: publicKeyCredentialCreationOptions,
              },
              this.handleErrorWithMessage('Webauthn error')
            )
            .then((credentials) => {
              const json = responseToObject(credentials);
              return fetch(`/bo/api/proxy/api/auths/${this.props.authModuleId}/register/finish`, {
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
                    password: '',
                    label,
                    handle,
                  },
                }),
              })
                .then((r) => r.json())
                .then((resp) => {
                  this.props.updateAll();
                  console.log('done');
                  this.setState({
                    error: null,
                    message: `Registration done for '${username}'`,
                  });
                });
            }, this.handleErrorWithMessage('Webauthn error'))
            .catch(this.handleError);
        });
    });
  };

  render() {
    return (
      <div className="row mt-1 mb-3">
        <div className="col-12 d-flex justify-content-end input-group-btn mb-2">
          <button
            type="button"
            className="btn btn-sm btn-success"
            title="Set password"
            onClick={(e) => {
              window
                .newPrompt('Type password', { type: 'password', title: 'Set the password' })
                .then((value1) => {
                  window
                    .newPrompt('Re-type password', {
                      type: 'password',
                      title: 'Confirm the password',
                    })
                    .then((value2) => {
                      if (value1 && value2 && value1 === value2) {
                        this.props.hashPassword(this.props.user.email, value1);
                      } else {
                        window.newAlert('Passwords does not match !', 'Error');
                      }
                    });
                });
            }}
          >
            <i className="fas fa-edit" /> Set password
          </button>
          <button
            type="button"
            className="btn btn-sm btn-success"
            title="Generate password"
            onClick={(e) => {
              const password = faker.random.alphaNumeric(16);
              this.props.hashPassword(this.props.user.email, password);
              window.newAlert(`The generated password is: ${password}`, 'Generated password');
            }}
            style={{ marginRight: 0 }}
          >
            <i className="fas fa-redo" /> Generate passsword
          </button>
          {this.props.webauthn && (
            <button
              type="button"
              className="btn btn-sm btn-primary"
              title="Update profile link"
              onClick={(e) => {
                return fetch(
                  `/bo/api/proxy/api/privateapps/sessions/${this.props.authModuleId}/${this.props.user.email}`,
                  {
                    method: 'POST',
                    credentials: 'include',
                    headers: {
                      Accept: 'application/json',
                    },
                  }
                )
                  .then((r) => r.json())
                  .then((r) => {
                    console.log(r);
                    const sessionId = r.sessionId;
                    window.newAlert(
                      <div
                        style={{
                          display: 'flex',
                          flexDirection: 'column',
                          justifyContent: 'center',
                          alignItems: 'center',
                        }}
                      >
                        <p>The link to update user profile is usable for the next 10 minutes</p>
                        <a
                          target="_blank"
                          href={`${r.host}/privateapps/profile?session=${sessionId}`}
                        >{`${r.host}/privateapps/profile?session=${sessionId}`}</a>
                      </div>,
                      'Profile updates'
                    );
                  });
              }}
            >
              <i className="fas fa-link" /> Update profile link
            </button>
          )}
          {this.props.webauthn && (
            <button
              type="button"
              className="btn btn-sm btn-primary"
              title="Send update profile link to user"
              onClick={(e) => {
                return fetch(
                  `/bo/api/proxy/api/privateapps/sessions/send/${this.props.authModuleId}/${this.props.user.email}`,
                  {
                    method: 'POST',
                    credentials: 'include',
                    headers: {
                      Accept: 'application/json',
                    },
                  }
                )
                  .then((r) => r.json())
                  .then((r) => {
                    window.newAlert('The email containing update link has been sent', 'Email sent');
                  });
              }}
              style={{ marginRight: 0 }}
            >
              <i className="fas fa-envelope" /> Send update profile link to user
            </button>
          )}
          {this.props.webauthn && (
            <button
              type="button"
              className="btn btn-sm btn-primary"
              onClick={this.registerWebAuthn}
              title="Register webauthn device"
            >
              <i className="fas fa-lock" /> Register webauthn device
            </button>
          )}
          <button
            type="button"
            className="btn btn-sm btn-danger"
            title="Remove user"
            onClick={(e) => this.props.removeUser(this.props.user.email)}
          >
            <i className="fas fa-trash" /> Remove user
          </button>
        </div>
        <div className="col-12">
          <div className="row mb-3">
            <label for="input-Name" className="col-xs-12 col-sm-2 col-form-label">
              Name
            </label>
            <div className="col-sm-10 d-flex">
              <input
                type="text"
                placeholder="User name"
                className="form-control"
                value={this.props.user.name}
                onChange={(e) => this.props.onChange(this.props.user.email, 'name', e.target.value)}
              />
            </div>
          </div>
          <div className="row mb-3">
            <label for="input-Name" className="col-xs-12 col-sm-2 col-form-label">
              Email
            </label>
            <div className="col-sm-10 d-flex">
              <input
                type="text"
                placeholder="User email"
                className="form-control"
                value={this.props.user.email}
                onChange={(e) =>
                  this.props.onChange(this.props.user.email, 'email', e.target.value)
                }
              />
            </div>
          </div>
          <div className="row mb-3">
            <label for="input-Name" className="col-xs-12 col-sm-2 col-form-label">
              Metadata
            </label>
            <div className="col-sm-10 d-flex">
              <input
                type="text"
                placeholder="User metadata"
                className="form-control"
                value={
                  this.state.rawUser !== JSON.stringify(this.props.user.metadata)
                    ? this.state.rawUser
                    : JSON.stringify(this.props.user.metadata)
                }
                onChange={(e) => {
                  try {
                    const finalValue = JSON.parse(e.target.value);
                    this.setState({ rawUser: JSON.stringify(finalValue) });
                    this.props.onChange(this.props.user.email, 'metadata', finalValue);
                  } catch (err) {
                    this.setState({ rawUser: e.target.value });
                  }
                }}
              />
            </div>
          </div>
        </div>
      </div>
    );
  }
}

export class BasicModuleConfig extends Component {
  state = {
    error: null,
    showRaw: false,
  };

  componentDidCatch(error) {
    const settings = this.props.value || this.props.settings;
    const path = this.props.path || '';
    console.log('BasicModuleConfig did catch', error, path, settings);
    this.setState({ error });
  }

  changeTheValue = (name, value) => {
    if (this.props.onChange) {
      const clone = cloneDeep(this.props.value || this.props.settings);
      const path = name.startsWith('.') ? name.substr(1) : name;
      const newObj = deepSet(clone, path, value);
      this.props.onChange(newObj);
    } else {
      this.props.changeTheValue(name, value);
    }
  };

  addUser = () => {
    const newValue = cloneDeep(this.props.value);
    const firstName = faker.name.firstName();
    const lastName = faker.name.lastName();
    newValue.users.push({
      name: firstName + ' ' + lastName,
      password: bcrypt.hashSync('password', bcrypt.genSaltSync(10)),
      email: firstName.toLowerCase() + '.' + lastName.toLowerCase() + '@oto.tools',
      metadata: {},
    });
    this.props.onChange(newValue);
  };

  removeUser = (email) => {
    const newValue = cloneDeep(this.props.value);
    newValue.users = newValue.users.filter((u) => u.email !== email);
    this.props.onChange(newValue);
  };

  hashPassword = (email, password) => {
    const newValue = cloneDeep(this.props.value);
    newValue.users.map((user) => {
      if (user.email === email) {
        user.password = bcrypt.hashSync(password, bcrypt.genSaltSync(10));
      }
    });
    this.props.onChange(newValue);
  };

  changeField = (email, name, value) => {
    const newValue = cloneDeep(this.props.value);
    newValue.users.map((user) => {
      if (user.email === email) {
        user[name] = value;
      }
    });
    this.props.onChange(newValue);
  };

  updateAll = () => {
    const settings = this.props.value || this.props.settings;
    return BackOfficeServices.findAuthConfigById(settings.id).then((auth) =>
      this.props.onChange(auth)
    );
  };

  save = () => {
    const settings = this.props.value || this.props.settings;
    return BackOfficeServices.updateAuthConfig(settings).then((auth) => this.props.onChange(auth));
  };

  render() {
    const settings = this.props.value || this.props.settings;
    const path = this.props.path || '';
    const changeTheValue = this.changeTheValue;
    if (this.state.error) {
      return <span>{this.state.error.message ? this.state.error.message : this.state.error}</span>;
    }
    return (
      <div>
        <TextInput
          label="Id"
          value={settings.id}
          disabled
          help="..."
          onChange={(v) => changeTheValue(path + '.id', v)}
        />
        <TextInput
          label="Name"
          value={settings.name}
          help="..."
          onChange={(v) => changeTheValue(path + '.name', v)}
        />
        <TextInput
          label="Description"
          value={settings.desc}
          help="..."
          onChange={(v) => changeTheValue(path + '.desc', v)}
        />
        <BooleanInput
          label="Basic auth."
          value={settings.basicAuth}
          help="..."
          onChange={(v) => changeTheValue(path + '.basicAuth', v)}
        />
        <BooleanInput
          label="Login with WebAuthn"
          value={settings.webauthn}
          help="..."
          onChange={(v) => changeTheValue(path + '.webauthn', v)}
        />
        <BooleanInput
          label="Client side session"
          value={settings.clientSideSessionEnabled}
          help="When using cluster mode, client side session will improve user experience with auth. modules. It allow to be logged in on a worker that has not been sync with leader yet."
          onChange={(v) => changeTheValue(path + '.clientSideSessionEnabled', v)}
        />
        <div className="row mb-3">
          <label htmlFor={`input-users`} className="col-sm-2 col-form-label">
            Users
          </label>
          <div className="col-sm-10">
            {this.props.value.users.map((user) => (
              <User
                user={user}
                authModuleId={settings.id}
                removeUser={this.removeUser}
                hashPassword={this.hashPassword}
                webauthn={settings.webauthn}
                onChange={this.changeField}
                updateAll={this.updateAll}
                save={this.save}
              />
            ))}
            <button type="button" className="btn btn-primary" onClick={this.addUser}>
              <i className="fas fa-plus-circle" /> Add user
            </button>
          </div>
        </div>
        {!this.state.showRaw && (
          <div className="row mb-3">
            <label className="col-sm-2 col-form-label">Users raw</label>
            <div className="col-sm-10">
              <button
                type="button"
                className="btn btn-primary"
                onClick={(e) => this.setState({ showRaw: !this.state.showRaw })}
              >
                Show raw users
              </button>
            </div>
          </div>
        )}
        {this.state.showRaw && (
          <div className="row mb-3">
            <label className="col-sm-2 col-form-label">Users raw</label>
            <div className="col-sm-10">
              <button
                type="button"
                className="btn btn-primary"
                onClick={(e) => this.setState({ showRaw: !this.state.showRaw })}
              >
                Hide raw users
              </button>
            </div>
          </div>
        )}
        {this.state.showRaw && (
          <Suspense fallback={<div>loading ...</div>}>
            <JsonObjectAsCodeInput
              label=""
              help="..."
              value={settings.users}
              onChange={(v) => changeTheValue(path + '.users', v)}
            />
          </Suspense>
        )}
        <ArrayInput
          label="Allowed users"
          value={settings.allowedUsers}
          onChange={(v) => changeTheValue(path + '.allowedUsers', v)}
        />
        <ArrayInput
          label="Denied users"
          value={settings.deniedUsers}
          onChange={(v) => changeTheValue(path + '.deniedUsers', v)}
        />
        <ArrayInput
          label="User validators"
          component={UserValidator}
          value={settings.userValidators}
          onChange={(v) => changeTheValue(path + '.userValidators', v)}
          defaultValue={{
            path: '$.profile.admin',
            value: true,
          }}
        />
        <ArrayInput
          label="Remote validators"
          component={RemoteValidator}
          value={settings.remoteValidators}
          onChange={(v) => changeTheValue(path + '.remoteValidators', v)}
          defaultValue={{
            url: 'https://validator.oto.tools:3005/user_validation',
            headers: {},
            timeout: 3000,
          }}
        />
      </div>
    );
  }
}

export class WasmAuthModuleConfig extends Component {
  state = {
    error: null,
    showRaw: false,
  };

  componentDidCatch(error) {
    const settings = this.props.value || this.props.settings;
    const path = this.props.path || '';
    console.log('WasmAuthModuleConfig did catch', error, path, settings);
    this.setState({ error });
  }

  changeTheValue = (name, value) => {
    console.log('changing', name, value);
    if (this.props.onChange) {
      const clone = cloneDeep(this.props.value || this.props.settings);
      const path = name.startsWith('.') ? name.substr(1) : name;
      const newObj = deepSet(clone, path, value);
      this.props.onChange(newObj);
    } else {
      this.props.changeTheValue(name, value);
    }
  };

  render() {
    const settings = this.props.value || this.props.settings;
    const path = this.props.path || '';
    const changeTheValue = this.changeTheValue;
    if (this.state.error) {
      return <span>{this.state.error.message ? this.state.error.message : this.state.error}</span>;
    }
    return (
      <div>
        <TextInput
          label="Id"
          value={settings.id}
          disabled
          help="..."
          onChange={(v) => changeTheValue(path + '.id', v)}
        />
        <TextInput
          label="Name"
          value={settings.name}
          help="..."
          onChange={(v) => changeTheValue(path + '.name', v)}
        />
        <TextInput
          label="Description"
          value={settings.description}
          help="..."
          onChange={(v) => changeTheValue(path + '.description', v)}
        />
        <SelectInput
          label="Wasm plugin"
          valuesFrom="/bo/api/proxy/apis/plugins.otoroshi.io/v1/wasm-plugins"
          transformer={(i) => ({ label: i.name, value: i.id })}
          value={settings.wasmRef}
          onChange={(v) => changeTheValue(path + '.wasmRef', v)}
        />
        <BooleanInput
          label="Client side session"
          value={settings.clientSideSessionEnabled}
          help="When using cluster mode, client side session will improve user experience with auth. modules. It allow to be logged in on a worker that has not been sync with leader yet."
          onChange={(v) => changeTheValue(path + '.clientSideSessionEnabled', v)}
        />
        <ArrayInput
          label="Allowed users"
          value={settings.allowedUsers}
          onChange={(v) => changeTheValue(path + '.allowedUsers', v)}
        />
        <ArrayInput
          label="Denied users"
          value={settings.deniedUsers}
          onChange={(v) => changeTheValue(path + '.deniedUsers', v)}
        />
        <ArrayInput
          label="User validators"
          component={UserValidator}
          value={settings.userValidators}
          onChange={(v) => changeTheValue(path + '.userValidators', v)}
          defaultValue={{
            path: '$.profile.admin',
            value: true,
          }}
        />
        <ArrayInput
          label="Remote validators"
          component={RemoteValidator}
          value={settings.remoteValidators}
          onChange={(v) => changeTheValue(path + '.remoteValidators', v)}
          defaultValue={{
            url: 'https://validator.oto.tools:3005/user_validation',
            headers: {},
            timeout: 3000,
          }}
        />
      </div>
    );
  }
}

export class LdapModuleConfig extends Component {
  state = {
    error: null,
  };

  componentDidCatch(error) {
    const settings = this.props.value || this.props.settings;
    const path = this.props.path || '';
    console.log('LdapModuleConfig did catch', error, path, settings);
    this.setState({ error });
  }

  changeTheValue = (name, value) => {
    if (this.props.onChange) {
      const clone = cloneDeep(this.props.value || this.props.settings);
      const path = name.startsWith('.') ? name.substr(1) : name;
      const newObj = deepSet(clone, path, value);
      this.props.onChange(newObj);
    } else {
      this.props.changeTheValue(name, value);
    }
  };

  check = () => {
    const settings = this.props.value || this.props.settings;
    fetch(`/bo/api/auth/_check`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify(settings),
    })
      .then((r) => r.json())
      .then((r) => {
        if (r.works) {
          window.newAlert('It Works !');
        } else {
          window.newAlert(`Error while checking connection: ${r.error}`);
        }
      });
  };

  checkUser = () => {
    const settings = this.props.value || this.props.settings;
    window.newAlert(<LdapUserLoginTest config={settings} />, `Testing user login`);
  };

  setGroupFiltersValues = (groupFilters, i, group, tenant, team) => {
    groupFilters[i] = { group, tenant, team };
    this.changeTheValue(this.props.path + '.groupFilters', groupFilters);
  };

  render() {
    const settings = this.props.value || this.props.settings;
    const path = this.props.path || '';
    const changeTheValue = this.changeTheValue;
    if (this.state.error) {
      return <span>{this.state.error.message ? this.state.error.message : this.state.error}</span>;
    }
    return (
      <div>
        <TextInput
          label="Id"
          value={settings.id}
          disabled
          help="..."
          onChange={(v) => changeTheValue(path + '.id', v)}
        />
        <TextInput
          label="Name"
          value={settings.name}
          help="..."
          onChange={(v) => changeTheValue(path + '.name', v)}
        />
        <TextInput
          label="Description"
          value={settings.desc}
          help="..."
          onChange={(v) => changeTheValue(path + '.desc', v)}
        />
        <BooleanInput
          label="Basic auth."
          value={settings.basicAuth}
          help="..."
          onChange={(v) => changeTheValue(path + '.basicAuth', v)}
        />
        <BooleanInput
          label="Client side session"
          value={settings.clientSideSessionEnabled}
          help="When using cluster mode, client side session will improve user experience with auth. modules. It allow to be logged in on a worker that has not been sync with leader yet."
          onChange={(v) => changeTheValue(path + '.clientSideSessionEnabled', v)}
        />
        <BooleanInput
          label="Allow empty password"
          value={settings.allowEmptyPassword}
          help="..."
          onChange={(v) => changeTheValue(path + '.allowEmptyPassword', v)}
        />
        <BooleanInput
          label="Super admins only"
          value={settings.superAdmins}
          help="All logged in users will have super admins rights"
          onChange={(v) => changeTheValue(path + '.superAdmins', v)}
        />
        <BooleanInput
          label="Extract profile"
          value={settings.extractProfile}
          help="..."
          onChange={(v) => changeTheValue(path + '.extractProfile', v)}
        />
        <ArrayInput
          label="LDAP Server URL"
          placeholder="Set your LDAP server"
          value={settings.serverUrls}
          help="List of your LDAP servers"
          onChange={(v) => changeTheValue(path + '.serverUrls', v)}
        />
        <TextInput
          label="Search Base"
          value={settings.searchBase}
          help="..."
          onChange={(v) => changeTheValue(path + '.searchBase', v)}
        />
        <TextInput
          label="Users search base"
          value={settings.userBase}
          help="..."
          onChange={(v) => changeTheValue(path + '.userBase', v)}
        />
        <Separator title="Match LDAP group to Otoroshi rights" />
        <div className="row mb-3">
          <label className="col-xs-12 col-sm-2 col-form-label">
            Mapping group filter <Help text="Map LDAP groups with Otoroshi rights" />
          </label>
          <div className="col-sm-10" style={{ display: 'flex' }}>
            {settings.groupFilters && settings.groupFilters.length > 0 ? (
              <table style={{ width: '100%' }}>
                <thead style={{ backgroundColor: 'transparent' }}>
                  <tr>
                    <th scope="col" className="text-center">
                      Group filter
                    </th>
                    <th scope="col" className="text-center">
                      Tenant
                    </th>
                    <th scope="col" className="text-center">
                      Team
                    </th>
                    <th scope="col" className="text-center">
                      Read
                    </th>
                    <th scope="col" className="text-center">
                      Write
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {(settings.groupFilters || []).map(({ group, tenant, team }, i) => (
                    <tr key={`group-filter-${i}`} style={{ marginTop: '5px' }}>
                      <th>
                        <input
                          type="text"
                          className="form-control"
                          placeholder="Group filter"
                          value={group}
                          onChange={(e) =>
                            this.setGroupFiltersValues(
                              settings.groupFilters,
                              i,
                              e.target.value,
                              tenant,
                              team
                            )
                          }
                          onDragOver={(e) => e.preventDefault()}
                        />
                      </th>
                      <td style={{ width: '25%', padding: '5px' }}>
                        <SelectInput
                          value={tenant.split(':')[0]}
                          onChange={(e) =>
                            this.setGroupFiltersValues(
                              settings.groupFilters,
                              i,
                              group,
                              `${e}:${tenant.split(':')[1]}`,
                              team
                            )
                          }
                          valuesFrom="/bo/api/proxy/api/tenants"
                          staticValues={[{ value: '*', label: 'All' }]}
                          transformer={(a) => ({ value: a.id, label: a.name })}
                        />
                      </td>
                      <td style={{ width: '25%', padding: '5px' }}>
                        <SelectInput
                          value={team}
                          onChange={(e) =>
                            this.setGroupFiltersValues(settings.groupFilters, i, group, tenant, e)
                          }
                          valuesFrom="/bo/api/proxy/api/teams"
                          staticValues={[{ value: '*', label: 'All' }]}
                          transformer={(a) => ({ value: a.id, label: a.name })}
                        />
                      </td>
                      <td style={{ width: '15%', padding: '5px' }}>
                        <SelectInput
                          value={tenant.split(':')[1]}
                          onChange={(e) =>
                            this.setGroupFiltersValues(
                              settings.groupFilters,
                              i,
                              group,
                              `${tenant.split(':')[0]}:${e}`,
                              team
                            )
                          }
                          staticValues={[
                            { value: 'rw', label: 'Read/Write' },
                            { value: 'r', label: 'Read' },
                          ]}
                          transformer={(a) => ({ value: a.id, label: a.name })}
                        />
                      </td>
                      <td style={{ width: '1%' }}>
                        <span className="input-group-btn">
                          <button
                            type="button"
                            className="btn btn-danger"
                            onClick={() =>
                              this.changeTheValue(
                                path + '.groupFilters',
                                settings.groupFilters.filter((_, j) => i !== j)
                              )
                            }
                          >
                            <i className="fas fa-trash" />
                          </button>
                          <button
                            type="button"
                            className="btn btn-primary"
                            onClick={() =>
                              changeTheValue(path + '.groupFilters', [
                                ...settings.groupFilters,
                                { group: '', tenant: '*rw', team: '*' },
                              ])
                            }
                          >
                            <i className="fas fa-plus-circle" />{' '}
                          </button>
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            ) : (
              <button
                type="button"
                className="btn btn-primary"
                onClick={() =>
                  changeTheValue(path + '.groupFilters', [{ group: '', tenant: '*rw', team: '*' }])
                }
              >
                <i className="fas fa-plus-circle" />{' '}
              </button>
            )}
          </div>
        </div>
        <TextInput
          label="Search Filter"
          value={settings.searchFilter}
          help="use ${username} as placeholder for searched username"
          onChange={(v) => changeTheValue(path + '.searchFilter', v)}
        />
        <TextInput
          label="Admin username (bind DN)"
          value={settings.adminUsername}
          help="if one"
          onChange={(v) => changeTheValue(path + '.adminUsername', v)}
        />
        <PasswordInput
          label="Admin password"
          value={settings.adminPassword}
          help="if one"
          onChange={(v) => changeTheValue(path + '.adminPassword', v)}
        />
        <ArrayInput
          label="Extract profile filters attributes in"
          value={settings.extractProfileFilter}
          help="..."
          onChange={(v) => changeTheValue(path + '.extractProfileFilter', v)}
        />
        <ArrayInput
          label="Extract profile filters attributes not in"
          value={settings.extractProfileFilterNot}
          help="..."
          onChange={(v) => changeTheValue(path + '.extractProfileFilterNot', v)}
        />
        <div className="row mb-3">
          <label className="col-xs-12 col-sm-2 col-form-label"></label>
          <div className="col-sm-10" style={{ display: 'flex' }}>
            <button type="button" className="btn btn-success" onClick={this.check}>
              Test admin. connection
            </button>
            <button type="button" className="btn btn-success" onClick={this.checkUser}>
              Test user connection
            </button>
          </div>
        </div>
        <TextInput
          label="Name field name"
          value={settings.nameField}
          help="Retrieve name from LDAP field"
          onChange={(v) => changeTheValue(path + '.nameField', v)}
        />
        <TextInput
          label="Email field name"
          value={settings.emailField}
          help="Retrieve email from LDAP field"
          onChange={(v) => changeTheValue(path + '.emailField', v)}
        />
        <TextInput
          label="Otoroshi metadata field name"
          value={settings.metadataField}
          help="Retrieve metadata from LDAP field"
          onChange={(v) => changeTheValue(path + '.metadataField', v)}
        />
        <ArrayInput
          label="Allowed users"
          value={settings.allowedUsers}
          onChange={(v) => changeTheValue(path + '.allowedUsers', v)}
        />
        <ArrayInput
          label="Denied users"
          value={settings.deniedUsers}
          onChange={(v) => changeTheValue(path + '.deniedUsers', v)}
        />
        <ArrayInput
          label="User validators"
          component={UserValidator}
          value={settings.userValidators}
          onChange={(v) => changeTheValue(path + '.userValidators', v)}
          defaultValue={{
            path: '$.profile.admin',
            value: true,
          }}
        />
        <ArrayInput
          label="Remote validators"
          component={RemoteValidator}
          value={settings.remoteValidators}
          onChange={(v) => changeTheValue(path + '.remoteValidators', v)}
          defaultValue={{
            url: 'https://validator.oto.tools:3005/user_validation',
            headers: {},
            timeout: 3000,
          }}
        />
        <Suspense fallback={<div>loading ...</div>}>
          <CodeInput
            label="Extra metadata"
            mode="json"
            value={JSON.stringify(settings.extraMetadata, null, 2)}
            onChange={(e) => {
              console.log('changes "', e, '"');
              if (e.trim() === '') {
                this.changeTheValue(path + '.extraMetadata', {});
              } else {
                this.changeTheValue(path + '.extraMetadata', JSON.parse(e));
              }
            }}
            example={{
              provider: 'Open LDAP',
              foo: 'bar',
            }}
          />
        </Suspense>
        <JsonObjectAsCodeInput
          label="Data override"
          mode="json"
          value={settings.dataOverride || {}}
          onChange={(e) => this.changeTheValue(path + '.dataOverride', e)}
          example={{
            'my_user@oto.tools': {
              lastname: 'bar',
            },
          }}
        />
        <JsonObjectAsCodeInput
          label="Additional rights group"
          mode="json"
          value={settings.groupRights || {}}
          onChange={(e) => this.changeTheValue(path + '.groupRights', e)}
          example={{
            virtual_group: {
              rights: [
                {
                  tenant: '*:rw',
                  teams: ['*:rw'],
                },
              ],
              users: ['my_user@oto.tools'],
            },
          }}
        />
        <JsonObjectAsCodeInput
          label="Rights override"
          mode="json"
          value={settings.rightsOverride || {}}
          onChange={(e) => this.changeTheValue(path + '.rightsOverride', e)}
          example={{
            'my_user@oto.tools': [
              {
                tenant: '*:r',
                teams: ['*:r'],
              },
            ],
          }}
        />
        <JsonObjectAsCodeInput
          label="Admin entity validators override"
          mode="json"
          value={settings.adminEntityValidatorsOverride || {}}
          onChange={(e) => {
            this.changeTheValue(path + '.adminEntityValidatorsOverride', e);
          }}
          example={{
            'john@otoroshi.tools': {
              route: [
                {
                  path: '$.*',
                  value: 'JsonContainsNot(${env.)',
                  error: 'You cannot use expression with env. variables',
                },
              ],
            },
          }}
        />
      </div>
    );
  }
}

class LdapUserLoginTest extends Component {
  state = { message: null, error: null, username: '', password: '', body: {} };

  check = () => {
    this.setState({ message: null, error: null });
    const config = this.props.config;
    fetch(`/bo/api/auth/_check`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      body: JSON.stringify({
        config,
        user: { username: this.state.username, password: this.state.password },
      }),
    })
      .then((r) => r.json())
      .then((r) => {
        if (r.works) {
          this.setState({ message: 'It Works !', body: r.user });
        } else {
          this.setState({ error: `Error while checking connection: ${r.error}` });
        }
      });
  };

  render() {
    return (
      <form className="form-horizontal">
        <div className="row mb-3">
          <label className="col-sm-2">Username</label>
          <div className="col-sm-10">
            <input
              type="email"
              value={this.state.username}
              onChange={(e) => this.setState({ username: e.target.value })}
              className="form-control"
              placeholder="Username"
            />
          </div>
        </div>
        <div className="row mb-3">
          <label className="col-sm-2">Password</label>
          <div className="col-sm-10">
            <input
              type="password"
              value={this.state.password}
              onChange={(e) => this.setState({ password: e.target.value })}
              className="form-control"
              placeholder="Password"
            />
          </div>
        </div>
        <div className="row mb-3">
          <label className="col-sm-2"></label>
          <div className="col-sm-10">
            <button type="button" className="btn btn-success" onClick={this.check}>
              Test login
            </button>
            <span className="badge bg-success">{this.state.message}</span>
            <span className="badge bg-danger">{this.state.error}</span>
            {Object.keys(this.state.body).length > 0 && (
              <pre style={{ maxHeight: '250px', marginTop: '12px' }}>
                {JSON.stringify(this.state.body, null, 2)}
              </pre>
            )}
          </div>
        </div>
      </form>
    );
  }
}

export class AuthModuleConfig extends Component {
  state = {
    authModuleTypes: [],
  };

  componentDidMount() {
    BackOfficeServices.getAuthModuleTypes().then((res) => {
      this.setState({
        authModuleTypes: res.templates.map((item) => ({ value: item.type, label: item.label })),
      });
    });
  }

  changeTheValue = (name, value) => {
    if (this.props.onChange) {
      const clone = cloneDeep(this.props.value || this.props.settings);
      const path = name.startsWith('.') ? name.substr(1) : name;
      const newObj = deepSet(clone, path, value);
      this.props.onChange(newObj);
    } else {
      this.props.changeTheValue(name, value);
    }
  };
  render() {
    const settings = this.props.value || this.props.settings;
    const path = this.props.path || '';
    const selector = (
      <SelectInput
        label="Type"
        value={settings.type}
        onChange={(e) => {
          BackOfficeServices.createNewAuthConfig(e).then((templ) => {
            this.props.onChange(templ);
          });
          /*switch (e) {
            case 'basic':
              this.props.onChange({
                id: faker.random.alphaNumeric(64),
                type: 'basic',
                name: 'Basic config.',
                sessionMaxAge: 86400,
                users: [
                  {
                    name: 'John Doe',
                    email: 'john.doe@oto.tools',
                    password: bcrypt.hashSync('password', bcrypt.genSaltSync(10)),
                    metadata: {}
                  },
                ],
                sessionCookieValues: {
                  httpOnly: true,
                  secure: true
                }
              });
              break;
            case 'ldap':
              this.props.onChange({
                id: faker.random.alphaNumeric(64),
                name: 'Ldap config.',
                type: 'ldap',
                serverUrl: 'ldap://ldap.forumsys.com:389',
                searchBase: 'dc=example,dc=com',
                searchFilter: '(uid=${username})',
                adminUsername: 'cn=read-only-admin,dc=example,dc=com',
                adminPassword: 'password',
                nameField: 'cn',
                emailField: 'mail',
                metadataField: null,
                sessionMaxAge: 86400,
                allowEmptyPassword: false,
                extraMetadata: {},
                sessionCookieValues: {
                  httpOnly: true,
                  secure: true
                }
              });
              break;
            case 'oauth2':
              this.props.onChange({
                id: faker.random.alphaNumeric(64),
                name: 'OAuth2 config.',
                type: 'oauth2',
                clientId: 'client',
                clientSecret: 'secret',
                authorizeUrl: 'http://my.iam.local:8082/oauth/authorize',
                tokenUrl: 'http://my.iam.local:8082/oauth/token',
                userInfoUrl: 'http://my.iam.local:8082/userinfo',
                loginUrl: 'http://my.iam.local:8082/login',
                logoutUrl: 'http://my.iam.local:8082/logout',
                callbackUrl: 'http://privateapps.oto.tools:8080/privateapps/generic/callback',
                accessTokenField: 'access_token',
                scope: 'openid profile email name',
                sessionMaxAge: 86400,
                refreshTokens: false,
                useJson: false,
                readProfileFromToken: false,
                jwtVerifier: {
                  type: 'HSAlgoSettings',
                  size: 512,
                  secret: 'secret',
                },
                nameField: 'name',
                emailField: 'email',
                otoroshiDataField: 'app_metadata | otoroshi_data',
                extraMetadata: {},
                mtlsConfig: {
                  mtls: false,
                  loose: false,
                  certs: [],
                },
                sessionCookieValues: {
                  httpOnly: true,
                  secure: true
                }
              });
              break;
          }*/
        }}
        possibleValues={this.state.authModuleTypes}
        //   [
        //   { label: 'OAuth2 / OIDC provider', value: 'oauth2' },
        //   { label: 'In memory auth. provider', value: 'basic' },
        //   { label: 'Ldap auth. provider', value: 'ldap' },
        //   { label: 'SAML v2 provider', value: 'saml' },
        //   { label: 'OAuth1 provider', value: 'oauth1' },
        // ]

        help="The type of settings to log into your app."
      />
    );

    // if (!['oauth2', 'basic', 'ldap', 'saml', 'oauth1'].includes(settings.type)) {
    //   return <h3>Unknown config type ...</h3>;
    // }

    console.log(this.props.value)

    return (
      <div>
        <Collapse initCollapsed={false} label="Location" lineEnd={true}>
          <Location
            tenant={settings._loc.tenant || 'default'}
            onChangeTenant={(v) => this.changeTheValue('_loc.tenant', v)}
            teams={settings._loc.teams || ['default']}
            onChangeTeams={(v) => this.changeTheValue('_loc.teams', (v || []).map(entry => {
              if (typeof entry === "string") {
                return entry
              } else {
                return entry?.id
              }
            })
              .filter(f => f)
            )}
          />
        </Collapse>
        {selector}
        {settings.type === 'oauth2' && <Oauth2ModuleConfig {...this.props} />}
        {settings.type === 'basic' && <BasicModuleConfig {...this.props} />}
        {settings.type === 'ldap' && <LdapModuleConfig {...this.props} />}
        {settings.type === 'saml' && <SamlModuleConfig {...this.props} />}
        {settings.type === 'oauth1' && <OAuth1ModuleConfig {...this.props} />}
        {settings.type === 'wasm' && <WasmAuthModuleConfig {...this.props} />}
        {!['oauth2', 'basic', 'ldap', 'saml', 'oauth1', 'wasm'].includes(settings.type) && (
          <CustomModuleConfig {...this.props} />
        )}
        <Separator title="Module metadata" />
        <ArrayInput
          label="Tags"
          value={settings.tags}
          onChange={(v) => this.changeTheValue(path + '.tags', v)}
        />
        <ObjectInput
          label="Metadata"
          value={settings.metadata}
          onChange={(v) => this.changeTheValue(path + '.metadata', v)}
        />
        <SessionCookieConfig {...this.props} />
      </div>
    );
  }
}

export class SamlModuleConfig extends Component {
  flow = [
    'warning',
    'id',
    'name',
    'desc',
    'clientSideSessionEnabled',
    'singleSignOnUrl',
    'ssoProtocolBinding',
    'singleLogoutUrl',
    'singleLogoutProtocolBinding',
    'credentials.signedDocuments',
    'credentials.encryptedAssertions',
    'credentials',
    'nameIDFormat',
    'usedNameIDAsEmail',
    'issuer',
    'validateSignature',
    'validateAssertions',
    'validatingCertificates',
    'allowedUsers',
    'deniedUsers',
    'userValidators',
    'remoteValidators',
    'adminEntityValidatorsOverride',
    'extraMetadata',
  ];

  changeTheValue = (name, value) => {
    if (this.props.onChange)
      this.props.onChange(
        deepSet(
          cloneDeep(this.props.value || this.props.settings),
          name.startsWith('.') ? name.substr(1) : name,
          value
        )
      );
    else this.props.changeTheValue(name, value);
  };

  schema = {
    userValidators: {
      type: 'array',
      props: {
        label: 'User validators',
        component: UserValidator,
        defaultValue: {
          path: '$.profile.admin',
          value: true,
        },
      },
    },
    remoteValidators: {
      type: 'array',
      props: {
        label: 'Remote validators',
        component: RemoteValidator,
        defaultValue: {
          url: 'https://validator.oto.tools:3005/user_validation',
          headers: {},
          timeout: 3000,
        },
      },
    },
    warning: {
      type: ({ }) => {
        if (this.props.value.warning) {
          const { warning } = this.props.value;
          return (
            <div className="row mb-3">
              <label className="col-xs-12 col-sm-2 col-form-label"></label>
              <div className="col-sm-10">{warning.error ? warning.error : warning.success}</div>
            </div>
          );
        }
        return null;
      },
    },
    clientSideSessionEnabled: {
      type: 'bool',
      props: {
        label: 'Client side session',
        help: 'When using cluster mode, client side session will improve user experience with auth. modules. It allow to be logged in on a worker that has not been sync with leader yet.',
      },
    },
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'New SAML config' },
    },
    desc: {
      type: 'string',
      props: { label: 'Description', placeholder: 'New SAML Description' },
    },
    singleSignOnUrl: {
      type: 'string',
      props: {
        label: 'Single sign on URL',
      },
    },
    ssoProtocolBinding: {
      type: 'select',
      props: {
        label: 'The protocol binding for the login request',
        defaultValue: 'post',
        possibleValues: [
          { value: 'post', label: 'Post' },
          { value: 'redirect', label: 'Redirect' },
        ],
      },
    },
    singleLogoutUrl: {
      type: 'string',
      props: {
        label: 'Single Logout URL',
      },
    },
    singleLogoutProtocolBinding: {
      type: 'select',
      props: {
        label: 'The protocol binding for the logout request',
        defaultValue: 'post',
        possibleValues: [
          { value: 'post', label: 'Post' },
          { value: 'redirect', label: 'Redirect' },
        ],
      },
    },
    'credentials.signedDocuments': {
      type: 'bool',
      props: {
        label: 'Sign documents',
        help: 'Should SAML Request be signed by Otoroshi ?',
      },
    },
    'credentials.encryptedAssertions': {
      type: 'bool',
      props: {
        label: 'Validate Assertions Signature',
        help: 'Should SAML Assertions to be decrypted ?',
      },
    },
    allowedUsers: {
      type: 'array',
      props: {
        label: 'Allowed users',
      },
    },
    deniedUsers: {
      type: 'array',
      props: {
        label: 'Denied users',
      },
    },
    credentials: {
      type: ({ }) => {
        const { signingKey, encryptionKey, signedDocuments, encryptedAssertions } =
          this.props.value.credentials;

        const configs = [
          {
            element: 'documents',
            switch: {
              value: signingKey.useOtoroshiCertificate,
              setValue: (value) => {
                this.changeTheValue('credentials.signingKey.useOtoroshiCertificate', value);
              },
            },
            key: 'Signing',
            path: 'credentials.signingKey',
            value: {
              certificate: signingKey.certificate,
              privateKey: signingKey.privateKey,
              certId: signingKey.certId,
            },
            show: signedDocuments,
          },
          {
            element: 'assertions',
            switch: {
              value: encryptionKey.useOtoroshiCertificate,
              setValue: (value) => {
                this.changeTheValue('credentials.encryptionKey.useOtoroshiCertificate', value);
              },
            },
            key: 'Encryption',
            path: 'credentials.encryptionKey',
            value: {
              certificate: encryptionKey.certificate,
              privateKey: encryptionKey.privateKey,
              certId: encryptionKey.certId,
            },
            show: encryptedAssertions,
          },
        ];

        return configs.map(
          (config, i) =>
            config.show && (
              <div key={`config${i}`}>
                <BooleanInput
                  label={`${i === 0 ? 'Sign' : 'Validate'} ${config.element
                    } with Otoroshi certificate`}
                  value={config.switch.value}
                  onChange={() => config.switch.setValue(!config.switch.value)}
                />
                {!config.switch.value ? (
                  <div>
                    <TextareaInput
                      label={`${config.key} Certificate`}
                      value={config.value.certificate}
                      help="..."
                      onChange={(v) => this.changeTheValue(`${config.path}.certificate`, v)}
                    />
                    <TextareaInput
                      label={`${config.key} Private Key`}
                      value={config.value.privateKey}
                      help="..."
                      onChange={(v) => this.changeTheValue(`${config.path}.privateKey`, v)}
                    />
                    <SelectInput
                      label="Signature al"
                      help="The signature algorithm to use to sign documents"
                      value={this.props.value.signature.algorithm}
                      onChange={(v) => this.changeTheValue('signature.algorithm', v)}
                      possibleValues={[
                        { label: 'RSA_SHA1', value: 'rsa_sha1' },
                        { label: 'RSA_SHA512', value: 'rsa_sha512' },
                        { label: 'RSA_SHA256', value: 'rsa_sha256' },
                        { label: 'DSA_SHA1', value: 'dsa_sha1' },
                      ]}
                    />
                    <SelectInput
                      label="Canonicalization Method"
                      help="Canonicalization Method for XML Signatures"
                      value={this.props.value.signature.canocalizationMethod}
                      onChange={(v) => this.changeTheValue('signature.canocalizationMethod', v)}
                      possibleValues={[
                        { label: 'EXCLUSIVE', value: 'exclusive' },
                        { label: 'EXCLUSIVE_WITH_COMMENTS', value: 'with_comments' },
                      ]}
                    />
                  </div>
                ) : (
                  <div>
                    <SelectInput
                      label={`${config.key} KeyPair`}
                      help={`The keypair used to sign/verify ${config.element}`}
                      value={config.value.certId}
                      onChange={(v) => this.changeTheValue(`${config.path}.certId`, v)}
                      valuesFrom="/bo/api/proxy/api/certificates?keypair=true"
                      transformer={(a) => ({ value: a.id, label: a.name + ' - ' + a.description })}
                    />
                  </div>
                )}
              </div>
            )
        );
      },
    },
    nameIDFormat: {
      type: 'select',
      props: {
        label: 'Name ID Format',
        defaultValue: 'The name ID Format to use for the subject',
        possibleValues: [
          { value: 'unspecified', label: 'Unspecified' },
          { value: 'emailAddress', label: 'Email address' },
          { value: 'persistent', label: 'Persistent' },
          { value: 'transient', label: 'Transient' },
          { value: 'kerberos', label: 'Kerberos' },
          { value: 'entity', label: 'Entity' },
        ],
      },
    },
    usedNameIDAsEmail: {
      type: ({ }) => {
        const { emailAttributeName, usedNameIDAsEmail } = this.props.value;
        return (
          <div>
            <BooleanInput
              label={`Use NameID format as email`}
              value={usedNameIDAsEmail}
              onChange={(v) => this.changeTheValue('.usedNameIDAsEmail', v)}
            />
            {!this.props.value.usedNameIDAsEmail && (
              <TextInput
                label="Name of email attribute"
                value={emailAttributeName}
                help="..."
                onChange={(v) => this.changeTheValue('emailAttributeName', v)}
              />
            )}
          </div>
        );
      },
    },
    issuer: {
      type: 'string',
      props: {
        label: 'URL issuer',
      },
    },
    validateSignature: {
      type: 'bool',
      props: {
        label: 'Validate Signature',
        help: 'Enable/disable signature validation of SAML responses',
      },
    },
    validateAssertions: {
      type: 'bool',
      props: {
        label: 'Validate Assertions Signature',
        help: 'Enable/disable signature validation of SAML assertions',
      },
    },
    validatingCertificates: {
      type: 'array',
      props: {
        label: 'Validating Certificates',
        help: 'The certificate in PEM format that must be used to check for signatures.',
      },
    },
    adminEntityValidatorsOverride: {
      type: ({ }) => (
        <JsonObjectAsCodeInput
          label="Admin entity validators override"
          mode="json"
          value={this.props.value.adminEntityValidatorsOverride || {}}
          onChange={(e) => {
            this.changeTheValue('adminEntityValidatorsOverride', e);
          }}
          example={{
            'john@otoroshi.tools': {
              route: [
                {
                  path: '$.*',
                  value: 'JsonContainsNot(${env.)',
                  error: 'You cannot use expression with env. variables',
                },
              ],
            },
          }}
        />
      ),
      props: {
        label: 'Admin entity validators override',
      },
    },
    extraMetadata: {
      type: ({ }) => (
        <Suspense fallback={<div>loading ...</div>}>
          <CodeInput
            label="Extra metadata"
            mode="json"
            value={JSON.stringify(this.props.value.extraMetadata, null, 2)}
            onChange={(e) => {
              if (e.trim() === '') {
                this.changeTheValue('.extraMetadata', {});
              } else {
                this.changeTheValue('.extraMetadata', JSON.parse(e));
              }
            }}
            example={{
              provider: 'Keycloak',
              foo: 'bar',
            }}
          />
        </Suspense>
      ),
      props: {
        label: 'Extra metadata',
      },
    },
  };

  fetchFromURL = () => {
    window.newPrompt('URL of the entity descriptor config').then((url) => {
      if (url) this._fetchConfig({ url });
    });
  };

  _fetchConfig = (body) => {
    fetch('/bo/api/saml/_fetchConfig', {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
    }).then((res) => {
      const onError = res.status >= 400;
      res.json().then((r) => {
        if (onError)
          this.props.onChange({
            ...this.props.value,
            warning: {
              error: r.error,
            },
          });
        else
          this.props.onChange({
            ...r,
            warning: {
              success: 'Config loaded',
            },
          });
      });
    });
  };

  fetchConfig = () => {
    window
      .newPrompt('Entities descriptor config', { value: '', textarea: true, rows: 12 })
      .then((xml) => {
        if (xml) this._fetchConfig({ xml });
      });
  };

  render() {
    return (
      <div>
        <div className="row mb-3">
          <label
            htmlFor={`input-${this.props.label}`}
            className="col-xs-12 col-sm-2 col-form-label"
          ></label>
          <div className="col-sm-10">
            <button type="button" className="btn btn-success" onClick={this.fetchFromURL}>
              Get entity descriptor from URL
            </button>
            <button type="button" className="btn btn-success" onClick={this.fetchConfig}>
              Paste config
            </button>
          </div>
        </div>
        <Form
          value={this.props.value}
          onChange={this.props.onChange}
          flow={this.flow}
          schema={this.schema}
        />
      </div>
    );
  }
}

export function CustomModuleConfig({ value, onChange }) {
  if (value.form && Object.keys(value.form.schema).length > 0) {
    const { form } = value;
    return <NgForm value={value} onChange={onChange} flow={form.flow} schema={form.schema} />;
  }

  return (
    <LabelAndInput label="Configuration">
      <span className="d-flex align-items-center" style={{ height: '100%' }}>
        <NgCodeRenderer
          ngOptions={{
            spread: true,
          }}
          rawSchema={{
            props: {
              ace_config: {
                maxLines: Infinity,
                fontSize: 14,
              },
              editorOnly: true,
              height: '100%',
              mode: 'json',
            },
          }}
          onChange={(e) => {
            onChange(JSON.parse(e));
          }}
          value={JSON.stringify(value, null, 4)}
        />
      </span>
    </LabelAndInput>
  );
}

export class OAuth1ModuleConfig extends Component {
  flow = [
    'id',
    'name',
    'desc',
    'clientSideSessionEnabled',
    'httpMethod',
    'consumerKey',
    'consumerSecret',
    // "signatureMethod",
    'requestTokenURL',
    'authorizeURL',
    'accessTokenURL',
    'profileURL',
    'callbackURL',
    'allowedUsers',
    'deniedUsers',
    'userValidators',
    'remoteValidators',
    'rightsOverride',
    'adminEntityValidatorsOverride',
  ];

  changeTheValue = (name, value) => {
    if (this.props.onChange)
      this.props.onChange(
        deepSet(
          cloneDeep(this.props.value || this.props.settings),
          name.startsWith('.') ? name.substr(1) : name,
          value
        )
      );
    else this.props.changeTheValue(name, value);
  };

  schema = {
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    clientSideSessionEnabled: {
      type: 'bool',
      props: {
        label: 'Client side session',
        help: 'When using cluster mode, client side session will improve user experience with auth. modules. It allow to be logged in on a worker that has not been sync with leader yet.',
      },
    },
    httpMethod: {
      type: 'select',
      props: {
        label: 'Http Method',
        help: 'Method used to get request_token and access token',
        possibleValues: [
          { value: 'post', label: 'POST' },
          { value: 'get', label: 'GET' },
        ],
      },
    },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'New OAuth1 config' },
    },
    desc: {
      type: 'string',
      props: { label: 'Description', placeholder: 'New OAuth1 Description' },
    },
    consumerKey: {
      type: 'string',
      props: { label: 'Consumer key', placeholder: 'Consumer Key' },
    },
    consumerSecret: {
      type: 'string',
      props: { label: 'Consumer secret', placeholder: 'Consumer secret' },
    },
    allowedUsers: {
      type: 'array',
      props: {
        label: 'Allowed users',
      },
    },
    deniedUsers: {
      type: 'array',
      props: {
        label: 'Denied users',
      },
    },
    userValidators: {
      type: 'array',
      props: {
        label: 'User validators',
        component: UserValidator,
        defaultValue: {
          path: '$.profile.admin',
          value: true,
        },
      },
    },
    remoteValidators: {
      type: 'array',
      props: {
        label: 'Remote validators',
        component: RemoteValidator,
        defaultValue: {
          url: 'https://validator.oto.tools:3005/user_validation',
          headers: {},
          timeout: 3000,
        },
      },
    },
    // signatureMethod: {
    //   type: 'select',
    //   props: {
    //     label: 'Signature method',
    //     defaultValue: 'HMAC-SHA1',
    //     possibleValues: [
    //       { value: 'HmacSHA1', label: 'HMAC-SHA1' },
    //       { value: 'RSA-SHA1', label: 'RSA-SHA1' },
    //       { value: 'PLAINTEXT', label: 'PLAINTEXT' }
    //     ],
    //   }
    // },
    requestTokenURL: {
      type: 'string',
      props: { label: 'Request Token URL', placeholder: 'Request Token URL' },
    },
    authorizeURL: {
      type: 'string',
      props: { label: 'Authorize URL', placeholder: 'Authorize URL' },
    },
    accessTokenURL: {
      type: 'string',
      props: { label: 'Access token URL', placeholder: 'Access token URL' },
    },
    profileURL: {
      type: 'string',
      props: { label: 'Profile URL', placeholder: 'Profile URL' },
    },
    callbackURL: {
      type: 'string',
      props: {
        label: 'Callback URL',
        placeholder: 'Callback URL',
        help: 'Endpoint used to get back user after authentication on provider',
      },
    },
    rightsOverride: {
      type: ({ }) => (
        <JsonObjectAsCodeInput
          label="Rights override"
          mode="json"
          value={this.props.value.rightsOverride || {}}
          onChange={(e) => this.changeTheValue('rightsOverride', e)}
          example={{
            'no.name@oto.tools': [
              {
                tenant: '*:r',
                teams: ['*:r'],
              },
            ],
          }}
        />
      ),
      props: {
        label: 'Override rights',
      },
    },
    adminEntityValidatorsOverride: {
      type: ({ }) => (
        <JsonObjectAsCodeInput
          label="Admin entity validators override"
          mode="json"
          value={this.props.value.adminEntityValidatorsOverride || {}}
          onChange={(e) => {
            this.changeTheValue('adminEntityValidatorsOverride', e);
          }}
          example={{
            'john@otoroshi.tools': {
              route: [
                {
                  path: '$.*',
                  value: 'JsonContainsNot(${env.)',
                  error: 'You cannot use expression with env. variables',
                },
              ],
            },
          }}
        />
      ),
      props: {
        label: 'Admin entity validators override',
      },
    },
  };

  render() {
    return (
      <div>
        <Form
          value={this.props.value}
          onChange={this.props.onChange}
          flow={this.flow}
          schema={this.schema}
        />
      </div>
    );
  }
}

const SessionCookieConfig = (props) => {
  const changeTheValue = (name, value) => {
    if (props.onChange) {
      const clone = cloneDeep(props.value || props.settings);
      const path = name.startsWith('.') ? name.substr(1) : name;
      const newObj = deepSet(clone, path, value);
      console.debug({ newObj });
      props.onChange(newObj);
    } else {
      props.changeTheValue(name, value);
    }
  };
  const settings = props.value || props.settings;
  const path = props.path || '';

  return (
    <>
      <Separator title="Session cookie values" />
      <NumberInput
        label="Session max. age"
        value={settings.sessionMaxAge}
        help="..."
        suffix="seconds"
        onChange={(v) => changeTheValue(path + '.sessionMaxAge', v)}
      />
      <BooleanInput
        label="HttpOnly"
        value={settings.sessionCookieValues.httpOnly}
        help="Rewrite session cookie httpOnly to false or true"
        onChange={(v) => changeTheValue(path + '.sessionCookieValues.httpOnly', v)}
      />
      <BooleanInput
        label="secure"
        value={settings.sessionCookieValues.secure}
        help="Rewrite session cookie secure to false or true"
        onChange={(v) => changeTheValue(path + '.sessionCookieValues.secure', v)}
      />
    </>
  );
};

const UserValidator = (props) => {
  const validator = props.itemValue;

  function changeTheValue(field, value) {
    const arr = props.value;
    arr[props.idx][field] = value;
    props.onChange(arr);
  }

  return (
    <>
      <TextInput
        label="Json path"
        value={validator.path}
        help="The JSON Path to the validated element of the user"
        suffix="JSON Path"
        onChange={(v) => changeTheValue('path', v)}
      />
      <TextInput
        label="Value"
        value={isString(validator.value) ? validator.value : JSON.stringify(validator.value)}
        help="The value you want to validate"
        onChange={(v) => {
          try {
            const parsed = JSON.parse(v);
            changeTheValue('value', parsed);
          } catch (e) {
            changeTheValue('value', v);
          }
        }}
      />
    </>
  );
};

const RemoteValidator = (props) => {
  const validator = props.itemValue;

  function changeTheValue(field, value) {
    const arr = props.value;
    arr[props.idx][field] = value;
    props.onChange(arr);
  }

  return (
    <>
      <TextInput
        label="URL"
        value={validator.url}
        help="The remote validator url"
        onChange={(v) => changeTheValue('url', v)}
      />
      <ObjectInput
        label="Headers"
        value={validator.headers}
        help="The remote validator headers"
        onChange={(v) => changeTheValue('headers', v)}
      />
      <NumberInput
        label="Timeout"
        value={validator.timeout}
        help="The remote validator timeout"
        suffix="millis."
        onChange={(v) => changeTheValue('timeout', v)}
      />
    </>
  );
};
