import React, { Component } from 'react';
import { Events } from './events';

export class DynamicSidebar extends Component {
  static events = new Events();

  state = { content: null };

  componentDidMount() {
    this.unsubscribe = DynamicSidebar.events.subscribe(this.update);
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
    return <div className="mt-3">{this.state.content}</div>;
  }

  static setContent(content) {
    DynamicSidebar.events.dispatch(content);
  }
}
