import React, { Component } from 'react';

export const LinkDisplay = (props) => (
  <div className="form-group">
    <label className="col-sm-2 control-label" />
    <div className="col-sm-10">
      <i className="fas fa-share-square" />{' '}
      <a href={props.link} target="_blank">
        {props.link}
      </a>
    </div>
  </div>
);
