import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { ServiceSidebar } from '../components/ServiceSidebar';
import { LiveStatTiles } from '../components/LiveStatTiles';
import DesignerSidebar from './RouteDesigner/Sidebar';

export class ServiceLiveStatsPage extends Component {
  state = {
    service: null,
  };

  onRoutes = window.location.pathname.indexOf('/bo/dashboard/routes') === 0;

  sidebarContent(name) {
    if (this.onRoutes) {
      return (
        <DesignerSidebar
          route={{ id: this.props.params.routeId, name }}
          setSidebarContent={this.props.setSidebarContent}
        />
      );
    }
    return (
      <ServiceSidebar
        env={this.state.service.env}
        serviceId={this.props.params.serviceId}
        name={name}
      />
    );
  }

  componentDidMount() {
    const fu = this.onRoutes
      ? BackOfficeServices.nextClient.fetch('routes', this.props.params.routeId)
      : BackOfficeServices.fetchService(this.props.params.lineId, this.props.params.serviceId);
    fu.then((service) => {
      this.onRoutes
        ? this.props.setTitle(this.props.title || `Route Live Stats`)
        : this.props.setTitle(`Service Live Stats`);
      this.setState({ service }, () => {
        this.props.setSidebarContent(this.sidebarContent(service.name));
      });
    });
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
