import React, { Component } from 'react';
import moment from 'moment';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';

export class ClusterPage extends Component {
  columns = [
    {
      title: 'Worker name',
      content: item => item.name,
    },
    {
      title: 'Last seen at',
      content: item => moment(item.lastSeen).format("DD-MM-YYYY hh:mm:ss.SSS"),
    },
    {
      title: 'Timeout',
      style: { textAlign: 'center', width: 100 },
      notFilterable: true,
      content: item => item.timeout,
    },
    {
      title: 'Health',
      style: { display: 'flex', justifyContent: 'center', alignItems: 'center', width: 100 },
      content: item => item.timeout,
      notFilterable: true,
      cell: (a, item) => {
        const value = Date.now() - item.lastSeen;
        if (value < (item.timeout / 2)) {
          return <div style={{ width: 16, height: 16, backgroundColor: 'green', borderRadius: '50%' }}></div>
        } else if (value < (3 * (item.timeout / 3))) {
          return <div style={{ width: 16, height: 16, backgroundColor: 'orange', borderRadius: '50%' }}></div>
        } else {
          return <div style={{ width: 16, height: 16, backgroundColor: 'red', borderRadius: '50%' }}></div>
        }
      }
    }
  ];

  update = () => {
    if (this.table) {
      this.table.update();
    }
  }

  componentDidMount() {
    this.props.setTitle(`Cluster view`);
    this.interval = setInterval(this.update, 5000);
  }

  componentWillUnmount() {
    if (this.interval) {
      clearInterval(this.interval);
    }
  }

  render() {
    return (
      <Table
        parentProps={this.props}
        selfUrl="cluster"
        defaultTitle="Cluster view"
        defaultValue={() => ({})}
        itemName="worker"
        columns={this.columns}
        fetchItems={BackOfficeServices.fetchClusterMembers}
        showActions={false}
        showLink={false}
        injectTable={t => this.table = t}
        extractKey={item => item.name}
      />
    );
  }
}
