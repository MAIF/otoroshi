import React, { Component } from 'react';
import moment from 'moment';
import { ServiceSidebar } from '../components/ServiceSidebar';
import { Histogram } from '../components/recharts';
import { BooleanInput } from '../components/inputs';
import { Uptime, formatPercentage } from '../components/Status';
import * as BackOfficeServices from '../services/BackOfficeServices';

import 'antd/dist/antd.css';

export class ServiceHealthPage extends Component {
  state = {
    service: null,
    health: false,
    status: [],
    responsesTime: [],
    stopTheCountUnknownStatus: true,
  };

  colors = {
    RED: '#d50200',
    YELLOW: '#ff8900',
    GREEN: '#95cf3d',
    BLACK: '#000000',
  };

  componentDidMount() {
    BackOfficeServices.fetchService(this.props.params.lineId, this.props.params.serviceId).then(
      (service) => {
        this.setState({ service }, () => {
          if (service.healthCheck.enabled) {
            this.setState({ health: true });

            Promise.all([
              BackOfficeServices.fetchHealthCheckEvents(service.id),
              BackOfficeServices.fetchServiceStatus(service.id),
              BackOfficeServices.fetchServiceResponseTime(service.id),
            ]).then(([evts, status, responsesTime]) => {
              this.setState({ status, responsesTime }, () => {
                const color = evts[0].health ? this.colors[evts[0].health] : 'grey';
                this.title = (
                  <span>
                    Service health is <i className="fas fa-heart" style={{ color }} />
                  </span>
                );
                this.props.setTitle(this.title);
              });
            });
          } else {
            this.title = 'No HealthCheck available yet';
            this.props.setTitle(this.title);
          }
          this.props.setSidebarContent(this.sidebarContent(service.name));
        });
      }
    );
  }

  sidebarContent(name) {
    return (
      <ServiceSidebar
        env={this.state.service.env}
        serviceId={this.props.params.serviceId}
        name={name}
      />
    );
  }

  onUpdate = (evts) => {
    this.updateEvts(evts);
  };

  render() {
    if (!this.state.service || !this.state.status.length) return null;

    return (
      <div className="content-health">
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
