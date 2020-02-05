import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';

export class ProvidersDashboardPage extends Component {

  state = {}
  
  componentDidMount() {
    BackOfficeServices.env().then(env => {
      this.setState({ env }, () => {
        this.loginToDashboard();
        document.querySelector('.main').style.paddingLeft = "0px";
        document.querySelector('.main').style.paddingRight = "30px";
      });
    });
  }

  loginToDashboard = () => {
    const timestamp = Date.now();
    const user = this.state.env.user;
    const id = this.state.env.instanceId;
    const secret = this.state.env.providerDashboardSecret;
    this.sha256Hex(`${id}:${secret}:${timestamp}`).then(token => {
      const url = `${this.state.env.providerDashboardUrl}?user=${user}&id=${id}&timestamp=${timestamp}&token=${token}`;
      console.log(token, url);
      this.setState({ url });
    });    
  }

  sha256Hex = (message) => {
    const msgUint8 = new TextEncoder().encode(message);                             // encode as (utf-8) Uint8Array
    return crypto.subtle.digest('SHA-256', msgUint8).then(hashBuffer => {           // hash the message
      const hashArray = Array.from(new Uint8Array(hashBuffer));                     // convert buffer to byte array
      const hashHex = hashArray.map(b => b.toString(16).padStart(2, '0')).join(''); // convert bytes to hex string
      return hashHex;
    });           
  }
  
  render() {
    if (!this.state.env) {
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
            marginTop: 15 
          }}></iframe>     
      </div>
    );
  }
}
