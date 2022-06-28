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
          className={this.props.icon || "far fa-question-circle"}
          data-toggle="tooltip"
          data-placement="top"
          title={this.props.text}
          style={{ color: this.props.iconColor }}
        />
      );
    }
    return null;
  }
}
