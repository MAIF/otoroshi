import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import moment from 'moment';

export class SessionsPage extends Component {
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
    this.props.setTitle(`Admin users sessions`);
  }

  discardSession = (e, id, table) => {
    if (e && e.preventDefault) e.preventDefault();
    window
      .newConfirm(`Are you sure that you want to discard admin session for ${id} ?`)
      .then(ok => {
        if (ok) {
          BackOfficeServices.discardSession(id).then(() => {
            setTimeout(() => {
              table.update();
              //window.location.href = '/bo/dashboard/sessions/admin';
            }, 1000);
          });
        }
      });
  };

  discardSessions = e => {
    if (e && e.preventDefault) e.preventDefault();
    window
      .newConfirm('Are you sure that you want to discard all admin session including yourself ?')
      .then(ok => {
        if (ok) {
          BackOfficeServices.discardAllSessions().then(() => {
            setTimeout(() => {
              window.location.href = '/';
            }, 1000);
          });
        }
      });
  };

  discardOldSessions = e => {
    if (e && e.preventDefault) e.preventDefault();
    window.newConfirm('Are you sure that you want to discard old admin session ?').then(ok => {
      if (ok) {
        BackOfficeServices.fetchSessions()
          .then(sessions => {
            let groups = _.groupBy(sessions, i => i.email);
            groups = _.mapValues(groups, g => {
              const values = _.orderBy(g, i => i.expiredAt, 'desc');
              const head = values.shift();
              return Promise.all(
                values.map(v => BackOfficeServices.discardSession(v.randomId))
              ).then(() => head);
            });
            return Promise.all(_.values(groups));
          })
          .then(() => window.location.reload());
      }
    });
  };

  render() {
    if (!window.__user.superAdmin) {
      return null;
    }
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="sessions"
          defaultTitle="Admin users sessions"
          defaultValue={() => ({})}
          itemName="session"
          columns={this.columns}
          fetchItems={BackOfficeServices.fetchSessions}
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
