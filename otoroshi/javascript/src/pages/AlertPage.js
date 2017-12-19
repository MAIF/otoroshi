import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import moment from 'moment';

export class AlertPage extends Component {
  columns = [
    {
      title: 'Date',
      content: item => item['@timestamp'],
      cell: (v, item) => moment(item['@timestamp']).format('DD/MM/YYYY HH:mm:ss:SSS'),
    },
    { title: 'User', content: item => (item.user || {}).name || '--' },
    { title: 'From', content: item => (item.audit || {}).from || '--' },
    { title: 'Alert', content: item => item.alert },
    { title: 'Action', content: item => (item.audit || {}).action || '--' },
    { title: 'Message', content: item => (item.audit || {}).message || '--' },
  ];

  componentDidMount() {
    this.props.setTitle(`Alert Log`);
  }

  fetchEvents = () => {
    return BackOfficeServices.fetchAlertEvents().then(d => d, err => console.error(err));
  };

  render() {
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="alerts"
          defaultTitle="Alert Log"
          defaultValue={() => ({})}
          itemName="Alert Event"
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
