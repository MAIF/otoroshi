import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';

export class Top10servicesPage extends Component {
  columns = [
    { title: 'Name', content: item => item.name },
    {
      title: 'Rate',
      content: item => item.rate.toFixed(3) + ' calls per sec.',
    },
  ];

  componentDidMount() {
    this.mounted = true;
    this.props.setTitle(`Top 10 services`);
    setTimeout(this.update, 5000);
  }

  componentWillUnmount() {
    this.mounted = false;
  }

  update = () => {
    if (this.mounted) {
      this.table.update();
      setTimeout(this.update, 5000);
    }
  };

  render() {
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="top10"
          defaultTitle="Top 10 services"
          defaultValue={() => ({})}
          itemName="service"
          columns={this.columns}
          fetchItems={BackOfficeServices.fetchTop10}
          showActions={false}
          showLink={false}
          injectTable={table => (this.table = table)}
          extractKey={item => item.id}
        />
      </div>
    );
  }
}
