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
import _ from 'lodash';

class RestrictionPath extends Component {
  changeTheValue = (key, value) => {
    const arrayValue = [...this.props.value];
    const item = arrayValue[this.props.idx];
    const newItem = deepSet(item, key, value);
    arrayValue[this.props.idx] = newItem;
    this.props.onChange(arrayValue);
  };

  render() {
    return (
      <div className="form__group mb-20 grid-template-bp1--fifth">
        <label />
        <div className="flex">
          <input
            className="w-50"
            placeholder="Http Method"
            type="text"
            value={this.props.itemValue.method}
            onChange={(e) => this.changeTheValue('method', e.target.value)}
          />
          <input
            className="w-50"
            placeholder="Http Path"
            type="text"
            value={this.props.itemValue.path}
            onChange={(e) => this.changeTheValue('path', e.target.value)}
          />
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
    console.log(value);
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
