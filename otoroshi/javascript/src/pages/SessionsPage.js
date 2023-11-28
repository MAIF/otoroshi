import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import moment from 'moment';

import groupBy from 'lodash/groupBy';
import mapValues from 'lodash/mapValues';
import orderBy from 'lodash/orderBy';
import values from 'lodash/values';

export class SessionsPage extends Component {
  columns = [
    { title: 'Name', filterId: 'name', content: (item) => item.name },
    {
      title: 'Email',
      filterId: 'email',
      content: (item) => item.email,
    },
    {
      title: 'Created At',
      filterId: 'createdAt',
      content: (item) => (item.createdAt ? item.createdAt : 0),
      cell: (v, item) =>
        item.createdAt ? moment(item.createdAt).format('DD/MM/YYYY HH:mm:ss') : '',
    },
    {
      title: 'Expires At',
      filterId: 'expiredAt',
      content: (item) => (item.expiredAt ? item.expiredAt : 0),
      cell: (v, item) =>
        item.expiredAt ? moment(item.expiredAt).format('DD/MM/YYYY HH:mm:ss') : '',
    },
    {
      title: 'Profile',
      content: (item) => 0,
      notFilterable: true,
      style: { textAlign: 'center', width: 70 },
      cell: (v, item) => (
        <button
          type="button"
          className="btn btn-primary btn-sm"
          onClick={(e) =>
            window.newAlert(
              <pre style={{ height: 300 }}>{JSON.stringify(item.profile, null, 2)}</pre>,
              'Profile'
            )
          }>
          Profile
        </button>
      ),
    },
    {
      title: 'Rights.',
      content: (item) => 0,
      notFilterable: true,
      style: { textAlign: 'center', width: 70 },
      cell: (v, item) => (
        <button
          type="button"
          className="btn btn-primary btn-sm"
          onClick={(e) =>
            window.newAlert(
              <pre style={{ height: 300 }}>{JSON.stringify(item.rights, null, 2)}</pre>,
              'Rights'
            )
          }>
          Rights
        </button>
      ),
    },
    {
      title: 'Action',
      style: { textAlign: 'center', width: 150 },
      notSortable: true,
      notFilterable: true,
      content: (item) => item,
      cell: (v, item, table) => {
        return (
          <button
            key={item.randomId}
            type="button"
            className="btn btn-danger btn-sm"
            onClick={(e) => this.discardSession(e, item.randomId, table)}>
            <i className="fas fa-fire" /> Discard Session
          </button>
        );
      },
    },
  ];

  componentDidMount() {
    this.props.setTitle(`Admin sessions`);
  }

  discardSession = (e, id, table) => {
    if (e && e.preventDefault) e.preventDefault();
    window
      .newConfirm(`Are you sure that you want to discard admin session for ${id} ?`)
      .then((ok) => {
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

  discardSessions = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    window
      .newConfirm('Are you sure that you want to discard all admin session including yourself ?')
      .then((ok) => {
        if (ok) {
          BackOfficeServices.discardAllSessions().then(() => {
            setTimeout(() => {
              window.location.href = '/';
            }, 1000);
          });
        }
      });
  };

  discardOldSessions = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    window.newConfirm('Are you sure that you want to discard old admin session ?').then((ok) => {
      if (ok) {
        BackOfficeServices.fetchSessions()
          .then((sessions) => {
            let groups = groupBy(sessions.data, (i) => i.email);
            groups = mapValues(groups, (g) => {
              const values = orderBy(g, (i) => i.expiredAt, 'desc');
              const head = values.shift();
              return Promise.all(
                values.map((v) => BackOfficeServices.discardSession(v.randomId))
              ).then(() => head);
            });
            return Promise.all(values(groups));
          })
          .then(() => window.location.reload());
      }
    });
  };

  render() {
    if (!window.__user.tenantAdmin) {
      return null;
    }
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="sessions"
          defaultTitle="Admin sessions"
          defaultValue={() => ({})}
          itemName="session"
          columns={this.columns}
          fetchItems={(paginateState) =>
            BackOfficeServices.fetchSessions({
              ...paginateState,
              fields: ['name', 'email', 'createdAt', 'expiredAt', 'profile', 'rights', 'randomId'],
            })
          }
          showActions={false}
          showLink={false}
          extractKey={(item) => item.randomId}
          injectTopBar={() => [
            window.__user.superAdmin ? (
              <button
                key="discard-all"
                type="button"
                className="btn btn-danger btn-sm ms-2"
                onClick={this.discardSessions}>
                <i className="fas fa-fire" /> Discard all sessions
              </button>
            ) : null,
            <button
              key="discard-old"
              type="button"
              className="btn btn-danger btn-sm ms-2"
              onClick={this.discardOldSessions}>
              <i className="fas fa-fire" /> Discard old sessions
            </button>,
          ]}
        />
      </div>
    );
  }
}
