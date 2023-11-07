import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import moment from 'moment';

export class AuditPage extends Component {
  columns = [
    {
      title: 'Date',
      filterId: '@timestamp',
      content: (item) => item['@timestamp'],
      cell: (v, item) => moment(item['@timestamp']).format('DD/MM/YYYY HH:mm:ss:SSS'),
    },
    { title: 'User', filterId: 'user.name', content: (item) => (item.user || {}).name || '--' },
    { title: 'From', filterId: 'from', content: (item) => item.from },
    { title: 'Action', filterId: 'action', content: (item) => item.action },
    { title: 'Message', filterId: 'message', content: (item) => item.message },
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
          }>
          content
        </button>
      ),
    },
  ];

  componentDidMount() {
    this.props.setTitle(`Audit log`);
  }

  fetchEvents = () => {
    return BackOfficeServices.fetchAuditEvents().then(
      (d) => d,
      (err) => console.error(err)
    );
  };

  render() {
    if (!window.__user.superAdmin) {
      return null;
    }
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="audit"
          defaultTitle="Audit Log"
          defaultValue={() => ({})}
          defaultSort={this.columns[0].title}
          defaultSortDesc={true}
          itemName="Audit Event"
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
