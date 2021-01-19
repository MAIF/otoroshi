import React, { Component, Suspense } from 'react';
import PropTypes from 'prop-types';
import {
  ArrayInput,
  ObjectInput,
  BooleanInput,
  SelectInput,
  TextInput,
  NumberInput,
  LabelInput,
  DateTimeInput,
} from '.';
import { Location } from '../Location';
const CodeInput = React.lazy(() => Promise.resolve(require('./CodeInput')));

import _ from 'lodash';
import deepGet from 'get-value';
import deepSet from 'set-value';
import { Separator } from '../Separator';
import { Collapse } from './Collapse';
import { TextareaInput } from './TextInput';
import { ArraySelectInput } from './ArraySelectInput';

export class Form extends Component {
  static propTypes = {
    value: PropTypes.object,
    onChange: PropTypes.func,
    schema: PropTypes.object,
    flow: PropTypes.array,
  };

  theValue = () => {
    if (_.isFunction(this.props.value)) {
      return this.props.value();
    } else {
      return this.props.value;
    }
  };

  changeValue = (name, value) => {
    const newValue = _.cloneDeep(this.theValue());
    deepSet(newValue, name, value);
    this.props.onChange(newValue);
    //if (name.indexOf('.') > -1) {
    //  const [key1, key2] = name.split('.');
    //  const newValue = {
    //    ...this.theValue(),
    //    [key1]: { ...this.theValue()[key1], [key2]: value },
    //  };
    //  this.props.onChange(newValue);
    //} else {
    //  const newValue = { ...this.theValue(), [name]: value };
    //  this.props.onChange(newValue);
    //}
  };

  getValue = (name, defaultValue) => {
    if (name.indexOf('.') > -1) {
      //const [key1, key2] = name.split('.');
      //if (this.theValue()[key1]) {
      //  return this.theValue()[key1][key2] || defaultValue;
      //} else {
      //  return defaultValue;
      //}
      const value = deepGet(this.theValue(), name);
      return value || defaultValue;
    } else {
      return this.theValue()[name] || defaultValue;
    }
  };

