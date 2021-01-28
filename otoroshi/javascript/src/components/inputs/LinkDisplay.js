import React, { Component } from 'react';

export const LinkDisplay = (props) => (
  <div className="form__group mb-20 grid-template-xs--fifth">
    <label />
    <div>
      <a href={props.link} target="_blank">
        <i className="fas fa-share-square mr-5" />
        {props.link}
      </a>
    </div>
  </div>
);
