import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { ServiceSidebar } from '../components/ServiceSidebar';

export class DocumentationPage extends Component {
  state = {
    service: null,
  };

  sidebarContent(name) {
    return (
      <ServiceSidebar
        env={this.state.service.env}
        serviceId={this.props.params.serviceId}
        name={name}
      />
    );
  }

  componentDidMount() {
    BackOfficeServices.fetchService(this.props.params.lineId, this.props.params.serviceId).then(
      (service) => {
        this.props.setTitle(`Service documentation`);
        this.setState({ service }, () => {
          this.props.setSidebarContent(this.sidebarContent(service.name));
        });
      }
    );
  }

  render() {
    if (this.state.service === null) {
      return null;
    }
    return (
      <div style={{ width: '100%', display: 'flex', justifyContent: 'center' }}>
        <iframe
          src={`/bo/api/lines/${this.state.service.env}/${this.state.service.id}/docframe`}
          style={{ width: '100%', border: 'none', height: '100vh' }}
        />
      </div>
    );
  }
}
