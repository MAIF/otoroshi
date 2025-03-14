import React from 'react';
import Select from 'react-select';
import Creatable from 'react-select/creatable';

export class ReactSelectOverride extends React.Component {
  state = {
    value: undefined,
  };

  componentDidMount() {
    this.readProps();
  }

  componentDidUpdate(props) {
    if (this.props.value && props.value !== this.props.value) {
      this.readProps();
    }
  }

  readProps = () => {
    const isOptionObject = this.isAnObject(this.props.options[0]);
    const opt = this.props.options.find(
      (o) => o === this.props.value || (isOptionObject ? o.value === this.props.value : false)
    );

    if (opt) {
      this.setState({
        value: {
          label: opt.name || opt.label,
          value: opt.value,
        },
      });
    }
  };

  onChange = (newItem) => {
    this.setState(
      {
        value: newItem,
      },
      () => {
        this.props.onChange(this.props.creatable || this.props.isMulti ? newItem : newItem.value);
      }
    );
  };

  isAnObject = (v) => typeof v === 'object' && v !== null;

  readProps = () => {
    this.setState(this.getValue());
  };

  onChange = (newItem) => {
    this.setState(
      {
        value: newItem,
      },
      () => {
        this.props.onChange(this.props.creatable || this.props.isMulti ? newItem : newItem?.value);
      }
    );
  };

  getValue = () => {
    const isOptionObject = this.isAnObject(this.props.options[0]);
    const opt = this.props.options.find(
      (o) => o === this.props.value || (isOptionObject ? o.value === this.props.value : false)
    );

    if (this.props.isMulti) {
      return this.props.options.filter((o) => this.props.value.some((v) => v.value === o.value));
    }
    if (!opt) return undefined;

    return {
      label: opt.name || opt.label,
      value: opt.value,
    };
  };

  render() {
    const Component = this.props.creatable ? Creatable : Select;

    let components = this.props.components || {}

    if (this.props.noOptionsMessage) {
      components = {
        ...components,
        noOptionsMessage: this.props.noOptionsMessage
      }
    }

    return (
      <Component
        {...this.props}
        value={this.getValue()}
        onChange={this.onChange}
        isClearable={this.props.isClearable}
        components={{
          IndicatorSeparator: () => null,
          ...components
        }}
        styles={{
          control: (baseStyles) => ({
            ...baseStyles,
            border: '1px solid var(--bg-color_level3)',
            color: 'var(--text)',
            backgroundColor: 'var(--bg-color_level2)',
            boxShadow: 'none',
          }),
          menu: (baseStyles) => ({
            ...baseStyles,
            margin: 0,
            borderTopLeftRadius: 0,
            borderTopRightRadius: 0,
            backgroundColor: 'var(--bg-color_level2)',
            color: 'var(--text)',
          }),
          option: (provided, { isFocused }) => ({
            ...provided,
            backgroundColor: isFocused ? 'var(--bg-color_level2)' : 'var(--bg-color_level3)',
          }),
          input: (provided) => ({
            ...provided,
            color: 'var(--text)',
          }),

          MenuList: (provided) => ({
            ...provided,
            background: 'red',
          }),
          multiValueLabel: (provided) => ({
            ...provided,
            color: 'var(--text)',
          }),
          multiValueRemove: (provided) => ({
            ...provided,
            background: 'var(--color-primary)',
          }),
          multiValue: (provided, { isFocused }) => ({
            ...provided,
            color: 'var(--text)',
            backgroundColor: isFocused ? 'var(--bg-color_level2)' : 'var(--bg-color_level3)',
          }),
          singleValue: (provided) => ({
            ...provided,
            color: 'var(--text)',
          }),
          valueContainer: (baseStyles) => ({
            ...baseStyles,
            display: 'flex'
          }),
        }}
      />
    );
  }
}