import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import moment from 'moment';

export class AuditPage extends Component {
  columns = [
    {
      title: 'Date',
      content: item => item['@timestamp'],
      cell: (v, item) => moment(item['@timestamp']).format('DD/MM/YYYY HH:mm:ss:SSS'),
    },
    { title: 'User', content: item => (item.user || {}).name || '--' },
    { title: 'From', content: item => item.from },
    { title: 'Action', content: item => item.action },
    { title: 'Message', content: item => item.message },
  ];

  componentDidMount() {
    this.props.setTitle(`Audit Log`);
  }

  fetchEvents = () => {
    return BackOfficeServices.fetchAuditEvents().then(d => d, err => console.error(err));
  };

  render() {
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="audit"
          defaultTitle="Audit Log"
          defaultValue={() => ({})}
          itemName="Audit Event"
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
