import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import { Link } from 'react-router-dom';
import { ServicePage } from './ServicePage';

export class SnowMonkeyPage extends Component {

  state = {
    config: null,
    started: false
  };

  componentDidMount() {
    this.props.setTitle(`SnowMonkey`);
    this.updateConfig();
  }

  updateConfig = () => {
    BackOfficeServices.fetchSnowMonkeyConfig().then(config => this.setState({ config, started: config.enabled }))
  };

  toggle = (e) => {
    if (this.state.started) {
      BackOfficeServices.stopSnowMonkey().then(() => this.updateConfig());
    } else {
      BackOfficeServices.startSnowMonkey().then(() => this.updateConfig());
    }
  };

  render() {
    return (
      <div>
        <button type="button" className={`btn btn-${this.state.started ? 'danger' : 'success'}`} onClick={this.toggle} >{this.state.started ? 'Stop' : 'Start'}</button>
      </div>
    );
  }
}
