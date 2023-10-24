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

  update = (content) => {
    this.setState({ content });
  };

  render() {
    if (!this.state.content) {
      return this.props.children;
    }
    
    if (typeof this.state.content === 'function') {
      return <div style={{ position: 'relative' }}>
        {this.state.content()}
        {this.props.children}
      </div>
    }

    return <div style={{ position: 'relative' }}>
      <div className="page-header">
        <h3 className="page-header_title">{this.state.content}</h3>
      </div>
      {this.props.children}
    </div>
  }

  static getContent() {
    return DynamicTitle.content;
  }

  static setContent(content) {
    DynamicTitle.content = content;
    DynamicTitle.events.dispatch(content);
  }
}
