import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import moment from 'moment';
import _ from 'lodash';

export class PrivateAppsSessionsPage extends Component {
  columns = [
    { title: 'Name', content: item => item.name },
    {
      title: 'Email',
      content: item => item.email,
    },
    {
      title: 'Created At',
      content: item => (item.createdAt ? item.createdAt : 0),
      cell: (v, item) =>
        item.createdAt ? moment(item.createdAt).format('DD/MM/YYYY HH:mm:ss') : '',
    },
    {
      title: 'Expires At',
      content: item => (item.expiredAt ? item.expiredAt : 0),
      cell: (v, item) =>
        item.expiredAt ? moment(item.expiredAt).format('DD/MM/YYYY HH:mm:ss') : '',
    },
    {
      title: 'Profile',
      content: item => 0,
      cell: (v, item) => JSON.stringify(item.profile),
    },
    {
      title: 'Action',
      style: { textAlign: 'center', width: 150 },
      notSortable: true,
      notFilterable: true,
      content: item => item,
      cell: (v, item, table) => {
        return (
          <button
            key={item.randomId}
            type="button"
            className="btn btn-danger btn-xs"
            onClick={e => this.discardSession(e, item.randomId, table)}>
            <i className="glyphicon glyphicon-fire" /> Discard Session
          </button>
        );
      },
    },
  ];

  componentDidMount() {
    this.props.setTitle(`Private apps users sessions`);
  }

  discardSession = (e, id, table) => {
    if (e && e.preventDefault) e.preventDefault();
    if (confirm(`Are you sure that you want to discard private apps session for ${id} ?`)) {
      BackOfficeServices.discardPrivateAppsSession(id).then(() => {
        setTimeout(() => {
          table.update();
        }, 1000);
      });
    }
  };

  discardSessions = e => {
    if (e && e.preventDefault) e.preventDefault();
    if (
      confirm('Are you sure that you want to discard all private apps session including yourself ?')
    ) {
      BackOfficeServices.discardAllPrivateAppsSessions().then(() => {
        setTimeout(() => {
          window.location.reload();
        }, 1000);
      });
    }
  };

  discardOldSessions = e => {
    if (e && e.preventDefault) e.preventDefault();
    if (confirm('Are you sure that you want to discard old private apps session ?')) {
      BackOfficeServices.fetchPrivateAppsSessions()
        .then(sessions => {
          let groups = _.groupBy(sessions, i => i.email);
          groups = _.mapValues(groups, g => {
            const values = _.orderBy(g, i => i.expiredAt, 'desc');
            const head = values.shift();
            return Promise.all(
              values.map(v => BackOfficeServices.discardPrivateAppsSession(v.randomId))
            ).then(() => head);
          });
          return Promise.all(_.values(groups));
        })
        .then(() => window.location.reload());
    }
  };

  render() {
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="sessions"
          defaultTitle="Private apps users sessions"
          defaultValue={() => ({})}
          itemName="session"
          columns={this.columns}
          fetchItems={BackOfficeServices.fetchPrivateAppsSessions}
          showActions={false}
          showLink={false}
          extractKey={item => item.randomId}
          injectTopBar={() => [
            <button
              key="discard-all"
              type="button"
              className="btn btn-danger"
              style={{ marginLeft: 15 }}
              onClick={this.discardSessions}>
              <i className="glyphicon glyphicon-fire" /> Discard all sessions
            </button>,
            <button
              key="discard-old"
              type="button"
              className="btn btn-danger"
              style={{ marginLeft: 15 }}
              onClick={this.discardOldSessions}>
              <i className="glyphicon glyphicon-fire" /> Discard old sessions
            </button>,
          ]}
        />
      </div>
    );
  }
}
