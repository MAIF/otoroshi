import React, { Component } from 'react';

export class Separator extends Component {
  render() {
    return (
      <div className="form-group">
          <label className="control-label col-sm-2"></label>
          <div className="col-sm-10" style={{ borderBottom: '1px solid #666', paddingBottom: 5 }}>{this.props.title}</div>
        </div>
    );
  }
}