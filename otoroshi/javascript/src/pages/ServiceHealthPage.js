import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { ServiceSidebar } from '../components/ServiceSidebar';
import { Histogram, Line } from '../components/recharts';
import classNames from 'classnames';
import { Popover } from 'antd';
import moment from 'moment';

import 'antd/dist/antd.css';

export class ServiceHealthPage extends Component {
  state = {
    service: null,
    health: false,
    evts: [],
    status: [],
    responsesTime: []
  };

  colors = {
    RED: '#d50200',
    YELLOW: '#ff8900',
    GREEN: '#95cf3d',
    BLACK: '#000000',
  };

  updateEvts = (evts) => {
    this.setState({ evts });
    if (evts.length > 0) {
      const color = evts[0].health ? this.colors[evts[0].health] : 'grey';
      this.title = (
        <span>
          Service health is <i className="fas fa-heart" style={{ color }} />
        </span>
      );
      this.props.setTitle(this.title);
    } else {
      this.title = 'No HealthCheck available yet';
      this.props.setTitle(this.title);
    }
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
            ])
              .then(([evts, status, responsesTime]) => {
                this.updateEvts(evts);
                this.setState({ status, responsesTime })
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
    if (!this.state.service) return null;

    return (
      <div className="content-health">
        <Uptime health={this.state.status} />
        <OverallUptime health={this.state.status} />
        <ResponseTime responsesTime={this.state.responsesTime}/>
      </div>
    );
  }
}

class Uptime extends Component {
  render() {
    if (!this.props.health.length) {
      return null;
    }

    const test = this.props.health[0].dates
      .map(h => {
        const availability = h.status.length ? h.status
          .filter(s => s.health === "GREEN" || s.health === "YELLOW")
          .reduce((acc, curr) => acc + curr.percentage, 0) : `unknown`
        return { date: h.dateAsString, availability, status: h.status }
      })

    const avg = this.props.health[0].dates
      // .filter(d => d.status.length)
      .reduce((avg, value, _, { length }) => {
        return avg + value.status
          .filter(s => s.health === "GREEN" || s.health === "YELLOW")
          .reduce((acc, curr) => acc + curr.percentage, 0) / length
      }, 0)

    return (
      <div>
        <h3>Uptime last 90 days</h3>
        <div className="health-container">
          <div className="uptime-avg">{formatPercentage(avg)}</div>
          <div className="flex-status">
            {test.map((t, idx) => (
              <Popover
                placement="bottom"
                title={t.date}
                content={!t.status.length ?
                  <span>UNKNOWN</span> :
                  <ul>{t.status.map((s, i) => <li key={i}>{`${s.health}: ${s.percentage}%`}</li>)}</ul>}>
                <div key={idx} className={classNames('status', {
                  green: t.availability !== 'unknown' && t.availability === 100,
                  'light-green': t.availability !== 'unknown' && t.availability >= 99 && t.availability < 100,
                  orange: t.availability !== 'unknown' && t.availability >= 95 && t.availability < 99,
                  red: t.availability !== 'unknown' && t.availability < 95,
                  gray: t.availability === 'unknown'
                })}></div>
              </Popover>
            ))}
          </div>
        </div>
      </div>
    )
  }
}

class OverallUptime extends Component {
  render() {
    if (!this.props.health.length) {
      return null;
    }

    const dates = this.props.health[0].dates;

    const avg = dates => dates
      .reduce((avg, value, _, { length }) => {
        return avg + value.status
          .filter(s => s.health === "GREEN" || s.health === "YELLOW")
          .reduce((acc, curr) => acc + curr.percentage, 0) / length
      }, 0)

    const today = moment().startOf('day');
    const last7days = moment().subtract(7, 'days').startOf('day');
    const last30days = moment().subtract(30, 'days').startOf('day');


    const lastDayUptime = formatPercentage(avg(dates.filter(d => d.date > today.valueOf())))
    const last7daysUptime = formatPercentage(avg(dates.filter(d => d.date > last7days.valueOf())))
    const last30daysUptime = formatPercentage(avg(dates.filter(d => d.date > last30days.valueOf())))
    const last90daysUptime = formatPercentage(avg(dates))

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
    )
  }
}

class ResponseTime extends Component {
  render() {
    console.debug({ test: this.props.responsesTime.map(x => x ? parseInt(x.duration) : x)})
    return (
      <div>
        <h3>Response Time Last 90 days</h3>
        <div className="health-container uptime">
          <Histogram series={[{
            name: 'test',
            data: this.props.responsesTime
              .filter(d => d.duration !== null)
              .map(e => [e.timestamp, e.duration ? parseInt(e.duration) : e.duration])
          }]} hideXAxis={true} title="HealthChecks average responses duration (ms.)" unit="millis." />
        </div>
      </div>
    )
  }
}

const formatPercentage = (value, decimal = 2) => {
  return parseFloat(value).toFixed(decimal) + " %"
}
