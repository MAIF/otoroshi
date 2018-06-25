import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { ServiceSidebar } from '../components/ServiceSidebar';
import { converterBase2 } from 'byte-converter';
import { Table } from '../components/inputs';
import moment from 'moment';

import { OtoDatePicker } from '../components/datepicker';

export class ServiceEventsPage extends Component {
  state = {
    service: null,
    from: moment().subtract(1, 'hours'),
    to: moment(),
  };

  columns = [
    {
      title: '@timestamp',
      content: item => item['@timestamp'],
      cell: (v, item) => moment(item['@timestamp']).format('DD/MM/YYYY HH:mm:ss:SSS'),
    },
    { title: '@product', content: item => item['@product'] },
    { title: 'protocol', content: item => item.protocol },
    { title: 'from', content: item => item.from },
    { title: 'duration', content: item => `${item.duration} ms.` },
    { title: 'overhead', content: item => `${item.overhead} ms.` },
    { title: 'status', content: item => item.status },
    { title: 'method', content: item => item.method },
    { title: 'Access By', content: item => (item.identity ? item.identity.identityType : '--') },
    {
      title: 'Accessed By',
      content: item =>
        item.identity ? item.identity.label + ' (' + item.identity.identity + ')' : '--',
    },
    { title: 'Data In', content: item => item.data.dataIn + ' bytes' },
    { title: 'Data Out', content: item => item.data.dataOut + ' bytes' },
    {
      title: 'uri',
      content: item => item.url,
      cell: (v, item) => {
        const url = item.url;
        const parts = url.split('/');
        parts.shift(); // Yeah !!!!!
        parts.shift(); // Yeah !!!!!
        parts.shift(); // Yeah !!!!!
        return (
          <a target="_blank" href={item.url}>
            /{parts.join('/')}
          </a>
        );
      },
    },
    { title: '@id', content: item => item['@id'] },
    { title: '@service', content: item => item['@service'] },
    { title: '@serviceId', content: item => item['@serviceId'] },
    { title: 'reqId', content: item => item.reqId },
    {
      title: 'To',
      content: item => `${item.to.scheme}://${item.to.host}${item.to.uri}`,
      cell: (v, item) => {
        const url = `${item.to.scheme}://${item.to.host}${item.to.uri}`;
        return (
          <a target="_blank" href={url}>
            {url}
          </a>
        );
      },
    },
    {
      title: 'Target',
      content: item => `${item.target.scheme}://${item.target.host}${item.target.uri}`,
      cell: (v, item) => {
        const url = `${item.target.scheme}://${item.target.host}${item.target.uri}`;
        return (
          <a target="_blank" href={url}>
            {url}
          </a>
        );
      },
    },
    {
      title: 'url',
      content: item => item.url,
      cell: (v, item) => (
        <a target="_blank" href={item.url}>
          {item.url}
        </a>
      ),
    },
    { title: 'Headers Count', content: item => item.headers.length },
    { title: 'Calls per sec', content: item => item.remainingQuotas.currentCallsPerSec },
    { title: 'Auth. calls per sec', content: item => item.remainingQuotas.authorizedCallsPerSec },
    { title: 'Rem. calls per sec', content: item => item.remainingQuotas.remainingCallsPerSec },
    { title: 'Calls per day', content: item => item.remainingQuotas.currentCallsPerDay },
    { title: 'Auth. calls per day', content: item => item.remainingQuotas.authorizedCallsPerDay },
    { title: 'Rem. calls per day', content: item => item.remainingQuotas.remainingCallsPerDay },
    { title: 'Calls per month', content: item => item.remainingQuotas.currentCallsPerMonth },
    {
      title: 'Auth. calls per month',
      content: item => item.remainingQuotas.authorizedCallsPerMonth,
    },
    { title: 'Rem. calls per month', content: item => item.remainingQuotas.remainingCallsPerMonth },
  ];

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
      service => {
        this.props.setTitle(`Service Events`);
        this.setState({ service }, () => {
          this.props.setSidebarContent(this.sidebarContent(service.name));
        });
      }
    );
  }

  fetchEvents = () => {
    return BackOfficeServices.fetchServiceEvents(
      this.state.service.id,
      this.state.from,
      this.state.to
    ).then(d => d, err => console.error(err));
  };

  updateDateRange = (from, to) => {
    this.setState({ from, to }, () => {
      this.table.update();
    });
  };

  render() {
    if (!this.state.service) return null;
    return (
      <div>
        <div className="row" style={{ marginBottom: 30 }}>
          <div className="">
            <OtoDatePicker
              updateDateRange={this.updateDateRange}
              from={this.state.from}
              to={this.state.to}
            />
          </div>
        </div>
        <Table
          parentProps={this.props}
          selfUrl={`lines/${this.props.params.lineId}/services/${
            this.props.params.serviceId
          }/events`}
          defaultTitle="Service Events"
          defaultValue={() => ({})}
          defaultSort={this.columns[0].title}
          defaultSortDesc={true}
          itemName="Events"
          formSchema={null}
          formFlow={null}
          columns={this.columns}
          fetchItems={this.fetchEvents}
          showActions={false}
          showLink={false}
          injectTable={table => (this.table = table)}
          extractKey={item => item['@id']}
        />
      </div>
    );
  }
}
