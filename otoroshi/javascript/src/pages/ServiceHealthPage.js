import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { ServiceSidebar } from '../components/ServiceSidebar';
import { RoundChart, Histogram } from '../components/recharts';
import { Table } from '../components/inputs';
import moment from 'moment';

export class ServiceHealthPage extends Component {
  state = {
    service: null,
    health: false,
    evts: [],
  };

  colors = {
    RED: '#d50200',
    YELLOW: '#ff8900',
    GREEN: '#95cf3d',
  };

  columns = [
    {
      title: 'Health',
      style: { textAlign: 'center' },
      content: item => item.health,
      cell: (v, item) => {
        const color = item.health ? this.colors[item.health] : 'grey';
        return <span className="glyphicon glyphicon-heart" style={{ color, fontSize: 16 }} />;
      },
    },
    {
      title: 'Date',
      content: item => item['@timestamp'],
      cell: (v, item) => moment(item['@timestamp']).format('DD/MM/YYYY HH:mm:ss:SSS'),
    },
    { title: 'Duration', content: item => `${item.duration} ms.`, style: { textAlign: 'right' } },
    { title: 'Status', content: item => item.status, style: { textAlign: 'center' } },
    {
      title: 'Logic Check',
      content: item => (item.logicCheck ? 'OK' : 'KO'),
      style: { textAlign: 'center' },
    },
    { title: 'Error', content: item => (item.error ? item.error : '') },
  ];

  componentDidMount() {
    BackOfficeServices.fetchService(this.props.params.lineId, this.props.params.serviceId).then(
      service => {
        this.setState({ service }, () => {
          if (service.healthCheck.enabled) {
            this.setState({ health: true });
            BackOfficeServices.fetchHealthCheckEvents(service.id).then(evts => {
              this.setState({ evts });
              if (evts.length > 0) {
                const color = evts[0].health ? this.colors[evts[0].health] : 'grey';
                this.title = (
                  <span>
                    Service health is <i className="glyphicon glyphicon-heart" style={{ color }} />
                  </span>
                );
                this.props.setTitle(this.title);
              } else {
                this.title = 'No HealthCheck available yet';
                this.props.setTitle(this.title);
              }
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

  fetchHealthCheckEvents = () => {
    return BackOfficeServices.fetchHealthCheckEvents(this.state.service.id);
  };

  render() {
    if (!this.state.service) return null;
    const evts = this.state.evts;
    const series = [
      {
        name: '2xx',
        data: evts
          .filter(e => e.status < 300)
          .map(e => [e['@timestamp'], e.duration])
          .reverse(),
      },
      {
        name: '3xx',
        data: evts
          .filter(e => e.status > 299 && e.status < 400)
          .map(e => [e['@timestamp'], e.duration])
          .reverse(),
      },
      {
        name: '4xx',
        data: evts
          .filter(e => e.status > 399 && e.status < 500)
          .map(e => [e['@timestamp'], e.duration])
          .reverse(),
      },
      {
        name: '5xx',
        data: evts
          .filter(e => e.status > 499 && e.status < 600)
          .map(e => [e['@timestamp'], e.duration])
          .reverse(),
      },
    ];
    return (
      <div className="contentHealth">
        <Histogram series={series} title="HealthChecks responses duration (ms.)" unit="millis." />
        {this.state.health && (
          <Table
            parentProps={this.props}
            selfUrl={`lines/${this.props.params.lineId}/services/${
              this.props.params.serviceId
            }/health`}
            defaultTitle={this.title}
            defaultValue={() => ({})}
            itemName="Health Check"
            formSchema={null}
            formFlow={null}
            columns={this.columns}
            fetchItems={this.fetchHealthCheckEvents}
            showActions={false}
            showLink={false}
            extractKey={item => item['@id']}
          />
        )}
      </div>
    );
  }
}
