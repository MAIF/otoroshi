import React, { Component } from 'react';

export class Separator extends Component {
  render() {
    return (
      <div className="row mb-3">
        <label className="col-sm-2" />
        <div className="col-sm-12 pb-2" style={{ borderBottom: '1px solid #666'}}>
          {this.props.title}
        </div>
      </div>
    );
  }
}
