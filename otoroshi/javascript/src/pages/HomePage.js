import React, { Component } from 'react';
import { LiveStatTiles } from '../components/LiveStatTiles';
import { ClusterTiles } from '../components/ClusterTiles';

export class HomePage extends Component {
  render() {
    return (
      <div>
        {!this.props.usedNewEngine && (
          <div className="mt-3 pt-3">
            <div class="alert alert-warning m-0 d-flex align-items-center" role="alert">
              You are using the legacy Otoroshi engine. The new Otoroshi engine is now ready for
              production.
              <a
                href="https://maif.github.io/otoroshi/manual/next/engine.html"
                target="_blank"
                className="btn btn-sm btn-warning ms-auto">
                Documentation
              </a>
            </div>
          </div>
        )}
        <div
          style={{
            width: '100%',
            display: 'flex',
            justifyContent: 'center',
            alignItems: 'center',
          }}>
          {this.props.env && <img src={this.props.env.otoroshiLogo} className="logoOtoroshi" />}
        </div>
        <LiveStatTiles url="/bo/api/proxy/api/live/global?every=2000" />
        <ClusterTiles url="/bo/api/proxy/api/cluster/live?every=2000" env={this.props.env} />
      </div>
    );
  }
}
