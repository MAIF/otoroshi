import React, { Component } from 'react';
import { Help } from './Help';

export class LabelInput extends Component {
  state = {
    value: this.props.value,
    loading: false,
  };

  identity(v) {
    return v;
  }

  componentDidMount() {
    const transform = this.props.transform || this.identity;
    if (this.props.from) {
      this.props.from().then((value) => this.setState({ value: transform(value) }));
    }
  }

  render() {
    return (
      <div className="form__group mb-20 grid-template-xs--fifth">
        <label>
          {this.props.label} <Help text={this.props.help} />
        </label>
        <div>
          {!this.state.loading && <p>Loading ...</p>}
          {this.state.loading && <p>{this.state.value}</p>}
        </div>
      </div>
    );
  }
}

export class HelpInput extends Component {
  state = {
    value: this.props.value,
  };

  render() {
    return (
      <div className="form__group mb-20 grid-template-xs--fifth">
        <label />
        <div>{this.state.value}</div>
      </div>
    );
  }
}
