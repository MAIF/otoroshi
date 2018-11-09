import React, { Component } from 'react';
import { LiveStatTiles } from '../components/LiveStatTiles';
import { ClusterTiles } from '../components/ClusterTiles';

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
        <ClusterTiles url="/bo/api/proxy/api/cluster/live?every=2000" />
      </div>
    );
  }
}
