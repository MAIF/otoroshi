import React, { Component } from 'react';

export class NotFoundPage extends Component {
  componentDidMount() {
    this.props.setTitle('Page not Found !');
  }

  render() {
    return <div />;
  }
}
