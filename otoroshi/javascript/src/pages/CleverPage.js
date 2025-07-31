import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import { Link } from 'react-router-dom';
import { ServicePage } from './ServicePage';

export class CleverPage extends Component {
  columns = [
    {
      title: 'Clever App',
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Otoroshi',
      style: { textAlign: 'center', width: 100 },
      content: (item) => (item.exists ? item.otoUrl : null),
      notFilterable: true,
      cell: (value) => (value ? <Link to={value}>View service</Link> : ''),
    },
    {
      title: 'Clever Cloud',
      style: { textAlign: 'center', width: 100 },
      notFilterable: true,
      content: (item) => (item.exists ? item.console : ''),
      cell: (value) =>
        value ? (
          <a href={value} target="_blank">
            View app.
          </a>
        ) : (
          ''
        ),
    },
    {
      title: 'Exists',
      style: { textAlign: 'center', width: 60 },
      content: (item) => (item.exists ? 'exists' : 'not-exists'),
      notFilterable: true,
      cell: (item) =>
        item === 'exists' ? (
          <span className="fas fa-check-circle" />
        ) : (
          <span style={{ color: 'var(--color-red)' }} className="fas fa-exclamation-circle" />
        ),
    },
    {
      title: 'Action',
      style: { textAlign: 'center', width: 150 },
      content: (item) => item,
      notSortable: true,
      notFilterable: true,
      cell: (item) =>
        item.exists ? (
          ''
        ) : (
          <button
            onClick={(e) => this.createService(e, item)}
            type="button"
            className="btn btn-sm btn-success"
          >
            <i className="fas fa-plus-circle" /> Create service
          </button>
        ),
    },
  ];

  componentDidMount() {
    this.props.setTitle(`Clever Cloud Apps`);
  }

  createService = (e, slug) => {
    BackOfficeServices.createNewService().then((service) => {
      const newService = { ...service };
      newService.name = slug.name;
      newService.targets[0].host = slug.host;
      newService.targets[0].scheme = 'https';
      ServicePage.__willCreateService = newService;
      this.props.history.push({
        pathname: `/lines/${service.env}/services/${service.id}`,
      });
    });
  };

  render() {
    if (!window.__user.superAdmin) {
      return null;
    }
    return (
      <Table
        parentProps={this.props}
        selfUrl="clever"
        defaultTitle="Clever Cloud Apps"
        defaultValue={() => ({})}
        itemName="user"
        columns={this.columns}
        fetchItems={BackOfficeServices.findAllApps}
        showActions={false}
        showLink={false}
        extractKey={(item) => item.id}
      />
    );
  }
}
