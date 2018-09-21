import React, { Component } from 'react';

import {
  ArrayInput,
  ObjectInput,
  BooleanInput,
  LinkDisplay,
  SelectInput,
  TextInput,
  TextareaInput,
  NumberInput,
  FreeDomainInput,
  Help,
  Form,
} from './inputs';

import deepSet from 'set-value';
import _ from 'lodash';

export class LocationSettings extends Component {
  state = {
    error: null,
  };

  componentDidCatch(error) {
    const location = this.props.location;
    const path = this.props.path;
    console.log('AlgoSettings did catch', error, path, location);
    this.setState({ error });
  }

  render() {
    const location = this.props.location;
    const path = this.props.path;
    const changeTheValue = this.props.changeTheValue;
    if (this.state.error) {
      return <span>{this.state.error.message ? this.state.error.message : this.state.error}</span>;
    }
    return (
      <div>
        <SelectInput
          label={this.props.locationTitle || 'Source'}
          value={location.type}
          onChange={e => {
            switch (e) {
              case 'InQueryParam':
                changeTheValue(path + '', { type: 'InQueryParam', name: 'jwt-token' });
                break;
              case 'InHeader':
                changeTheValue(path + '', { type: 'InHeader', name: 'X-JWT-Token', remove: '' });
                break;
              case 'InCookie':
                changeTheValue(path + '', { type: 'InCookie', name: 'jwt-token' });
                break;
            }
          }}
          possibleValues={[
            { label: 'JWT token location in query string', value: 'InQueryParam' },
            { label: 'JWT token location in a header', value: 'InHeader' },
            { label: 'JWT token location in a cookie', value: 'InCookie' },
          ]}
          help="The location where to find/set the JWT token"
        />
        {location.type === 'InQueryParam' && (
          <TextInput
            label="Query param name"
            placeholder="jwt-token"
            value={location.name}
            help="The name of the query param where JWT is located"
            onChange={e => changeTheValue(path + '.name', e)}
          />
        )}
        {location.type === 'InHeader' && [
          <TextInput
            label="Header name"
            placeholder="jwt-token"
            value={location.name}
            help="The name of the header where JWT is located"
            onChange={e => changeTheValue(path + '.name', e)}
          />,
          <TextInput
            label="Remove value"
            placeholder="Bearer "
            value={location.remove}
            help="Remove a value (regexp) inside the header value"
            onChange={e => changeTheValue(path + '.remove', e)}
          />,
        ]}
        {location.type === 'InCookie' && (
          <TextInput
            label="Cookie name"
            placeholder="jwt-token"
            value={location.name}
            help="The name of the cookie where JWT is located"
            onChange={e => changeTheValue(path + '.name', e)}
          />
        )}
      </div>
    );
  }
}

export class AlgoSettings extends Component {
  state = {
    error: null,
  };

  componentDidCatch(error) {
    const algo = this.props.algo;
    const path = this.props.path;
    console.log('AlgoSettings did catch', error, path, algo);
    this.setState({ error });
  }

