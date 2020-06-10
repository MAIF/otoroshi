import React, { Component } from 'react';

export class Help extends Component {
  render() {
    const shouldRender = this.props.text && this.props.text !== "" && this.props.text !== "...";
    if (shouldRender) {
      return (
        <i
          ref={r => $(r).tooltip({ container: 'body' })}
          className="far fa-question-circle"
          data-toggle="tooltip"
          data-placement="top"
          title={this.props.text}
        />
      );
    }
    return null;
  }
}
