import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
//[REMOVE SERVICEDESC] import { ServiceSidebar } from '../components/ServiceSidebar';
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
    return null;
    //[REMOVE SERVICEDESC] return (
    //[REMOVE SERVICEDESC] <ServiceSidebar
    //[REMOVE SERVICEDESC] env={this.state.service.env}
    //[REMOVE SERVICEDESC] serviceId={this.props.params.serviceId}
    //[REMOVE SERVICEDESC] name={name}
    //[REMOVE SERVICEDESC] />
    //[REMOVE SERVICEDESC] );
  }

  componentDidMount() {
    const fu = this.onRoutes
      ? BackOfficeServices.nextClient.fetch('routes', this.props.params.routeId)
      : //[REMOVE SERVICEDESC] : BackOfficeServices.fetchService(this.props.params.lineId, this.props.params.serviceId);
        Promise.resolve({});
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

  componentWillUnmount() {
    if (this.props.setSidebarContent) this.props.setSidebarContent(null);
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
