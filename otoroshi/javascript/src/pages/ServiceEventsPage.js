import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { ServiceSidebar } from '../components/ServiceSidebar';
import { converterBase2 } from 'byte-converter';
import { Table, SimpleBooleanInput } from '../components/inputs';
import moment from 'moment';
import queryString from 'query-string';

import { OtoDatePicker } from '../components/datepicker';

import DesignerSidebar from './RouteDesigner/Sidebar';

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

function safe(obj, f) {
  if (obj) {
    return f(obj);
  } else {
    return '--';
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
      filterId: '@timestamp',
    },
    { title: '@product', content: (item) => item['@product'], filterId: '@product' },
    {
      title: 'Content',
      content: (item) => item['@timestamp'],
      notFilterable: true,
      style: { textAlign: 'center', width: 70 },
      cell: (v, item) => (
        <button
          type="button"
          className="btn btn-success btn-sm"
          onClick={(e) =>
            window.newAlert(
              <pre style={{ height: 300 }}>{JSON.stringify(item, null, 2)}</pre>,
              'Content'
            )
          }
        >
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
          className="btn btn-success btn-sm"
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
          }}
        >
          bodies
        </button>
      ),
    },
    { title: 'protocol', content: (item) => item.protocol, filterId: 'protocol' },
    { title: 'from', content: (item) => item.from, filterId: 'from' },
    { title: 'duration', content: (item) => `${item.duration} ms.`, filterId: 'duration' },
    { title: 'overhead', content: (item) => `${item.overhead} ms.`, filterId: 'overhead' },
    { title: 'status', content: (item) => item.status, filterId: 'status' },
    { title: 'method', content: (item) => item.method, filterId: 'method' },
    {
      title: 'Access By',
      content: (item) => safe(item.identity, (i) => i.identityType),
      filterable: false,
    }, // (item.identity ? item.identity.identityType : '--') },
    {
      title: 'Accessed By',
      filterable: false,
      content: (item) => safe(item.identity, (i) => i.label + ' (' + i.identity + ')'),
    },
    {
      title: 'Data In',
      content: (item) => safe(item.data, (i) => i.dataIn + ' bytes'),
      filterId: 'data.dataIn',
    }, // item.data.dataIn + ' bytes' },
    {
      title: 'Data Out',
      content: (item) => safe(item.data, (i) => i.dataOut + ' bytes'),
      filterId: 'data.dataOut',
    }, // item.data.dataOut + ' bytes' },
    {
      title: 'uri',
      filterId: 'url',
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
    { title: '@id', content: (item) => item['@id'], filterId: '@id' },
    { title: 'reqId', content: (item) => item.reqId, filterId: 'reqId' },
    {
      title: 'To',
      filterable: false,
      content: (item) => safe(item.to, (i) => `${i.scheme}://${i.host}${i.uri}`), // `${item.to.scheme}://${item.to.host}${item.to.uri}`,
      cell: (v, item) => {
        const url = safe(item.to, (i) => `${i.scheme}://${i.host}${i.uri}`);
        return (
          <a target="_blank" href={url}>
            {url}
          </a>
        );
      },
    },
    {
      title: 'Target',
      filterable: false,
      content: (item) => safe(item.target, (i) => `${i.scheme}://${i.host}${i.uri}`), // `${item.target.scheme}://${item.target.host}${item.target.uri}`,
      cell: (v, item) => {
        const url = safe(item.target, (i) => `${i.scheme}://${i.host}${i.uri}`);
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
    { title: 'Headers Count', content: (item) => item.headers.length, filterable: false },
    {
      title: 'Calls per sec',
      filterId: 'remainingQuotas.currentCallsPerSec',
      content: (item) => safe(item.remainingQuotas, (i) => i.currentCallsPerSec),
    },
    {
      title: 'Auth. calls per sec',
      filterId: 'remainingQuotas.authorizedCallsPerSec',
      content: (item) => safe(item.remainingQuotas, (i) => i.authorizedCallsPerSec),
    },
    {
      title: 'Rem. calls per sec',
      filterId: 'remainingQuotas.remainingCallsPerSec',
      content: (item) => safe(item.remainingQuotas, (i) => i.remainingCallsPerSec),
    },
    {
      title: 'Calls per day',
      filterId: 'remainingQuotas.currentCallsPerDay',
      content: (item) => safe(item.remainingQuotas, (i) => i.currentCallsPerDay),
    },
    {
      title: 'Auth. calls per day',
      filterId: 'remainingQuotas.authorizedCallsPerDay',
      content: (item) => safe(item.remainingQuotas, (i) => i.authorizedCallsPerDay),
    },
    {
      title: 'Rem. calls per day',
      filterId: 'remainingQuotas.remainingCallsPerDay',
      content: (item) => safe(item.remainingQuotas, (i) => i.remainingCallsPerDay),
    },
    {
      title: 'Calls per month',
      filterId: 'remainingQuotas.currentCallsPerMonth',
      content: (item) => safe(item.remainingQuotas, (i) => i.currentCallsPerMonth),
    },
    {
      title: 'Auth. calls per month',
      filterId: 'remainingQuotas.authorizedCallsPerMonth',
      content: (item) => safe(item.remainingQuotas, (i) => i.authorizedCallsPerMonth),
    },
    {
      title: 'Rem. calls per month',
      filterId: 'remainingQuotas.remainingCallsPerMonth',
      content: (item) => safe(item.remainingQuotas, (i) => i.remainingCallsPerMonth),
    },
  ];

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
        serviceId={this.props.params.serviceId || this.props.params.routeId}
        name={name}
      />
    );
  }

  componentWillUnmount() {
    if (this.props.setSidebarContent)
      this.props.setSidebarContent(null)
  }

  componentDidMount() {
    const fu = this.onRoutes
      ? BackOfficeServices.nextClient.fetch('routes', this.props.params.routeId)
      : BackOfficeServices.fetchService(this.props.params.lineId, this.props.params.serviceId);
    fu.then((service) => {
      this.onRoutes ? this.props.setTitle(this.props.title || `Route Events`) : this.props.setTitle(`Service Events`);
      this.setState({ service }, () => {
        this.props.setSidebarContent(this.sidebarContent(service.name));
      });
    });
  }

  fetchEvents = (paginationState) => {
    const query = queryString.parse(window.location.search);
    const limit = query.limit || this.state.limit;

    return BackOfficeServices.findAllEvents(
      paginationState,
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
      <div style={{ width: 'calc(100vw - 52px)', overflowX: 'hidden' }}>
        <div className="row" style={{ marginBottom: 30 }}>
          <div className="col-xs-12 col-4" style={{ display: 'flex', alignItems: 'center' }}>
            <OtoDatePicker
              updateDateRange={this.updateDateRange}
              from={this.state.from}
              to={this.state.to}
            />
          </div>
          <div className="input-group col-3 ms-3" style={{ width: 'auto' }}>
            <div className="input-group-text">Limit</div>
            <input
              type="number"
              style={{ width: 100 }}
              className="form-control"
              value={this.state.limit}
              onChange={(e) => this.setState({ limit: e.target.value }, () => this.table.update())}
            />
          </div>
          <div className="input-group col-4 ms-3" style={{ width: 'auto' }}>
            <span style={{ marginTop: 10, marginRight: 5 }}>
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
        <Table
          parentProps={this.props}
          selfUrl={
            this.onRoutes
              ? `routes/${this.props.params.routeId}/events`
              : `lines/${this.props.params.lineId}/services/${this.props.params.serviceId}/events`
          }
          defaultTitle="Service Events"
          defaultValue={() => ({})}
          fetchItems={this.fetchEvents}
          defaultSort={this.columns[0].title}
          defaultSortDesc={!this.state.asc}
          itemName="Events"
          formSchema={null}
          formFlow={null}
          columns={this.columns}
          showActions={false}
          showLink={false}
          injectTable={(table) => (this.table = table)}
          extractKey={(item) => item['@id']}
        />
      </div>
    );
  }
}
