import React, { Component } from 'react';
import Select from 'react-select';
import { Help } from './Help';

export class SelectInput extends Component {
  state = {
    error: null,
    loading: false,
    value: this.props.value || null,
    values: (this.props.possibleValues || []).map((a) => ({
      label: a.label || a,
      value: a.value || a,
    })),
  };

  componentDidMount() {
    if (this.props.valuesFrom) {
      this.reloadValues();
    }
  }

  componentWillReceiveProps(nextProps) {
    if (nextProps.valuesFrom && nextProps.value !== this.props.value) {
      this.reloadValues().then(() => {
        this.setState({ value: nextProps.value });
      });
    }
    if (nextProps.possibleValues !== this.props.possibleValues) {
      this.setState({
        values: (nextProps.possibleValues || []).map((a) => ({
          label: a.label || a,
          value: a.value || a,
        })),
      });
    }
    if (!nextProps.valuesFrom && nextProps.value !== this.props.value) {
      this.setState({ value: nextProps.value });
    }
  }

  componentDidCatch(error) {
    console.log('SelectInput catches error', error, this.state);
    this.setState({ error });
  }

  reloadValues = () => {
    this.setState({ loading: true });
    return fetch(this.props.valuesFrom, {
      method: 'GET',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
      },
    })
      .then((r) => r.json())
      .then((values) =>
        values.map((v) => {
          if (this.props.transformerMapping) {
            const value = v[this.props.transformerMapping.value];
            const label = v[this.props.transformerMapping.label];
            return { value, label };
          } else if (this.props.transformer) {
            return this.props.transformer(v);
          } else {
            return v;
          }
        })
      )
      .then((values) => this.setState({ values, loading: false }));
  };

  onChange = (e) => {
    if (e) {
      this.setState({ value: e.value });
      this.props.onChange(e.value);
    } else {
      this.setState({ value: null });
      this.props.onChange(null);
    }
  };

  onChangeClassic = (e) => {
    this.setState({ value: e.target.value });
    this.props.onChange(e.target.value);
  };

  render() {
    if (this.state.error) {
      return (
        <div className="form-group">
          <label htmlFor={`input-${this.props.label}`} className="col-xs-12 col-sm-2 control-label">
            {this.props.label} <Help text={this.props.help} />
          </label>
          <div className="col-sm-10">
            <div style={{ width: '100%' }}>
              <span>{this.state.error.message ? this.state.error.message : this.state.error}</span>
            </div>
          </div>
        </div>
      );
    }
    if (this.props.classic && !this.props.disabled) {
      return (
        <div className="form-group">
          <label htmlFor={`input-${this.props.label}`} className="col-xs-12 col-sm-2 control-label">
            {this.props.label} <Help text={this.props.help} />
          </label>
          <div className="col-sm-10">
            <div style={{ width: '100%' }}>
              <select
                className="form-control classic-select"
                value={this.state.value}
                onChange={this.onChangeClassic}>
                {this.state.values.map((value) => (
                  <option value={value.value}>{value.label}</option>
                ))}
              </select>
            </div>
          </div>
        </div>
      );
    }
    if (this.props.staticValues) {
      return (
        <div style={{ width: '100%' }}>
          <Select
            style={{ width: '100%' }}
            name={`${this.props.label}-search`}
            isLoading={this.state.loading}
            value={this.state.value}
            placeholder={this.props.placeholder}
            options={[...(this.props.staticValues || []), ...this.state.values]}
            onChange={this.onChange}
          />
        </div>
      );
    }
    return (
      <div className="form-group">
        <label htmlFor={`input-${this.props.label}`} className="col-xs-12 col-sm-2 control-label">
          {this.props.label} <Help text={this.props.help} />
        </label>
        <div className="col-sm-10">
          <div style={{ width: '100%' }}>
            {!this.props.disabled && (
              <Select
                style={{ width: this.props.more ? '100%' : '100%' }}
                name={`${this.props.label}-search`}
                isLoading={this.state.loading}
                value={this.state.value}
                placeholder={this.props.placeholder}
                options={this.state.values}
                onChange={this.onChange}
              />
            )}
            {this.props.disabled && (
              <input
                type="text"
                className="form-control"
                disabled={true}
                placeholder={this.props.placeholder}
                value={this.state.value}
                onChange={this.onChange}
              />
            )}
          </div>
        </div>
      </div>
    );
  }
}
