import React, { Component } from 'react';

import { TextInput, NumberInput, SelectInput, CodeInput, BooleanInput } from './inputs';

import deepSet from 'set-value';
import _ from 'lodash';
import faker from 'faker';
import bcrypt from 'bcryptjs';

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
    callbackUrl: 'http://privateapps.foo.bar:8080/privateapps/generic/callback',
    accessTokenField: 'access_token',
    nameField: 'name',
    emailField: 'email',
    otoroshiDataField: 'app_metadata | otoroshi_data',
  };

  componentDidCatch(error) {
    const settings = this.props.value || this.props.settings;
    const path = this.props.path || '';
    console.log('Oauth2ModuleConfig did catch', error, path, settings);
    this.setState({ error });
  }

  changeTheValue = (name, value) => {
    if (this.props.onChange) {
      const clone = _.cloneDeep(this.props.value || this.props.settings);
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
          onChange={v => changeTheValue(path + '.id', v)}
        />
        <TextInput
          label="Name"
          value={settings.name}
          help="..."
          onChange={v => changeTheValue(path + '.name', v)}
        />
        <TextInput
          label="Description"
          value={settings.desc}
          help="..."
          onChange={v => changeTheValue(path + '.desc', v)}
        />
        <NumberInput
          label="Session max. age"
          value={settings.sessionMaxAge}
          help="..."
          suffix="seconds"
          onChange={v => changeTheValue(path + '.sessionMaxAge', v)}
        />
        <TextInput
          label="Client ID"
          value={settings.clientId}
          help="..."
          onChange={v => changeTheValue(path + '.clientId', v)}
        />
        <TextInput
          label="Client Secret"
          value={settings.clientSecret}
          help="..."
          onChange={v => changeTheValue(path + '.clientSecret', v)}
        />
        <TextInput
          label="Authorize URL"
          value={settings.authorizeUrl}
          help="..."
          onChange={v => changeTheValue(path + '.authorizeUrl', v)}
        />
        <TextInput
          label="Token URL"
          value={settings.tokenUrl}
          help="..."
          onChange={v => changeTheValue(path + '.tokenUrl', v)}
        />
        <TextInput
          label="Userinfo URL"
          value={settings.userInfoUrl}
          help="..."
          onChange={v => changeTheValue(path + '.userInfoUrl', v)}
        />
        <TextInput
          label="Login URL"
          value={settings.loginUrl}
          help="..."
          onChange={v => changeTheValue(path + '.loginUrl', v)}
        />
        <TextInput
          label="Logout URL"
          value={settings.logoutUrl}
          help="..."
          onChange={v => changeTheValue(path + '.logoutUrl', v)}
        />
        <TextInput
          label="Callback URL"
          value={settings.callbackUrl}
          help="..."
          onChange={v => changeTheValue(path + '.callbackUrl', v)}
        />
        <TextInput
          label="Access token field name"
          value={settings.accessTokenField}
          help="..."
          onChange={v => changeTheValue(path + '.accessTokenField', v)}
        />
        <TextInput
          label="Name field name"
          value={settings.nameField}
          help="..."
          onChange={v => changeTheValue(path + '.nameField', v)}
        />
        <TextInput
          label="Email field name"
          value={settings.emailField}
          help="..."
          onChange={v => changeTheValue(path + '.emailField', v)}
        />
        <TextInput
          label="Otoroshi metadata field name"
          value={settings.otoroshiDataField}
          help="..."
          onChange={v => changeTheValue(path + '.otoroshiDataField', v)}
        />
      </div>
    );
  }
}

export class User extends Component {
  render() {
    return (
      <div
        style={{
          display: 'flex',
        }}>
        <input
          type="text"
          placeholder="User name"
          className="form-control"
          value={this.props.user.name}
          onChange={e => this.props.onChange(this.props.user.email, 'name', e.target.value)}
        />
        <input
          type="text"
          placeholder="User email"
          className="form-control"
          value={this.props.user.email}
          onChange={e => this.props.onChange(this.props.user.email, 'email', e.target.value)}
        />
        <input
          type="text"
          placeholder="User metadata"
          className="form-control"
          value={JSON.stringify(this.props.user.metadata)}
          onChange={e => this.props.onChange(this.props.user.email, 'metadata', e.target.value)}
        />
        <button
          type="button"
          className="btn btn-sm btn-success"
          onClick={e => {
            const value1 = prompt('Type password');
            const value2 = prompt('Re-type password');
            if (value1 && value2 && value1 === value2) {
              this.props.hashPassword(this.props.user.email, value1);
            } else {
              windows.alert('Passwords does not match !');
            }
          }}
          style={{ marginLeft: 5 }}>
          Set password
        </button>
        <button
          type="button"
          className="btn btn-sm btn-success"
          onClick={e => {
            const password = faker.random.alphaNumeric(16);
            window.alert(`The generated password is: ${password}`);
            this.props.hashPassword(this.props.user.email, password);
          }}
          style={{ marginLeft: 5 }}>
          Generate password
        </button>
        <button
          type="button"
          className="btn btn-sm btn-danger"
          onClick={e => this.props.removeUser(this.props.user.email)}>
          <i className="glyphicon glyphicon-trash" />
        </button>
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
      const clone = _.cloneDeep(this.props.value || this.props.settings);
      const path = name.startsWith('.') ? name.substr(1) : name;
      const newObj = deepSet(clone, path, value);
      this.props.onChange(newObj);
    } else {
      this.props.changeTheValue(name, value);
    }
  };

