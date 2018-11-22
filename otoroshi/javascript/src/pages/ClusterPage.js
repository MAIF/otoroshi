import React, { Component } from 'react';
import moment from 'moment';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';

export class ClusterPage extends Component {
  columns = [
    {
      title: 'Worker name',
      content: item => item.name.toLowerCase(),
    },
    {
      title: 'Location',
      content: item => item.location,
    },
    {
      title: 'Last seen at',
      style: { display: 'flex', justifyContent: 'center', alignItems: 'center', width: 190 },
      content: item => moment(item.lastSeen).format('DD-MM-YYYY HH:mm:ss.SSS'),
    },
    {
      title: 'Rate',
      style: { textAlign: 'center', width: 120 },
      content: item => (item.stats.rate || 0.0).toFixed(2) + ' call/s',
    },
    {
      title: 'Overhead',
      style: { textAlign: 'center', width: 80 },
      content: item => (item.stats.overhead || 0.0).toFixed(2) + ' ms',
    },
    {
      title: 'Duration',
      style: { textAlign: 'center', width: 80 },
      content: item => (item.stats.duration || 0.0).toFixed(2) + ' ms',
    },
    {
      title: 'Data in',
      style: { textAlign: 'center', width: 80 },
      content: item => {
        const kb = (item.stats.dataInRate || 0.0) / 1024;
        const mb = (item.stats.dataInRate || 0.0) / (1024 * 1024);
        if (mb < 1.0) {
          return kb.toFixed(2) + ' Kb/s';
        } else {
          return mb.toFixed(2) + ' Mb/s';
        }
      },
    },
    {
      title: 'Data out',
      style: { textAlign: 'center', width: 80 },
      content: item => {
        const kb = (item.stats.dataOutRate || 0.0) / 1024;
        const mb = (item.stats.dataOutRate || 0.0) / (1024 * 1024);
        if (mb < 1.0) {
          return kb.toFixed(2) + ' Kb/s';
        } else {
          return mb.toFixed(2) + ' Mb/s';
        }
      },
    },
    {
      title: 'Health',
      style: { display: 'flex', justifyContent: 'center', alignItems: 'center', width: 100 },
      content: item => item.timeout,
      notFilterable: true,
      cell: (a, item) => {
        const value = item.time - item.lastSeen;
        if (value < item.timeout / 2) {
          return <i className="fa fa-heartbeat" style={{ color: 'green' }} />;
        } else if (value < 3 * (item.timeout / 3)) {
          return <i className="fa fa-heartbeat" style={{ color: 'orange' }} />;
        } else {
          return <i className="fa fa-heartbeat" style={{ color: 'red' }} />;
        }
      },
    },
  ];

  update = () => {
    if (this.table) {
      this.table.update();
    }
  };

  componentDidMount() {
    this.props.setTitle(`Cluster view`);
    this.interval = setInterval(this.update, 5000);
    BackOfficeServices.env().then(env => {
      if (env.clusterRole === 'Off') {
        this.props.setTitle(`Cluster mode is not enabled`);
      } else {
        this.props.setTitle(`Cluster view (${env.clusterRole} cluster)`);
      }
    });
  }

  componentWillUnmount() {
    if (this.interval) {
      clearInterval(this.interval);
    }
  }

  clear = () => {
    BackOfficeServices.clearClusterMembers().then(() => {
      this.update();
    });
  };

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
        injectTable={t => (this.table = t)}
        extractKey={item => item.name}
        injectTopBar={() => (
          <button type="button" className="btn btn-danger" onClick={this.clear}>
            <i className="glyphicon glyphicon-trash" /> Clear members
          </button>
        )}
      />
    );
  }
}
