import React, { Component, Suspense } from 'react';

import {
  TextInput,
  NumberInput,
  SelectInput,
  ArrayInput,
  BooleanInput,
  PasswordInput,
} from './inputs';

import { Separator } from './Separator';

import deepSet from 'set-value';
import { ReactSelectOverride } from './inputs/ReactSelectOverride';
// import _ from 'lodash';

export class RestrictionPath extends Component {

  state = { values: [] }

  componentDidMount() {
    const v = this.props.value[this.props.idx];
    if (v?.authorized_entity?.kind) {
      this.updateValues(v?.authorized_entity?.kind);
    }
  }

  changeTheValue = (key, value) => {
    const arrayValue = [...this.props.value];
    const item = arrayValue[this.props.idx];
    const newItem = deepSet(item, key, value);
    arrayValue[this.props.idx] = newItem;
    this.props.onChange(arrayValue);
  };

  updateValues = (kind) => {
    if (kind === 'api') {
      fetch('/bo/api/proxy/apis/apis.otoroshi.io/v1/apis', {
        method: 'GET',
        credentials: 'include',
        headers: {
          'Accept': 'application/json',
        }
      }).then(r => r.json()).then((values) => this.setState({ values }))
    } else if (kind === 'route') {
      fetch('/bo/api/proxy/apis/proxy.otoroshi.io/v1/routes', {
        method: 'GET',
        credentials: 'include',
        headers: {
          'Accept': 'application/json',
        }
      }).then(r => r.json()).then((values) => this.setState({ values }))
    } else if (kind === 'group') {
      fetch('/bo/api/proxy/apis/organize.otoroshi.io/v1/service-groups', {
        method: 'GET',
        credentials: 'include',
        headers: {
          'Accept': 'application/json',
        }
      }).then(r => r.json()).then((values) => this.setState({ values }))
    }
  }

  render() {
    return (
      <div className="row mb-3">
        <label className="col-xs-12 col-sm-2 col-form-label" />
        <div className="col-sm-10 d-flex">
          <input
            className="form-control"
            style={{ width: '20%' }}
            placeholder="Http Method"
            type="text"
            value={this.props.itemValue.method}
            onChange={(e) => this.changeTheValue('method', e.target.value)}
          />
          <input
            className="form-control"
            style={{ width: '30%' }}
            placeholder="Http Path"
            type="text"
            value={this.props.itemValue.path}
            onChange={(e) => this.changeTheValue('path', e.target.value)}
          />
          <ReactSelectOverride
            style={{ width: '20%' }}
            value={this.props.value[this.props.idx].authorized_entity?.kind}
            onChange={e => {
              const arrayValue = [...this.props.value];
              const item = arrayValue[this.props.idx];
              if (e === 'any') {
                delete item.authorized_entity;
              } else {
                item.authorized_entity = item.authorized_entity || {};
                item.authorized_entity.kind = e;
                this.updateValues(e);
              }
              arrayValue[this.props.idx] = item;
              this.props.onChange(arrayValue);
            }}
            options={[
              { label: "Any", value: "any" },
              { label: "Route", value: "route" },
              { label: "API", value: "api" },
              { label: "Service group", value: "group" },
            ]}
          />
          {this.props.value[this.props.idx].authorized_entity?.kind && (
            <div style={{ width: '30%' }}>
              <ReactSelectOverride
                value={this.props.value[this.props.idx].authorized_entity?.id}
                onChange={e => {
                  const arrayValue = [...this.props.value];
                  const item = arrayValue[this.props.idx];
                  item.authorized_entity = item.authorized_entity || {};
                  item.authorized_entity.id = e;
                  arrayValue[this.props.idx] = item;
                  this.props.onChange(arrayValue);
                }}
                options={this.state.values.map(v => ({ label: v.name, value: v.id }))}
              />
            </div>
          )}
        </div>
      </div>
    );
  }
}

export class Restrictions extends Component {
  changeTheValue = (name, value) => {
    const newValue = deepSet({ ...this.props.value }, name, value);
    this.props.onChange(newValue);
  };
  render() {
    const value = this.props.value;
    return (
      <div>
        <BooleanInput
          label="Enabled"
          value={value.enabled}
          help="Enable restrictions"
          onChange={(v) => this.changeTheValue('enabled', v)}
        />
        <BooleanInput
          label="Allow last"
          value={value.allowLast}
          help="Otoroshi will test forbidden and notFound paths before testing allowed paths"
          onChange={(v) => this.changeTheValue('allowLast', v)}
        />
        <ArrayInput
          label="Allowed"
          value={value.allowed}
          help="Allowed paths"
          component={RestrictionPath}
          defaultValue={{ method: '*', path: '/.*' }}
          onChange={(v) => this.changeTheValue('allowed', v)}
        />
        <ArrayInput
          label="Forbidden"
          value={value.forbidden}
          help="Forbidden paths"
          component={RestrictionPath}
          defaultValue={{ method: '*', path: '/.*' }}
          onChange={(v) => this.changeTheValue('forbidden', v)}
        />
        <ArrayInput
          label="Not Found"
          value={value.notFound}
          help="Not found paths"
          component={RestrictionPath}
          defaultValue={{ method: '*', path: '/.*' }}
          onChange={(v) => this.changeTheValue('notFound', v)}
        />
      </div>
    );
  }
}
