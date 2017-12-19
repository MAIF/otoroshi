import React, { Component } from 'react';

export class Help extends Component {
  render() {
    if (this.props.text) {
      return (
        <i
          ref={r => $(r).tooltip({ container: 'body' })}
          className="fa fa-question-circle-o"
          data-toggle="tooltip"
          data-placement="top"
          title={this.props.text}
          style={{
            fontSize: 13,
            paddingBottom: 10,
            position: 'absolute',
            marginLeft: 5,
            marginTop: -5,
            paddingTop: 8,
            color: '#00B4CD',
          }}
        />
      );
    }
    return null;
  }
}
