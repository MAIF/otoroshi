import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { ServiceSidebar } from '../components/ServiceSidebar';
import { converterBase2 } from 'byte-converter';
import { Table, SimpleBooleanInput } from '../components/inputs';
import moment from 'moment';
import queryString from 'query-string';

import { OtoDatePicker } from '../components/datepicker';

function readableType(contentType) {
  if (contentType.indexOf('text/html') > -1) {
    return true;
  } else if (contentType.indexOf('application/json') > -1) {
    return true;
  } else if (contentType.indexOf('application/xml') > -1) {
    return true;
  } else if (contentType.indexOf('text/plain') > -1) {
    return true;
  } else {
    return false;
  }
}

function readContent(req) {
  if (req) {
    if (req.body.trim() === '') {
      return '';
    } else {
      const ctype = req.headers['Content-Type'] || req.headers['content-type'] || 'none';
      console.log(ctype);
      const isReadable = readableType(ctype);
      if (isReadable) {
        return decodeURIComponent(escape(window.atob(req.body)));
      } else {
        return req.body;
      }
    }
  } else {
    return '';
  }
}

export class ServiceEventsPage extends Component {
  state = {
    service: null,
    from: moment().subtract(1, 'hours'),
    to: moment(),
    limit: 500,
    asc: true,
  };

  columns = [
    {
      title: '@timestamp',
      content: (item) => item['@timestamp'],
      cell: (v, item) => moment(item['@timestamp']).format('DD/MM/YYYY HH:mm:ss:SSS'),
    },
    { title: '@product', content: (item) => item['@product'] },
    {
      title: 'Content',
      content: (item) => item['@timestamp'],
      notFilterable: true,
      style: { textAlign: 'center', width: 70 },
      cell: (v, item) => (
        <button
          type="button"
          className="btn btn-success btn-xs"
          onClick={(e) =>
            window.newAlert(
              <pre style={{ height: 300 }}>{JSON.stringify(item, null, 2)}</pre>,
              'Content'
            )
          }>
          content
        </button>
      ),
    },
    {
      title: 'Bodies',
      content: (item) => item['@timestamp'],
      notFilterable: true,
      style: { textAlign: 'center', width: 70 },
      cell: (v, item) => (
        <button
          type="button"
          className="btn btn-success btn-xs"
          onClick={(e) => {
            BackOfficeServices.fetchBodiesFor(item['@serviceId'], item.reqId).then((res) => {
              if (!res.error) {
                const bodyIn = readContent(res.request);
                const bodyOut = readContent(res.response);
                window.newAlert(
                  <>
                    {bodyIn.trim() !== '' && (
                      <>
                        <h3>Body in</h3>
                        <pre style={{ height: 150, width: '100%' }}>{bodyIn}</pre>
                      </>
                    )}
                    {bodyOut.trim() !== '' && (
                      <>
                        <h3>Body out</h3>
                        <pre style={{ height: 150, width: '100%' }}>{bodyOut}</pre>
                      </>
                    )}
                  </>,
                  'Bodies'
                );
              } else {
                window.newAlert('No body has been found for this request !', 'No body found');
              }
            });
          }}>
          bodies
        </button>
      ),
    },
    { title: 'protocol', content: (item) => item.protocol },
    { title: 'from', content: (item) => item.from },
    { title: 'duration', content: (item) => `${item.duration} ms.` },
    { title: 'overhead', content: (item) => `${item.overhead} ms.` },
    { title: 'status', content: (item) => item.status },
    { title: 'method', content: (item) => item.method },
    { title: 'Access By', content: (item) => (item.identity ? item.identity.identityType : '--') },
    {
      title: 'Accessed By',
      content: (item) =>
        item.identity ? item.identity.label + ' (' + item.identity.identity + ')' : '--',
    },
    { title: 'Data In', content: (item) => item.data.dataIn + ' bytes' },
    { title: 'Data Out', content: (item) => item.data.dataOut + ' bytes' },
    {
      title: 'uri',
      content: (item) => item.url,
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
    { title: '@id', content: (item) => item['@id'] },
    { title: '@service', content: (item) => item['@service'] },
    { title: '@serviceId', content: (item) => item['@serviceId'] },
    { title: 'reqId', content: (item) => item.reqId },
    {
      title: 'To',
      content: (item) => `${item.to.scheme}://${item.to.host}${item.to.uri}`,
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
      content: (item) => `${item.target.scheme}://${item.target.host}${item.target.uri}`,
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
      content: (item) => item.url,
      cell: (v, item) => (
        <a target="_blank" href={item.url}>
          {item.url}
        </a>
      ),
    },
    { title: 'Headers Count', content: (item) => item.headers.length },
    { title: 'Calls per sec', content: (item) => item.remainingQuotas.currentCallsPerSec },
    { title: 'Auth. calls per sec', content: (item) => item.remainingQuotas.authorizedCallsPerSec },
    { title: 'Rem. calls per sec', content: (item) => item.remainingQuotas.remainingCallsPerSec },
    { title: 'Calls per day', content: (item) => item.remainingQuotas.currentCallsPerDay },
    { title: 'Auth. calls per day', content: (item) => item.remainingQuotas.authorizedCallsPerDay },
    { title: 'Rem. calls per day', content: (item) => item.remainingQuotas.remainingCallsPerDay },
    { title: 'Calls per month', content: (item) => item.remainingQuotas.currentCallsPerMonth },
    {
      title: 'Auth. calls per month',
      content: (item) => item.remainingQuotas.authorizedCallsPerMonth,
    },
    {
      title: 'Rem. calls per month',
      content: (item) => item.remainingQuotas.remainingCallsPerMonth,
    },
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
      (service) => {
        this.props.setTitle(`Service Events`);
        this.setState({ service }, () => {
          this.props.setSidebarContent(this.sidebarContent(service.name));
        });
      }
    );
  }

