import React, { Component } from 'react';
import moment from 'moment';
//[REMOVE SERVICEDESC] import { ServiceSidebar } from '../components/ServiceSidebar';
import { Histogram } from '../components/recharts';
import { BooleanInput } from '../components/inputs';
import { Uptime, formatPercentage } from '../components/Status';
import * as BackOfficeServices from '../services/BackOfficeServices';
import DesignerSidebar from './RouteDesigner/Sidebar';

import { Link } from 'react-router-dom';
import Loader from '../components/Loader';
import { EventsSetupHint } from '../components/EventsSetupHint';

export class ServiceHealthPage extends Component {
  state = {
    service: null,
    health: false,
    status: [],
    loading: true,
    responsesTime: [],
    stopTheCountUnknownStatus: true,
  };

  onRoutes = window.location.pathname.indexOf('/bo/dashboard/routes') === 0;

  componentWillUnmount() {
    if (this.props.setSidebarContent) this.props.setSidebarContent(null);
  }

  componentDidMount() {
    const fu = this.onRoutes
      ? BackOfficeServices.nextClient.fetch('routes', this.props.params.routeId)
      : //[REMOVE SERVICEDESC] : BackOfficeServices.fetchService(this.props.params.lineId, this.props.params.serviceId);
        Promise.resolve({});

    this.props.setTitle('Route health')
      
    fu.then((service) => {
      this.setState({ service }, () => {
        if (
          (this.onRoutes && service.backend.health_check && service.backend.health_check.enabled) ||
          (service.healthCheck && service.healthCheck.enabled)
        ) {
          this.setState({ health: true });

          Promise.all([
            BackOfficeServices.fetchServiceStatus(service.id),
            BackOfficeServices.fetchServiceResponseTime(service.id),
          ]).then(([status, responsesTime]) => {
            this.setState({ status, responsesTime, loading: false });
          });
        } else {
          this.setState({ loading: false });
        }
        this.props.setSidebarContent(this.sidebarContent(service.name));
      });
    });
  }

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

  render() {
    return (
      <Loader loading={this.state.loading}>
        {!this.state.service || !this.state.status.length ? (
          !this.state.health ? (
            <>
              <p>
                The health check is disabled on this {this.onRoutes ? 'route' : 'service'}. Otoroshi
                only collects availability data for targets it actively probes, so there is nothing
                to display yet.
              </p>
              <p>
                Enable it in the <strong>Health check</strong> section of{' '}
                {this.onRoutes ? (
                  <Link to={`/routes/${this.props.params.routeId}?tab=flow`}>the route configuration</Link>
                ) : (
                  <Link
                    to={`/lines/${this.props.params.lineId}/services/${this.props.params.serviceId}`}
                  >
                    the service configuration
                  </Link>
                )}
                , then come back here.
              </p>
            </>
          ) : (
            <EventsSetupHint intro="The health check is enabled, but no data has been collected yet. Displaying it requires three things:" />
          )
        ) : (
          <div className="content-health" style={{ maxWidth: '100%' }}>
            <div>
              <h3>Uptime last 90 days</h3>
              <Uptime
                health={this.state.status[0]}
                stopTheCountUnknownStatus={this.state.stopTheCountUnknownStatus}
              />
            </div>
            <OverallUptime
              health={this.state.status}
              stopTheCountUnknownStatus={this.state.stopTheCountUnknownStatus}
            />
            <ResponseTime
              responsesTime={this.state.responsesTime}
              stopTheCountUnknownStatus={this.state.stopTheCountUnknownStatus}
            />
            <BooleanInput
              label="Don't use unknown status when calculating averages"
              value={this.state.stopTheCountUnknownStatus}
              help="Use unknown statuses when calculating averages could modify results and may not be representative"
              onChange={(stopTheCountUnknownStatus) => this.setState({ stopTheCountUnknownStatus })}
            />
          </div>
        )}
      </Loader>
    );
  }
}

class OverallUptime extends Component {
  render() {
    if (!this.props.health.length) {
      return null;
    }

    const dates = this.props.health[0].dates;

    const avg = (dates) =>
      dates
        .filter((d) => !this.props.stopTheCountUnknownStatus || d.status.length)
        .reduce((avg, value, _, { length }) => {
          return (
            avg +
            value.status
              .filter((s) => s.health === 'GREEN' || s.health === 'YELLOW')
              .reduce((acc, curr) => acc + curr.percentage, 0) /
            length
          );
        }, 0);

    const today = moment().startOf('day');
    const last7days = moment().subtract(7, 'days').startOf('day');
    const last30days = moment().subtract(30, 'days').startOf('day');

    const lastDayUptime = formatPercentage(avg(dates.filter((d) => d.date > today.valueOf())));
    const last7daysUptime = formatPercentage(
      avg(dates.filter((d) => d.date > last7days.valueOf()))
    );
    const last30daysUptime = formatPercentage(
      avg(dates.filter((d) => d.date > last30days.valueOf()))
    );
    const last90daysUptime = formatPercentage(avg(dates));

    return (
      <div>
        <h3>Overall Uptime</h3>
        <div className="health-container uptime">
          <div className="uptime">
            <div className="uptime-value">{lastDayUptime}</div>
            <div className="uptime-label">Last 24 hours</div>
          </div>
          <div className="uptime">
            <div className="uptime-value">{last7daysUptime}</div>
            <div className="uptime-label">Last 7 days</div>
          </div>
          <div className="uptime">
            <div className="uptime-value">{last30daysUptime}</div>
            <div className="uptime-label">Last 30 days</div>
          </div>
          <div className="uptime">
            <div className="uptime-value">{last90daysUptime}</div>
            <div className="uptime-label">Last 90 days</div>
          </div>
        </div>
      </div>
    );
  }
}

class ResponseTime extends Component {
  render() {
    return (
      <div>
        <h3>Response Time Last 90 days</h3>
        <div className="health-container uptime">
          <Histogram
            series={[
              {
                name: 'test',
                data: this.props.responsesTime
                  .filter((d) => !this.props.stopTheCountUnknownStatus || d.duration !== null)
                  .map((e) => [e.timestamp, e.duration ? parseInt(e.duration) : e.duration]),
              },
            ]}
            hideXAxis={true}
            title="HealthChecks average responses duration (ms.)"
            unit="millis."
          />
        </div>
      </div>
    );
  }
}