  generateStep(name, idx) {
    if (_.isFunction(name)) {
      return React.createElement(name, {});
    } else if (React.isValidElement(name)) {
      return name;
    } else if (name.indexOf('>>>') === 0) {
      if (this.collapsed) {
        const collapsed = this.collapsed;
        const collapsedState = this.collapsedState;
        const collapsedLabel = this.collapsedLabel;
        this.collapsed = [];
        this.collapsedState = true;
        this.collapsedLabel = name.replace('>>>', '');
        return (
          <Collapse key={collapsedLabel} label={collapsedLabel} collapsed={collapsedState}>
            {collapsed}
          </Collapse>
        );
      } else {
        this.collapsed = [];
        this.collapsedState = true;
        this.collapsedLabel = name.replace('>>>', '');
        return null;
      }
    } else if (name.indexOf('<<<') === 0) {
      if (this.collapsed) {
        const collapsed = this.collapsed;
        const collapsedState = this.collapsedState;
        const collapsedLabel = this.collapsedLabel;
        this.collapsed = [];
        this.collapsedState = false;
        this.collapsedLabel = name.replace('<<<', '');
        return (
          <Collapse key={collapsedLabel} label={collapsedLabel} collapsed={collapsedState}>
            {collapsed}
          </Collapse>
        );
      } else {
        this.collapsed = [];
        this.collapsedState = false;
        this.collapsedLabel = name.replace('<<<', '');
        return null;
      }
    } else if (name === '---') {
      if (this.collapsed) {
        const collapsed = this.collapsed;
        const collapsedState = this.collapsedState;
        const collapsedLabel = this.collapsedLabel;
        delete this.collapsed;
        delete this.collapsedState;
        delete this.collapsedLabel;
        return (
          <Collapse
            key={collapsedLabel}
            label={collapsedLabel}
            collapsed={collapsedState}
            lineEnd={true}>
            {collapsed}
          </Collapse>
        );
      } else {
        return <hr key={idx} />;
      }
    } else {
      if (name.indexOf('-- ') === 0) {
        if (this.collapsed) {
          this.collapsed.push(<Separator title={name.replace('-- ', '')} />);
          return null;
        } else {
          return <Separator title={name.replace('-- ', '')} />;
        }
      }
      if (!this.props.schema[name]) {
        console.log('unable to find "', name, '" in', this.props.schema);
        return null;
      }
      const { display, type, disabled, props = {} } = this.props.schema[name];
      // console.log('generate', name, 'of type', type, 'from', this.props.schema);
      let component = null;
      if (display) {
        if (!display(this.theValue())) {
          return null;
        }
      }
      if (type) {
        if (type === 'location') {
          component = (
            <Location
              disabled={disabled}
              key={name}
              {...props}
              tenant={this.getValue(name + '.tenant', 'default')}
              onChangeTenant={(v) => this.changeValue(name + '.tenant', v)}
              teams={this.getValue(name + '.teams', ['default'])}
              onChangeTeams={(v) => this.changeValue(name + '.teams', v)}
            />
          );
        } else if (type === 'array') {
          component = (
            <ArrayInput
              disabled={disabled}
              key={name}
              value={this.getValue(name, [])}
              {...props}
              onChange={(v) => this.changeValue(name, v)}
            />
          );
        } else if (type === 'object') {
          component = (
            <ObjectInput
              disabled={disabled}
              key={name}
              value={this.getValue(name, {})}
              {...props}
              onChange={(v) => this.changeValue(name, v)}
            />
          );
        } else if (type === 'bool') {
          component = (
            <BooleanInput
              disabled={disabled}
              key={name}
              value={this.getValue(name, false)}
              {...props}
              onChange={(v) => this.changeValue(name, v)}
            />
          );
        } else if (type === 'select') {
          component = (
            <SelectInput
              disabled={disabled}
              key={name}
              value={this.getValue(name, props.defaultValue || '')}
              {...props}
              onChange={(v) => this.changeValue(name, v)}
            />
          );
        } else if (type === 'string') {
          component = (
            <TextInput
              disabled={disabled}
              key={name}
              value={this.getValue(name, '')}
              {...props}
              onChange={(v) => this.changeValue(name, v)}
            />
          );
        } else if (type === 'password') {
          component = (
            <TextInput
              disabled={disabled}
              key={name}
              value={this.getValue(name, '')}
              type="password"
              {...props}
              onChange={(v) => this.changeValue(name, v)}
            />
          );
        } else if (type === 'code') {
          component = (
            <Suspense fallback={<div>loading ...</div>}>
              <CodeInput
                disabled={disabled}
                key={name}
                value={this.getValue(name, '')}
                {...props}
                onChange={(v) => this.changeValue(name, v)}
              />
            </Suspense>
          );
        } else if (type === 'text') {
          component = (
            <TextareaInput
              disabled={disabled}
              key={name}
              value={this.getValue(name, '')}
              {...props}
              onChange={(v) => this.changeValue(name, v)}
            />
          );
        } else if (type === 'datetime') {
          component = (
            <DateTimeInput
              disabled={disabled}
              key={name}
              value={this.getValue(name, '')}
              {...props}
              onChange={(v) => this.changeValue(name, v)}
            />
          );
        } else if (type === 'label') {
          component = <LabelInput key={name} value={this.getValue(name, '')} {...props} />;
        } else if (type === 'number') {
          component = (
            <NumberInput
              disabled={disabled}
              key={name}
              value={this.getValue(name, 0)}
              {...props}
              onChange={(v) => this.changeValue(name, v)}
            />
          );
        } else if (type === 'array_select') {
          component = (
            <ArraySelectInput
              disabled={disabled}
              key={name}
              value={this.getValue(name, [])}
              {...props}
              onChange={(v) => this.changeValue(name, v)}
            />
          );
        } else if (_.isFunction(type)) {
          component = React.createElement(type, {
            ...props,
            disabled,
            rawValue: this.theValue(),
            rawOnChange: this.props.onChange,
            key: name,
            value: this.getValue(name, {}),
            changeValue: this.changeValue,
            onChange: (v) => this.changeValue(name, v),
          });
        } else if (React.isValidElement(type)) {
          component = type;
        } else {
          console.error(`No field named '${name}' of type ${type}`);
        }
      }
      if (this.collapsed) {
        this.collapsed.push(component);
        return null;
      } else {
        return component;
      }
    }
  }

  generateLastStep() {
    if (this.collapsed) {
      const collapsed = this.collapsed;
      const collapsedState = this.collapsedState;
      const collapsedLabel = this.collapsedLabel;
      delete this.collapsed;
      delete this.collapsedState;
      delete this.collapsedLabel;
      return (
        <Collapse key="last" label={collapsedLabel} collapsed={collapsedState}>
          {collapsed}
        </Collapse>
      );
    } else {
      return null;
    }
  }

  render() {
    return (
      <form style={this.props.style}>
        {this.props.flow.map((step, idx) => this.generateStep(step, idx))}
        {this.generateLastStep()}
      </form>
    );
  }
}
