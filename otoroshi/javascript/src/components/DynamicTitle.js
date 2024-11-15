import React, { Component } from 'react';
import { Events } from './events';
import Thumbtack from './Thumbtack';

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
      return (
        <div style={{ position: 'relative' }}>
          {this.state.content()}
          {this.props.children}
        </div>
      );
    }

    return (
      <div style={{ position: 'relative' }}>
        <div className="page-header_title d-flex">
          <div className="d-flex ms-3">
              <h3 className="m-0 align-self-center">
                {this.state.content} <Thumbtack {...this.props} getTitle={DynamicTitle.getContent} />
              </h3>
            </div>
        </div>
        <div className="dynamicChildren">
          {this.props.children}
        </div>
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
