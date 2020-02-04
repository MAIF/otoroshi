import React, { Component } from 'react';
import { Help } from './Help';
import _ from 'lodash';

function parseDomain(service) {
  let domain = service.domain;
  if (service.env && service.env !== 'prod') {
    domain = service.env + '.' + domain;
  }
  if (service.subdomain && service.subdomain.trim().length > 0) {
    domain = service.subdomain + '.' + domain;
  }
  if (service.matchingRoot) {
    return (service.forceHttps ? 'https://' : 'http://') + domain + service.matchingRoot;
  } else {
    return (service.forceHttps ? 'https://' : 'http://') + domain;
  }
}

export class FreeDomainInput extends Component {
  state = {
    rawValue: parseDomain(this.props.value),
    value: parseDomain(this.props.value),
    error: null,
  };

  change = e => {
    const rawValue = e.target.value;
    this.setState({ rawValue });
    const newService = _.cloneDeep(this.props.value);
    this.setState({ value: rawValue });
    if (rawValue.indexOf('https://') === 0) {
      newService.forceHttps = true;
    } else if (rawValue.indexOf('http://') === 0) {
      newService.forceHttps = false;
    } else {
      newService.forceHttps = false;
    }
    let value = e.target.value
      .replace('https://', '')
      .replace('http://', '')
      .replace('://', '');
    if (value.indexOf('/') > -1) {
      const parts = value.split('/');
      const lastParts = [...parts];
      lastParts.shift();
      const matchingRoot = '/' + lastParts.join('/');
      value = parts[0];
      if (matchingRoot.trim() !== '/') {
        newService.matchingRoot = matchingRoot;
      } else {
        newService.matchingRoot = null;
      }
    } else {
      newService.matchingRoot = null;
    }
    const reverseParts = value
      .split('.')
      .reverse()
      .filter(i => !!i);
    this.setState({ error: null });
    if (reverseParts.length > 3) {
      newService.domain = `${reverseParts[1]}.${reverseParts[0]}`;
      newService.env = reverseParts[2];
      reverseParts.shift();
      reverseParts.shift();
      reverseParts.shift();
      newService.subdomain = reverseParts.reverse().join('.');
    } else if (reverseParts.length > 2) {
      newService.domain = `${reverseParts[1]}.${reverseParts[0]}`;
      newService.env = 'prod';
      reverseParts.shift();
      reverseParts.shift();
      newService.subdomain = reverseParts.reverse().join('.');
    } else if (reverseParts.length === 2) {
      newService.domain = reverseParts[0];
      newService.env = 'prod';
      newService.subdomain = reverseParts[1];
    } else if (reverseParts.length === 1) {
      newService.domain = reverseParts[0];
      newService.env = 'prod';
      newService.subdomain = '';
    } else {
      newService.domain = '';
      newService.env = 'prod';
      newService.subdomain = '';
      this.setState({
        error:
          'Should follow pattern: (http|https)://subdomain?.env?.domain.tld?/root? or regex (http|https)://(.*?).?(.*?).?(.*?).?(.*)/?(.*)',
      });
    }
    this.props.onChange(newService);
  };

  render() {
    const stateValue = this.state.value;
    const propsValue = this.state.rawValue; // parseDomain(this.props.value);
    const value = stateValue !== propsValue ? propsValue : stateValue;
    return (
      <div className="form-group">
        <label htmlFor={`input-${this.props.label}`} className="col-xs-12 col-sm-2 control-label">
          {this.props.label} <Help text={this.props.help} />
        </label>
        <div className="col-sm-10">
          <input
            type="text"
            placeholder={this.props.placeholder}
            className="form-control"
            value={value}
            onChange={this.change}
            disabled={this.props.disabled}
          />
          {this.state.error && <span className="label label-danger">{this.state.error}</span>}
        </div>
      </div>
    );
  }
}