  addUser = () => {
    const newValue = _.cloneDeep(this.props.value);
    const firstName = faker.name.firstName();
    const lastName = faker.name.lastName();
    newValue.users.push({
      name: firstName + ' ' + lastName,
      password: bcrypt.hashSync('password', bcrypt.genSaltSync(10)),
      email: firstName.toLowerCase() + '.' + lastName.toLowerCase() + '@foo.bar',
      metadata: {},
    });
    this.props.onChange(newValue);
  };

  removeUser = email => {
    const newValue = _.cloneDeep(this.props.value);
    newValue.users = newValue.users.filter(u => u.email !== email);
    this.props.onChange(newValue);
  };

  hashPassword = (email, password) => {
    const newValue = _.cloneDeep(this.props.value);
    newValue.users.map(user => {
      if (user.email === email) {
        user.password = bcrypt.hashSync(password, bcrypt.genSaltSync(10));
      }
    });
    this.props.onChange(newValue);
  };

  changeField = (email, name, value) => {
    const newValue = _.cloneDeep(this.props.value);
    newValue.users.map(user => {
      if (user.email === email) {
        user[name] = value;
      }
    });
    this.props.onChange(newValue);
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
          onChange={v => changeTheValue(path + '.id', v)}
        />
        <TextInput
          label="Name"
          value={settings.name}
          help="..."
          onChange={v => changeTheValue(path + '.name', v)}
        />
        <TextInput
          label="Description"
          value={settings.desc}
          help="..."
          onChange={v => changeTheValue(path + '.desc', v)}
        />
        <NumberInput
          label="Session max. age"
          value={settings.sessionMaxAge}
          help="..."
          suffix="seconds"
          onChange={v => changeTheValue(path + '.sessionMaxAge', v)}
        />
        <BooleanInput
          label="Basic auth."
          value={settings.basicAuth}
          help="..."
          onChange={v => changeTheValue(path + '.basicAuth', v)}
        />
        <div className="form-group">
          <label htmlFor={`input-users`} className="col-sm-2 control-label">
            Users
          </label>
          <div className="col-sm-10">
            {this.props.value.users.map(user => (
              <User
                user={user}
                removeUser={this.removeUser}
                hashPassword={this.hashPassword}
                onChange={this.changeField}
              />
            ))}
            <button
              type="button"
              className="btn btn-info"
              onClick={this.addUser}
              style={{ marginTop: 20 }}>
              <i className="glyphicon glyphicon-plus-sign" /> Add user
            </button>
          </div>
        </div>
        {!this.state.showRaw && (
          <div className="form-group">
            <label className="col-sm-2 control-label">Users raw</label>
            <div className="col-sm-10">
              <button
                type="button"
                className="btn btn-info"
                onClick={e => this.setState({ showRaw: !this.state.showRaw })}>
                Show raw users
              </button>
            </div>
          </div>
        )}
        {this.state.showRaw && (
          <div className="form-group">
            <label className="col-sm-2 control-label">Users raw</label>
            <div className="col-sm-10">
              <button
                type="button"
                className="btn btn-info"
                onClick={e => this.setState({ showRaw: !this.state.showRaw })}>
                Hide raw users
              </button>
            </div>
          </div>
        )}
        {this.state.showRaw && (
          <CodeInput
            label=""
            value={JSON.stringify(settings.users, null, 2)}
            help="..."
            onChange={v => changeTheValue(path + '.users', JSON.parse(v))}
          />
        )}
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
      const clone = _.cloneDeep(this.props.value || this.props.settings);
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
          onChange={v => changeTheValue(path + '.id', v)}
        />
        <TextInput
          label="Name"
          value={settings.name}
          help="..."
          onChange={v => changeTheValue(path + '.name', v)}
        />
        <TextInput
          label="Description"
          value={settings.desc}
          help="..."
          onChange={v => changeTheValue(path + '.desc', v)}
        />
        <NumberInput
          label="Session max. age"
          value={settings.sessionMaxAge}
          help="..."
          suffix="seconds"
          onChange={v => changeTheValue(path + '.sessionMaxAge', v)}
        />
        <BooleanInput
          label="Basic auth."
          value={settings.basicAuth}
          help="..."
          onChange={v => changeTheValue(path + '.basicAuth', v)}
        />
        <TextInput
          label="LDAP Server URL"
          value={settings.serverUrl}
          help="..."
          onChange={v => changeTheValue(path + '.serverUrl', v)}
        />
        <TextInput
          label="Search Base"
          value={settings.searchBase}
          help="..."
          onChange={v => changeTheValue(path + '.searchBase', v)}
        />
        <TextInput
          label="Users search base"
          value={settings.userBase}
          help="..."
          onChange={v => changeTheValue(path + '.userBase', v)}
        />
        <TextInput
          label="Group filter"
          value={settings.groupFilter}
          help="..."
          onChange={v => changeTheValue(path + '.groupFilter', v)}
        />
        <TextInput
          label="Search Filter"
          value={settings.searchFilter}
          help="use ${username} as placeholder for searched username"
          onChange={v => changeTheValue(path + '.searchFilter', v)}
        />
        <TextInput
          label="Admin username (bind DN)"
          value={settings.adminUsername}
          help="if one"
          onChange={v => changeTheValue(path + '.adminUsername', v)}
        />
        <TextInput
          label="Admin password"
          value={settings.adminPassword}
          help="if one"
          onChange={v => changeTheValue(path + '.adminPassword', v)}
        />
        <TextInput
          label="Name field name"
          value={settings.nameField}
          help="..."
          onChange={v => changeTheValue(path + '.nameField', v)}
        />
        <TextInput
          label="Email field name"
          value={settings.emailField}
          help="..."
          onChange={v => changeTheValue(path + '.emailField', v)}
        />
        <TextInput
          label="Otoroshi metadata field name"
          value={settings.metadataField}
          help="..."
          onChange={v => changeTheValue(path + '.metadataField', v)}
        />
      </div>
    );
  }
}

