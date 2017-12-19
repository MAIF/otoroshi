import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { LiveStatTiles } from '../components/LiveStatTiles';

export class HomePage extends Component {
  render() {
    return (
      <div>
        <div
          style={{
            width: '100%',
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
          }}>
          <img src="/assets/images/otoroshi-logo-color.png" className="logoOtoroshi" />
        </div>
        <LiveStatTiles url="/bo/api/proxy/api/live/global?every=2000" />
      </div>
    );
  }
}
