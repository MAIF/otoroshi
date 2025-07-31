import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import moment from 'moment';
import groupBy from 'lodash/groupBy';
import mapValues from 'lodash/mapValues';
import values from 'lodash/values';
import orderBy from 'lodash/orderBy';

export class PrivateAppsSessionsPage extends Component {
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
          className="btn btn-success btn-sm"
          onClick={(e) =>
            window.newAlert(
              <pre style={{ height: 300 }}>{JSON.stringify(item.profile, null, 2)}</pre>,
              'Profile'
            )
          }
        >
          Profile
        </button>
      ),
    },
    {
      title: 'Meta.',
      content: (item) => 0,
      notFilterable: true,
      style: { textAlign: 'center', width: 70 },
      cell: (v, item) => (
        <button
          type="button"
          className="btn btn-success btn-sm"
          onClick={(e) =>
            window.newAlert(
              <pre style={{ height: 300 }}>{JSON.stringify(item.otoroshiData, null, 2)}</pre>,
              'Metadata'
            )
          }
        >
          Meta.
        </button>
      ),
    },
    {
      title: 'Tokens',
      content: (item) => 0,
      notFilterable: true,
      style: { textAlign: 'center', width: 70 },
      cell: (v, item) => (
        <button
          type="button"
          className="btn btn-success btn-sm"
          onClick={(e) =>
            window.newAlert(
              <pre style={{ height: 300 }}>{JSON.stringify(item.token, null, 2)}</pre>,
              'Tokens'
            )
          }
        >
          Tokens
        </button>
      ),
    },
    {
      title: 'Realm',
      filterId: 'realm',
      content: (item) => item.realm,
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
            onClick={(e) => this.discardSession(e, item.randomId, table)}
          >
            <i className="fas fa-fire" /> Discard Session
          </button>
        );
      },
    },
  ];

  componentDidMount() {
    this.props.setTitle(`Auth. module sessions`);
  }

  discardSession = (e, id, table) => {
    if (e && e.preventDefault) e.preventDefault();
    window
      .newConfirm(`Are you sure that you want to discard private apps session for ${id} ?`)
      .then((ok) => {
        if (ok) {
          BackOfficeServices.discardPrivateAppsSession(id).then(() => {
            setTimeout(() => {
              table.update();
            }, 1000);
          });
        }
      });
  };

  discardSessions = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    window
      .newConfirm(
        'Are you sure that you want to discard all private apps session including yourself ?'
      )
      .then((ok) => {
        if (ok) {
          BackOfficeServices.discardAllPrivateAppsSessions().then(() => {
            setTimeout(() => {
              window.location.reload();
            }, 1000);
          });
        }
      });
  };

  discardOldSessions = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    window
      .newConfirm('Are you sure that you want to discard old private apps session ?')
      .then((ok) => {
        if (ok) {
          BackOfficeServices.fetchPrivateAppsSessions()
            .then((sessions) => {
              let groups = groupBy(sessions.data, (i) => i.email);
              groups = mapValues(groups, (g) => {
                const values = orderBy(g, (i) => i.expiredAt, 'desc');
                const head = values.shift();
                return Promise.all(
                  values.map((v) => BackOfficeServices.discardPrivateAppsSession(v.randomId))
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
          defaultTitle="Auth. module sessions"
          defaultValue={() => ({})}
          itemName="session"
          columns={this.columns}
          fetchItems={(paginationState) =>
            BackOfficeServices.fetchPrivateAppsSessions({
              ...paginationState,
              fields: [
                'name',
                'email',
                'createdAt',
                'expiredAt',
                'profile',
                'rights',
                'randomId',
                'otoroshiData',
              ],
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
                onClick={this.discardSessions}
              >
                <i className="fas fa-fire" /> Discard all sessions
              </button>
            ) : null,
            <button
              key="discard-old"
              type="button"
              className="btn btn-danger btn-sm ms-2"
              onClick={this.discardOldSessions}
            >
              <i className="fas fa-fire" /> Discard old sessions
            </button>,
          ]}
        />
      </div>
    );
  }
}
