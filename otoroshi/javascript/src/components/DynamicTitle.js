import React, { Component } from 'react';
import { Events } from './events';

export class DynamicTitle extends Component {
  static events = new Events();

  state = { content: null };

  componentDidMount() {
    this.unsubscribe = DynamicTitle.events.subscribe(this.update);
  }

  componentWillUnmount() {
    if (this.unsubscribe) {
      this.unsubscribe();
    }
  }

  update = content => {
    this.setState({ content });
  };

  render() {
    if (!this.state.content) return null;
    return (
      <div className="fixedH3">
        <h3 className="page-header">{this.state.content}</h3>
      </div>
    );
  }

  static getContent() {
    return DynamicTitle.content;
  }

  static setContent(content) {
    DynamicTitle.content = content;
    DynamicTitle.events.dispatch(content);
  }
}
