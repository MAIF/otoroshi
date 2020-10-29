import React, { Component } from 'react';
import _ from 'lodash';
import deepSet from 'set-value';

import { ArrayInput, TextInput, PasswordInput, NumberInput } from './inputs';

export class Proxy extends Component {
  changeTheValue = (name, value) => {
    const proxy = _.cloneDeep(this.props.value || {});
    const newProxy = deepSet(proxy, name, value);
    if (this.props.onChange) {
      this.props.onChange(newProxy);
    }
  };

  render() {
    const proxy = this.props.value || {};
    return (
      <div>
        <TextInput
          label="Proxy host"
          value={proxy.host}
          help="Proxy host"
          onChange={(v) => this.changeTheValue('host', v)}
        />
        <NumberInput
          label="Proxy port"
          value={proxy.port}
          help="Proxy port"
          onChange={(v) => this.changeTheValue('port', v)}
        />
        <TextInput
          label="Proxy principal"
          value={proxy.principal}
          help="Proxy principal"
          onChange={(v) => this.changeTheValue('principal', v)}
        />
        <PasswordInput
          label="Proxy password"
          value={proxy.password}
          help="Proxy password"
          onChange={(v) => this.changeTheValue('password', v)}
        />
        {/*
        <SelectInput
          label="Proxy protocol"
          value={proxy.protocol}
          help="Proxy protocol"
          possibleValues={[
            { label: 'HTTP', value: 'http' },
            { label: 'HTTPS', value: 'https' },
            { label: 'Kerberos', value: 'kerberos' },
            { label: 'NTLM', value: 'ntlm' },
            { label: 'Spnego', value: 'spnego' },
          ]}
          onChange={v => this.changeTheValue('protocol', v)}
        />
        <TextInput
          label="Proxy ntlm domain"
          value={proxy.ntlmDomain}
          help="Proxy ntlmDomain"
          onChange={v => this.changeTheValue('ntlmDomain', v)}
        />
        <TextInput
          label="Proxy encoding"
          value={proxy.encoding}
          help="Proxy encoding"
          placeholder="UTF-8"
          onChange={v => this.changeTheValue('encoding', v)}
        />
        */}
        {this.props.showNonProxyHosts && (
          <ArrayInput
            label="Non proxy host"
            placeholder="IP address that can access the service"
            value={proxy.nonProxyHosts}
            help="List of non proxyable host"
            onChange={(arr) => this.changeTheValue('nonProxyHosts', arr)}
          />
        )}
      </div>
    );
  }
}