  fetchEvents = () => {
    const query = queryString.parse(window.location.search);
    const limit = query.limit || this.state.limit;
    return BackOfficeServices.fetchServiceEvents(
      this.state.service.id,
      this.state.from,
      this.state.to,
      limit,
      this.state.asc ? 'asc' : 'desc'
    ).then(
      (d) => d,
      (err) => console.error(err)
    );
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
        <div className="mb-20">
          <div className="display--flex justify-content--between align-items--center flex-direction-xs--column">
            <OtoDatePicker
              updateDateRange={this.updateDateRange}
              from={this.state.from}
              to={this.state.to}
            />
            <div className="form grid grid-template-col__1fr-auto mt-xs-10" >
              <div className="input-group-addon">Limit</div>
              <input
                type="number"
                style={{ width: 100 }}
                value={this.state.limit}
                onChange={(e) =>
                  this.setState({ limit: e.target.value }, () => this.table.update())
                }
              />
            </div>
            <div className="input-group display--flex align-items--center mt-xs-10">
              <span style={{ marginRight: 5 }}>
                Order by timestamp ascending values
              </span>
              <SimpleBooleanInput
                value={this.state.asc}
                onChange={(e) => {
                  this.setState({ asc: !this.state.asc }, () => {
                    this.table.update();
                  });
                }}
              />
            </div>
          </div>
        </div>
        <Table
          parentProps={this.props}
          selfUrl={`lines/${this.props.params.lineId}/services/${this.props.params.serviceId}/events`}
          defaultTitle="Service Events"
          defaultValue={() => ({})}
          defaultSort={this.columns[0].title}
          defaultSortDesc={!this.state.asc}
          itemName="Events"
          formSchema={null}
          formFlow={null}
          columns={this.columns}
          fetchItems={this.fetchEvents}
          showActions={false}
          showLink={false}
          injectTable={(table) => (this.table = table)}
          extractKey={(item) => item['@id']}
        />
      </div>
    );
  }
}
