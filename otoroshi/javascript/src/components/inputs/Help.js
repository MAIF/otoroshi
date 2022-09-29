import React, { Component } from 'react';

export class Help extends Component {
  render() {
    const shouldRender = this.props.text && this.props.text !== '' && this.props.text !== '...';
    if (shouldRender) {
      return (
        <i
          ref={(r) => {
            if (r)
              new bootstrap.Tooltip(r, {
                container: 'body',
              });
          }}
          className={this.props.icon || 'far fa-question-circle'}
          data-bs-toggle="tooltip"
          data-bs-placement="top"
          title={this.props.text}
          style={{ color: this.props.iconColor }}
        />
      );
    }
    return null;
  }
}

export class HelpWrapper extends Component {
  render() {
    const shouldRender = this.props.text && this.props.text !== '' && this.props.text !== '...';
    if (shouldRender) {
      return (
        <div
          ref={(r) => {
            if (r)
              new bootstrap.Tooltip(r, {
                container: 'body',
              });
          }}
          data-bs-toggle="tooltip"
          data-bs-placement={this.props.dataPlacement || "top"}
          title={this.props.text}
          style={{
            height: '100%'
          }}
        >
          {this.props.children}
        </div>
      );
    }
    return this.props.children;
  }
}