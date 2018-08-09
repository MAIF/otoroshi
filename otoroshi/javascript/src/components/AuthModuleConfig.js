import React, { Component } from 'react';

import { TextInput } from './inputs';

import deepSet from 'set-value';
import _ from 'lodash';

export class AuthModuleConfig extends Component {
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
  };

  componentDidCatch(error) {
    const settings = this.props.value || this.props.settings;
    const path = this.props.path || '';
    console.log('AuthModuleConfig did catch', error, path, settings);
    this.setState({ error });
  }

  changeTheValue = (name, value) => {
    console.log('changeTheValue', name, value);
    if (this.props.onChange) {
      const clone = _.cloneDeep(this.props.value || this.props.verifier);
      const path = name.startsWith('.') ? name.substr(1) : name;
      const newObj = deepSet(clone, path, value);
      // console.log('changeTheValue', name, path, value, newObj)
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
          hide={settings.type !== 'oauth2-global'}
          label="Id"
          value={settings.id}
          help="..."
          onChange={v => changeTheValue(path + '.id', v)}
        />
        <TextInput
          hide={settings.type !== 'oauth2-global'}
          label="Name"
          value={settings.name}
          help="..."
          onChange={v => changeTheValue(path + '.name', v)}
        />
        <TextInput
          hide={settings.type !== 'oauth2-global'}
          label="Description"
          value={settings.desc}
          help="..."
          onChange={v => changeTheValue(path + '.desc', v)}
        />
        <TextInput
          hide={settings.type !== 'oauth2' && settings.type !== 'oauth2-global'}
          label="Client ID"
          value={settings.clientId}
          help="..."
          onChange={v => changeTheValue(path + '.clientId', v)}
        />
        <TextInput
          hide={settings.type !== 'oauth2' && settings.type !== 'oauth2-global'}
          label="Client Secret"
          value={settings.clientSecret}
          help="..."
          onChange={v => changeTheValue(path + '.clientSecret', v)}
        />
        <TextInput
          hide={settings.type !== 'oauth2' && settings.type !== 'oauth2-global'}
          label="Authorize URL"
          value={settings.authorizeUrl}
          help="..."
          onChange={v => changeTheValue(path + '.authorizeUrl', v)}
        />
        <TextInput
          hide={settings.type !== 'oauth2' && settings.type !== 'oauth2-global'}
          label="Token URL"
          value={settings.tokenUrl}
          help="..."
          onChange={v => changeTheValue(path + '.tokenUrl', v)}
        />
        <TextInput
          hide={settings.type !== 'oauth2' && settings.type !== 'oauth2-global'}
          label="Userinfo URL"
          value={settings.userInfoUrl}
          help="..."
          onChange={v => changeTheValue(path + '.userInfoUrl', v)}
        />
        <TextInput
          hide={settings.type !== 'oauth2' && settings.type !== 'oauth2-global'}
          label="Login URL"
          value={settings.loginUrl}
          help="..."
          onChange={v => changeTheValue(path + '.loginUrl', v)}
        />
        <TextInput
          hide={settings.type !== 'oauth2' && settings.type !== 'oauth2-global'}
          label="Logout URL"
          value={settings.logoutUrl}
          help="..."
          onChange={v => changeTheValue(path + '.logoutUrl', v)}
        />
        <TextInput
          hide={settings.type !== 'oauth2' && settings.type !== 'oauth2-global'}
          label="Callback URL"
          value={settings.callbackUrl}
          help="..."
          onChange={v => changeTheValue(path + '.callbackUrl', v)}
        />
        <TextInput
          hide={settings.type !== 'oauth2' && settings.type !== 'oauth2-global'}
          label="Access token field name"
          value={settings.accessTokenField}
          help="..."
          onChange={v => changeTheValue(path + '.accessTokenField', v)}
        />
        <TextInput
          hide={settings.type !== 'oauth2' && settings.type !== 'oauth2-global'}
          label="Name field name"
          value={settings.nameField}
          help="..."
          onChange={v => changeTheValue(path + '.nameField', v)}
        />
        <TextInput
          hide={settings.type !== 'oauth2' && settings.type !== 'oauth2-global'}
          label="Email field name"
          value={settings.emailField}
          help="..."
          onChange={v => changeTheValue(path + '.emailField', v)}
        />
        <TextInput
          hide={settings.type !== 'oauth2' && settings.type !== 'oauth2-global'}
          label="Otoroshi metadata field name"
          value={settings.otoroshiDataField}
          help="..."
          onChange={v => changeTheValue(path + '.otoroshiDataField', v)}
        />
      </div>
    );
  }
}