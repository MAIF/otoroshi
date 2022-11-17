import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';

export class ProvidersDashboardPage extends Component {
  state = {};

  componentDidMount() {
    this.loginToDashboard();
    document.querySelector('.main').style.paddingLeft = '0px';
    document.querySelector('.main').style.paddingRight = '30px';
  }

  loginToDashboard = () => {
    const timestamp = Date.now();
    const user = this.props.env.user;
    const id = this.props.env.instanceId;
    const secret = this.props.env.providerDashboardSecret;
    this.sha256Hex(`${id}:${secret}:${timestamp}`).then((token) => {
      const url = `${this.props.env.providerDashboardUrl}?user=${user}&id=${id}&timestamp=${timestamp}&token=${token}`;
      this.setState({ url });
    });
  };

  sha256Hex = (message) => {
    const msgUint8 = new TextEncoder().encode(message); // encode as (utf-8) Uint8Array
    if (crypto.subtle) {
      return crypto.subtle.digest('SHA-256', msgUint8).then((hashBuffer) => {
        // hash the message
        const hashArray = Array.from(new Uint8Array(hashBuffer)); // convert buffer to byte array
        const hashHex = hashArray.map((b) => b.toString(16).padStart(2, '0')).join(''); // convert bytes to hex string
        return hashHex;
      });
    } else {
      return Promise.resolve();
    }
  };

  render() {
    if (!window.__user.superAdmin) {
      return null;
    }
    if (!this.props.env) {
      return null;
    }
    if (!this.state.url) {
      return null;
    }
    return (
      <div>
        <iframe
          id="provider-dashboard"
          src={this.state.url}
          style={{
            border: 'none',
            width: '100%',
            height: window.innerHeight - 55,
            marginTop: 15,
          }}></iframe>
      </div>
    );
  }
}