export class AuthModuleConfig extends Component {
  render() {
    const settings = this.props.value || this.props.settings;
    const selector = (
      <SelectInput
        label="Type"
        value={settings.type}
        onChange={e => {
          switch (e) {
            case 'basic':
              this.props.onChange({
                id: faker.random.alphaNumeric(64),
                type: 'basic',
                users: [
                  {
                    name: 'John Doe',
                    email: 'john.doe@foo.bar',
                    password: bcrypt.hashSync('password', bcrypt.genSaltSync(10)),
                    metadata: {},
                  },
                ],
              });
              break;
            case 'ldap':
              this.props.onChange({
                id: faker.random.alphaNumeric(64),
                type: 'ldap',
                serverUrl: 'ldap://ldap.forumsys.com:389',
                searchBase: 'dc=example,dc=com',
                searchFilter: '(uid=${username})',
                adminUsername: 'cn=read-only-admin,dc=example,dc=com',
                adminPassword: 'password',
                nameField: 'cn',
                emailField: 'mail',
                metadataField: null,
              });
              break;
            case 'oauth2':
              this.props.onChange({
                id: faker.random.alphaNumeric(64),
                type: 'oauth2',
                clientId: 'client',
                clientSecret: 'secret',
                authorizeUrl: 'http://my.iam.local:8082/oauth/authorize',
                tokenUrl: 'http://my.iam.local:8082/oauth/token',
                userInfoUrl: 'http://my.iam.local:8082/userinfo',
                loginUrl: 'http://my.iam.local:8082/login',
                logoutUrl: 'http://my.iam.local:8082/logout',
                callbackUrl: 'http://privateapps.foo.bar:8080/privateapps/generic/callback',
                accessTokenField: 'access_token',
                nameField: 'name',
                emailField: 'email',
                otoroshiDataField: 'app_metadata | otoroshi_data',
              });
              break;
          }
        }}
        possibleValues={[
          { label: 'Generic oauth2 provider', value: 'oauth2' },
          { label: 'In memory auth. provider (experimental)', value: 'basic' },
          { label: 'Ldap auth. provider (experimental)', value: 'ldap' },
        ]}
        help="The type of settings to log into your app."
      />
    );
    if (settings.type === 'oauth2') {
      return (
        <div>
          {selector}
          <Oauth2ModuleConfig {...this.props} />
        </div>
      );
    } else if (settings.type === 'basic') {
      return (
        <div>
          {selector}
          <BasicModuleConfig {...this.props} />
        </div>
      );
    } else if (settings.type === 'ldap') {
      return (
        <div>
          {selector}
          <LdapModuleConfig {...this.props} />
        </div>
      );
    } else {
      return <h3>Unknown config type ...</h3>;
    }
  }
}
