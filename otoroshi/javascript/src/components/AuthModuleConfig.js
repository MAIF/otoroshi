import React, { Component } from 'react';

import { TextInput, TextareaInput, SelectInput, CodeInput } from './inputs';

import deepSet from 'set-value';
import _ from 'lodash';

export class Oauth2ModuleConfig extends Component {
  state = {
    error: null,
  };

  static defaultConfig = {
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
    otoroshiDataField: 'app_metadata | otoroshi_data'
  };

  componentDidCatch(error) {
    const settings = this.props.value || this.props.settings;
    const path = this.props.path || '';
    console.log('Oauth2ModuleConfig did catch', error, path, settings);
    this.setState({ error });
  }

  changeTheValue = (name, value) => {
    console.log('changeTheValue', name, value);
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
    console.log(settings);
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

export class BasicModuleConfig extends Component {
  state = {
    error: null,
  };

  componentDidCatch(error) {
    const settings = this.props.value || this.props.settings;
    const path = this.props.path || '';
    console.log('BasicModuleConfig did catch', error, path, settings);
    this.setState({ error });
  }

  changeTheValue = (name, value) => {
    console.log('changeTheValue', name, value);
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
        <CodeInput 
          label="Users"
          value={JSON.stringify(settings.users, null, 2)}
          help="..."
          onChange={v => changeTheValue(path + '.users', JSON.parse(v))}
        />
      </div>
    );
  }
}

export class AuthModuleConfig extends Component {
  render() {
    const settings = this.props.value || this.props.settings;
    const selector = <SelectInput
      label="Type"
      value={settings.type}
      onChange={e => {
        switch (e) {
          case 'basic':
            this.props.onChange({ 
              type: 'basic',
              users: []
            });
            break;
          case 'oauth2':
            this.props.onChange({ 
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
            });
            break;
        }
      }}
      possibleValues={[
        { label: 'Generic OAuth2 provider', value: 'oauth2' },
        { label: 'Basic Auth provider', value: 'basic' },
      ]}
      help="The type of settings to log into your private app"
    />
    if (settings.type === 'oauth2') {
      return <div>{selector}<Oauth2ModuleConfig {...this.props} /></div>;
    } else if (settings.type === 'basic') {
      return <div>{selector}<BasicModuleConfig {...this.props} /></div>;
    } else {
      return <h3>Unknown config type ...</h3>
    }
  }
}