  render() {
    const algo = this.props.algo;
    const path = this.props.path;
    const changeTheValue = this.props.changeTheValue;
    if (this.state.error) {
      return <span>{this.state.error.message ? this.state.error.message : this.state.error}</span>;
    }
    return (
      <div>
        <SelectInput
          label={this.props.algoTitle || 'Algo.'}
          value={algo.type}
          onChange={e => {
            switch (e) {
              case 'HSAlgoSettings':
                changeTheValue(path + '', {
                  type: 'HSAlgoSettings',
                  size: 512,
                  secret: 'secret',
                });
                break;
              case 'RSAlgoSettings':
                changeTheValue(path + '', {
                  type: 'RSAlgoSettings',
                  size: 512,
                  publicKey: '-----BEGIN PUBLIC KEY-----\nxxxxxxxx\n-----END PUBLIC KEY-----',
                  privateKey: '-----BEGIN PRIVATE KEY-----\nxxxxxxxx\n-----END PRIVATE KEY-----',
                });
                break;
              case 'ESAlgoSettings':
                changeTheValue(path + '', {
                  type: 'ESAlgoSettings',
                  size: 512,
                  publicKey: '-----BEGIN PUBLIC KEY-----\nxxxxxxxx\n-----END PUBLIC KEY-----',
                  privateKey: '-----BEGIN PRIVATE KEY-----\nxxxxxxxx\n-----END PRIVATE KEY-----',
                });
                break;
            }
            // changeTheValue(path + '', e)
          }}
          possibleValues={[
            { label: 'Hmac + SHA', value: 'HSAlgoSettings' },
            { label: 'RSASSA-PKCS1 + SHA', value: 'RSAlgoSettings' },
            { label: 'ECDSA + SHA', value: 'ESAlgoSettings' },
          ]}
          help="What kind of algorithm you want to use to verify/sign your JWT token with"
        />
        {algo.type === 'HSAlgoSettings' && [
          <SelectInput
            label="SHA Size"
            help="Word size for the SHA-2 hash function used"
            value={algo.size}
            onChange={v => changeTheValue(path + '.size', v)}
            possibleValues={[
              { label: '256', value: 256 },
              { label: '384', value: 384 },
              { label: '512', value: 512 },
            ]}
          />,
          <TextInput
            label="Hmac secret"
            placeholder="secret"
            value={algo.secret}
            help="The Hmac secret"
            onChange={e => changeTheValue(path + '.secret', e)}
          />,
        ]}
        {algo.type === 'RSAlgoSettings' && [
          <SelectInput
            label="SHA Size"
            help="Word size for the SHA-2 hash function used"
            value={algo.size}
            onChange={v => changeTheValue(path + '.size', v)}
            possibleValues={[
              { label: '256', value: 256 },
              { label: '384', value: 384 },
              { label: '512', value: 512 },
            ]}
          />,
          <TextareaInput
            label="Public key"
            value={algo.publicKey}
            help="The RSA public key"
            onChange={e => changeTheValue(path + '.publicKey', e)}
          />,
          <TextareaInput
            label="Private key"
            value={algo.publicKey}
            help="The RSA private key, private key can be empty if not used for JWT token signing"
            onChange={e => changeTheValue(path + '.privateKey', e)}
          />,
        ]}
        {algo.type === 'ESAlgoSettings' && [
          <SelectInput
            label="SHA Size"
            help="Word size for the SHA-2 hash function used"
            value={algo.size}
            onChange={v => changeTheValue(path + '.size', v)}
            possibleValues={[
              { label: '256', value: 256 },
              { label: '384', value: 384 },
              { label: '512', value: 512 },
            ]}
          />,
          <TextareaInput
            label="Public key"
            value={algo.publicKey}
            help="The ECDSA public key"
            onChange={e => changeTheValue(path + '.publicKey', e)}
          />,
          <TextareaInput
            label="Private key"
            value={algo.publicKey}
            help="The ECDSA private key, private key can be empty if not used for JWT token signing"
            onChange={e => changeTheValue(path + '.privateKey', e)}
          />,
        ]}
      </div>
    );
  }
}

