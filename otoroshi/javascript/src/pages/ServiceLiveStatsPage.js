import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { ServiceSidebar } from '../components/ServiceSidebar';
import { LiveStatTiles } from '../components/LiveStatTiles';

export class ServiceLiveStatsPage extends Component {
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
        this.props.setTitle(`Service Live Stats`);
        this.setState({ service }, () => {
          this.props.setSidebarContent(this.sidebarContent(service.name));
        });
      }
    );
    setTimeout(() => window.location.reload(), 120000);
  }

  render() {
    if (!this.state.service) return null;
    return (
      <div>
        <LiveStatTiles url={`/bo/api/proxy/api/live/${this.state.service.id}?every=2000`} />
      </div>
    );
  }
}
