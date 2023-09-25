import React from 'react';
import Select from 'react-select'
import Creatable from 'react-select/creatable';

export class ReactSelectOverride extends React.Component {

    state = {
        value: undefined
    }

    componentDidMount() {
        this.readProps()
    }

    componentDidUpdate(props) {
        if (this.props.value && props.value !== this.props.value) {
            this.readProps()
        }
    }

    isAnObject = v => typeof v === 'object' && v !== null;

    readProps = () => {
        const isOptionObject = this.isAnObject(this.props.options[0]);
        const opt = this.props.options.find(o => o === this.props.value || (isOptionObject ? o.value === this.props.value : false));

        console.log(this.props.options, this.props.value)

        if (opt) {
            this.setState({
                value: {
                    label: opt.name || opt.label,
                    value: opt.value
                }
            })
        } else if (this.props.value) {
            this.setState({ value: this.props.value })
        }
    }

    onChange = newItem => {
        this.setState({
            value: newItem
        }, () => {
            this.props.onChange((this.props.creatable || this.props.isMulti) ? newItem : newItem.value)
        })
    }

    render() {
        const Component = this.props.creatable ? Creatable : Select;
        return <Component
            {...this.props}
            value={this.state.value}
            onChange={this.onChange}
            components={{
                IndicatorSeparator: () => null,
            }}
            styles={{
                control: (baseStyles) => ({
                    ...baseStyles,
                    border: '1px solid var(--bg-color_level3)',
                    color: 'var(--text)',
                    backgroundColor: 'var(--bg-color_level2)',
                    boxShadow: 'none'
                }),
                menu: (baseStyles) => ({
                    ...baseStyles,
                    margin: 0,
                    borderTopLeftRadius: 0,
                    borderTopRightRadius: 0,
                    backgroundColor: 'var(--bg-color_level2)',
                    color: 'var(--text)'
                }),
                option: (provided, { isFocused }) => ({
                    ...provided,
                    backgroundColor: isFocused ? 'var(--bg-color_level2)' : 'var(--bg-color_level3)'
                }),
                input: provided => ({
                    ...provided,
                    color: 'var(--text)'
                }),
                MenuList: provided => ({
                    ...provided,
                    background: 'red'
                }),
                multiValueLabel: provided => ({
                    ...provided,
                    color: 'var(--text)',
                }),
                multiValueRemove: provided => ({
                    ...provided,
                    background: 'var(--color-primary)'
                }),
                multiValue: (provided, { isFocused }) => ({
                    ...provided,
                    color: 'var(--text)',
                    backgroundColor: isFocused ? 'var(--bg-color_level2)' : 'var(--bg-color_level3)'
                }),
                singleValue: provided => ({
                    ...provided,
                    color: 'var(--text)'
                })
            }}
        />
    }
}