export class JwtVerifier extends Component {
  static defaultVerifier = {
    type: 'local',
    enabled: false,
    strict: true,
    source: { type: 'InHeader', name: 'X-JWT-Token', remove: '' },
    algoSettings: { type: 'HSAlgoSettings', size: 512, secret: 'secret' },
    strategy: { type: 'PassThrough', verificationSettings: { fields: { iss: 'The Issuer' } } },
  };

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
    const verifier = this.props.value || this.props.verifier;
    const path = this.props.path || '';
    const changeTheValue = this.changeTheValue;
    return (
      <div>
        {verifier.type === 'global' && (
          <TextInput
            label="Id"
            placeholder="The verifier Id"
            disabled
            value={verifier.id}
            help="The verifier Id"
            onChange={e => changeTheValue(path + '.id', e)}
          />
        )}
        {verifier.type === 'global' && (
          <TextInput
            label="Name"
            placeholder="The verifier name"
            value={verifier.name}
            help="The verifier name"
            onChange={e => changeTheValue(path + '.name', e)}
          />
        )}
        {verifier.type === 'global' && (
          <TextInput
            label="Description"
            placeholder="The verifier description"
            value={verifier.desc}
            help="The verifier description"
            onChange={e => changeTheValue(path + '.desc', e)}
          />
        )}
        {!this.props.global && <BooleanInput
          label="Enabled"
          value={verifier.enabled}
          help="Is JWT verification enabled for this service"
          onChange={v => changeTheValue(path + '.enabled', v)}
        />}
        <BooleanInput
          label="Strict"
          value={verifier.strict}
          help="If not strict, request without JWT token will be allowed to pass"
          onChange={v => changeTheValue(path + '.strict', v)}
        />
        <ArrayInput
          label="Excluded patterns"
          placeholder="URI pattern"
          suffix="regex"
          value={verifier.excludedPatterns}
          help="By default, when jwt verification is enabled, everything is verified. But sometimes you need to exclude something, so just add regex to matching path you want to exlude."
          onChange={v => changeTheValue(path + '.excludedPatterns', v)}
        />
        <br />
        {/* **************************************************************************************************** */}
        <LocationSettings
          path={`${path}.source`}
          changeTheValue={this.changeTheValue}
          location={verifier.source}
        />
        <br />
        {/* **************************************************************************************************** */}
        <AlgoSettings
          path={`${path}.algoSettings`}
          changeTheValue={this.changeTheValue}
          algo={verifier.algoSettings}
        />
        <br />
        {/* **************************************************************************************************** */}
        <SelectInput
          label="Verif. strategy"
          value={verifier.strategy.type}
          onChange={e => {
            switch (e) {
              case 'PassThrough':
                changeTheValue(path + '.strategy', {
                  type: 'PassThrough',
                  verificationSettings: {
                    fields: {
                      iss: 'The issuer',
                    },
                  },
                });
                break;
              case 'Sign':
                changeTheValue(path + '.strategy', {
                  type: 'Sign',
                  verificationSettings: {
                    fields: {
                      iss: 'The issuer',
                    },
                  },
                  algoSettings: {
                    type: 'HSAlgoSettings',
                    size: 512,
                    secret: 'secret',
                  },
                });
                break;
              case 'Transform':
                changeTheValue(path + '.strategy', {
                  type: 'Transform',
                  verificationSettings: {
                    fields: {
                      iss: 'The issuer',
                    },
                  },
                  algoSettings: {
                    type: 'HSAlgoSettings',
                    size: 512,
                    secret: 'secret',
                  },
                  transformSettings: {
                    location: {
                      type: 'InHeader',
                      name: 'X-JWT-Token',
                      remove: '',
                    },
                    mappingSettings: {
                      map: {
                        foo: 'bar',
                      },
                      values: {
                        newValue: 'foobar',
                      },
                    },
                  },
                });
                break;
            }
          }}
          possibleValues={[
            { label: 'Verify JWT token', value: 'PassThrough' },
            { label: 'Verify and re-sign JWT token', value: 'Sign' },
            { label: 'Verify, re-sign and transform JWT token', value: 'Transform' },
          ]}
          help="What kind of strategy is used for JWT token verification. PassThrough will only verifiy token signing and fields values if provided. Sign will do the same as PassThrough plus will re-sign the JWT token with the provided algo. settings. Transform will do the same as Sign plus will be able to transform the token."
        />
        {verifier.strategy.type === 'PassThrough' && [
          <ObjectInput
            label="Verify token fields"
            placeholderKey="Field name"
            placeholderValue="Field value"
            value={verifier.strategy.verificationSettings.fields}
            help="When the JWT token is checked, each field specified here will be verified with the provided value"
            onChange={v => changeTheValue(path + '.strategy.verificationSettings.fields', v)}
          />,
        ]}
        {verifier.strategy.type === 'Sign' && [
          <ObjectInput
            label="Verify token fields"
            placeholderKey="Field name"
            placeholderValue="Field value"
            value={verifier.strategy.verificationSettings.fields}
            help="When the JWT token is checked, each field specified here will be verified with the provided value"
            onChange={v => changeTheValue(path + '.strategy.verificationSettings.fields', v)}
          />,
          <AlgoSettings
            algoTitle="Re-sign algo."
            path={`${path}.strategy.algoSettings`}
            changeTheValue={this.changeTheValue}
            algo={verifier.strategy.algoSettings}
          />,
        ]}
        {verifier.strategy.type === 'Transform' && [
          <ObjectInput
            label="Verify token fields"
            placeholderKey="Field name"
            placeholderValue="Field value"
            value={verifier.strategy.verificationSettings.fields}
            help="When the JWT token is checked, each field specified here will be verified with the provided value"
            onChange={v => changeTheValue(path + '.strategy.verificationSettings.fields', v)}
          />,
          <AlgoSettings
            algoTitle="Re-sign algo."
            path={`${path}.strategy.algoSettings`}
            changeTheValue={this.changeTheValue}
            algo={verifier.strategy.algoSettings}
          />,
          <LocationSettings
            locationTitle="Token location"
            path={`${path}.strategy.transformSettings.location`}
            changeTheValue={this.changeTheValue}
            location={verifier.strategy.transformSettings.location}
          />,
          <ObjectInput
            label="Rename token fields"
            placeholderKey="Field name"
            placeholderValue="Field value"
            value={verifier.strategy.transformSettings.mappingSettings.map}
            help="When the JWT token is transformed, it is possible to change a field name, just specify origin field name and target field name"
            onChange={v =>
              changeTheValue(path + '.strategy.transformSettings.mappingSettings.map', v)
            }
          />,
          <ObjectInput
            label="Set token fields"
            placeholderKey="Field name"
            placeholderValue="Field value"
            value={verifier.strategy.transformSettings.mappingSettings.values}
            help="When the JWT token is transformed, it is possible to add new field with static values, just specify field name and value"
            onChange={v =>
              changeTheValue(path + '.strategy.transformSettings.mappingSettings.values', v)
            }
          />,
          <ArrayInput
            label="Remove token fields"
            placeholder="Field name"
            value={verifier.strategy.transformSettings.mappingSettings.remove}
            help="When the JWT token is transformed, it is possible to remove fields"
            onChange={v =>
              changeTheValue(path + '.strategy.transformSettings.mappingSettings.remove', v)
            }
          />,
        ]}
      </div>
    );
  }
}
