import React, { Component } from 'react';
import PropTypes from 'prop-types';
import { Table, SelectInput } from '../components/inputs';
import { Link } from 'react-router-dom';
import { ServicePage } from './ServicePage';

import * as BackOfficeServices from '../services/BackOfficeServices';

export class ServicesPage extends Component {
  color(env) {
    if (env === 'prod') {
      return 'label-success';
    } else if (env === 'preprod') {
      return 'label-primary';
    } else if (env === 'experiments') {
      return 'label-warning';
    } else if (env === 'dev') {
      return 'label-info';
    } else {
      return 'label-default';
    }
  }

  columns = [
    {
      title: 'Name',
      content: item => item.name,
    },
    {
      title: 'Env.',
      style: { textAlign: 'center', width: 120 },
      content: item => item.env,
      cell: (v, item) => <span className={`label ${this.color(item.env)}`}>{item.env}</span>,
    },
    {
      title: 'Active',
      style: { textAlign: 'center', width: 70 },
      noMobile: true,
      notFilterable: true,
      content: item => item.enabled,
      cell: (v, item) => (item.enabled ? <span className="glyphicon glyphicon-ok-sign" /> : ''),
    },
    {
      title: 'Private',
      style: { textAlign: 'center', width: 55 },
      noMobile: true,
      content: item => item.privateApp,
      notFilterable: true,
      cell: (v, item) => (item.privateApp ? <img src="/assets/images/logoMaif.png" /> : ''),
    },
    {
      title: '*Public',
      style: { textAlign: 'center', width: 55 },
      notFilterable: true,
      noMobile: true,
      content: item =>
        !item.privateApp &&
        item.privatePatterns.length === 0 &&
        item.publicPatterns.indexOf('/.*') > -1,
      cell: (v, item) =>
        !item.privateApp &&
        item.privatePatterns.length === 0 &&
        item.publicPatterns.indexOf('/.*') > -1 ? (
          <i className="glyphicon glyphicon-remove-sign" />
        ) : (
          <i className="fa fa-globe fa-lg" aria-hidden="true" />
        ),
    },
    {
      title: 'Sec. Ex.',
      style: { textAlign: 'center', width: 70 },
      notFilterable: true,
      noMobile: true,
      content: item => item.enforceSecureCommunication,
      cell: (v, item) =>
        item.enforceSecureCommunication ? (
          <i className="fa fa-lock fa-lg" />
        ) : (
          <i className="fa fa-unlock-alt fa-lg" />
        ),
    },
    {
      title: 'HTTPS',
      style: { textAlign: 'center', width: 50 },
      notFilterable: true,
      noMobile: true,
      content: item =>
        item.targets.map(i => i.scheme).filter(i => i.toLowerCase() === 'https').length ===
        item.targets.length,
      cell: (v, item) =>
        item.targets.map(i => i.scheme).filter(i => i.toLowerCase() === 'https').length ===
        item.targets.length ? (
          <i className="fa fa-lock fa-lg" />
        ) : (
          <i className="fa fa-unlock-alt fa-lg" />
        ),
    },
  ];

  constructor(p) {
    super(p);
    if (window.__env === 'dev') {
      this.columns.push({
        title: 'Local',
        style: { textAlign: 'center', width: 55 },
        noMobile: true,
        notFilterable: true,
        cell: (v, item) =>
          item.redirectToLocal ? <span className="glyphicon glyphicon-ok-sign" /> : '',
      });
    }
  }

  componentDidMount() {
    const env = this.props.location.query.env;
    const group = this.props.location.query.group;
    if (env && group) {
      this.title = `All services for '${env}' and group '${this.props.location.query.group}'`;
    } else if (env) {
      this.title = `All services for '${env}'`;
    } else if (group) {
      this.title = `All services for '${this.props.location.query.group}'`;
    } else {
      this.title = `All services`;
    }
    this.props.setTitle(this.title);
  }

  nothing() {
    return null;
  }

  addService = e => {
    if (e && e.preventDefault) e.preventDefault();
    BackOfficeServices.createNewService().then(r => {
      ServicePage.__willCreateService = r;
      this.props.history.push({
        pathname: `/lines/${r.env}/services/${r.id}`,
      });
    });
  };

  fetchServices = () => {
    // console.log('fetchServices', this.props);
    return BackOfficeServices.allServices(
      this.props.location.query.env,
      this.props.location.query.group
    );
  };

  gotoService = service => {
    this.props.history.push({
      pathname: `/lines/${service.env}/services/${service.id}`,
    });
  };

  render() {
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="services"
          defaultTitle={this.title}
          defaultValue={() => ({})}
          itemName="Services"
          columns={this.columns}
          fetchItems={this.fetchServices}
          updateItem={this.nothing}
          deleteItem={this.nothing}
          createItem={this.nothing}
          showActions={false}
          showLink={false}
          rowNavigation={true}
          navigateTo={this.gotoService}
          firstSort={0}
          extractKey={item => item.id}
          itemUrl={i => `/bo/dashboard/lines/${i.env}/services/${i.id}`}
        />
      </div>
    );
  }
}
