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

  loginToDashboard() {
    console.log('TODO !')
  }

  render() {
    if (!this.state.env) {
      return null;
    }
    return (
      <div>
        <iframe 
          id="provider-dashboard" 
          src={this.state.env.providerDashboardUrl} 
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
