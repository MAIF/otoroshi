import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import { WithEnv } from '../components/WithEnv';
import { Link } from 'react-router-dom';
import moment from 'moment';

export class U2FRegisterPage extends Component {
  state = {
    email: '',
    label: '',
    password: '',
    passwordcheck: '',
    error: null,
    message: null,
  };

  columns = [
    {
      title: 'Username',
      content: item => item.username,
    },
    {
      title: 'Label',
      content: item => item.label,
    },
    {
      title: 'Created At',
      content: item => (item.createdAt ? item.createdAt : 0),
      cell: (v, item) =>
        item.createdAt ? moment(item.createdAt).format('DD/MM/YYYY HH:mm:ss') : '',
    },
    {
      title: 'Type',
      content: item => item.type && item.type === 'U2F',
      notFilterable: true,
      cell: (v, item) =>
        item.type && item.type === 'U2F' ? (
          <i className="fas fa-key" />
        ) : (
          <i className="far fa-user" />
        ),
      props: { width: 30, textAlign: 'center' },
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
            type="button"
            className="btn btn-danger btn-xs"
            onClick={e =>
              this.discardAdmin(
                e,
                item.username,
                item.registration ? item.registration.keyHandle : null,
                table
              )
            }>
            <i className="glyphicon glyphicon-fire" /> Discard User
          </button>
        );
      },
    },
  ];

  componentDidMount() {
    this.props.setTitle(`Register new admin`);
  }

  onChange = e => {
    this.setState({ [e.target.name]: e.target.value });
  };

  handleError = err => {
    this.setState({ error: err.message });
  };

  register = e => {
    if (e && e.preventDefault) {
      e.preventDefault();
    }
    const username = this.state.email;
    const password = this.state.password;
    const passwordcheck = this.state.passwordcheck;
    const label = this.state.label;
    if (password !== passwordcheck) {
      return alert('Password does not match !!!');
    }
    fetch(`/bo/u2f/register/start`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        username,
      }),
    })
      .then(r => r.json(), this.handleError)
      .then(payload => {
        const username = payload.username;
        const request = payload.data;
        this.setState({
          message: 'Initializing registration, now touch your blinking U2F device ...',
        });
        u2f.register(request.registerRequests, request.authenticateRequests, data => {
          console.log(data);
          if (data.errorCode) {
            this.setState({ error: `U2F failed with error ${data.errorCode}` });
          } else {
            this.setState({ message: 'Finishing registration ...' });
            fetch(`/bo/u2f/register/finish`, {
              method: 'POST',
              credentials: 'include',
              headers: {
                Accept: 'application/json',
                'Content-Type': 'application/json',
              },
              body: JSON.stringify({
                username: username,
                password,
                label,
                tokenResponse: data,
              }),
            })
              .then(r => r.json(), this.handleError)
              .then(data => {
                console.log(data);
                this.setState({
                  error: null,
                  email: '',
                  label: '',
                  password: '',
                  passwordcheck: '',
                  message: `Registration done for '${data.username}'`,
                });
              }, this.handleError);
          }
        });
      }, this.handleError);
  };

  simpleRegister = e => {
    if (e && e.preventDefault) {
      e.preventDefault();
    }
    const username = this.state.email;
    const password = this.state.password;
    const passwordcheck = this.state.passwordcheck;
    const label = this.state.label;
    if (password !== passwordcheck) {
      return alert('Password does not match !!!');
    }
    fetch(`/bo/simple/admins`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        username,
        password,
        label,
      }),
    })
      .then(r => r.json(), this.handleError)
      .then(data => {
        this.setState({
          error: null,
          email: '',
          label: '',
          password: '',
          passwordcheck: '',
          message: `Registration done for '${data.username}'`,
        });
      }, this.handleError);
  };

  discardAdmin = (e, username, id, table) => {
    if (e && e.preventDefault) e.preventDefault();
    if (confirm(`Are you sure that you want to discard admin user for ${username} ?`)) {
      BackOfficeServices.discardAdmin(username, id).then(() => {
        setTimeout(() => {
          table.update();
          //window.location.href = '/bo/dashboard/admins';
        }, 1000);
      });
    }
  };

  render() {
    return (
      <div>
        <WithEnv predicate={e => e.changePassword}>
          <div class="alert alert-danger" role="alert">
            You are using the default admin account with the default password. You should create a
            new admin account.
          </div>
        </WithEnv>
        <form className="form-horizontal">
          <div className="form-group">
            <label className="col-sm-2 control-label">Label</label>
            <div className="col-sm-10">
              <input
                type="text"
                className="form-control"
                name="label"
                value={this.state.label}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label">Email</label>
            <div className="col-sm-10">
              <input
                type="text"
                className="form-control"
                name="email"
                value={this.state.email}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label">Password</label>
            <div className="col-sm-10">
              <input
                type="password"
                className="form-control"
                name="password"
                value={this.state.password}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label">Re-type Password</label>
            <div className="col-sm-10">
              <input
                type="password"
                className="form-control"
                name="passwordcheck"
                value={this.state.passwordcheck}
                onChange={this.onChange}
              />
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label" />
            <div className="col-sm-10">
              <button type="button" className="btn btn-success" onClick={this.simpleRegister}>
                Register Admin
              </button>
              <button
                type="button"
                className="btn btn-success"
                style={{ marginLeft: 10 }}
                onClick={this.register}>
                Register FIDO U2F Admin
              </button>
            </div>
          </div>
          <div className="form-group">
            <label className="col-sm-2 control-label" />
            <div className="col-sm-10">
              <p>{!this.state.error && this.state.message}</p>
              <p style={{ color: 'red' }}>{!!this.state.error && this.state.error}</p>
            </div>
          </div>
        </form>
        <hr />
        <Table
          parentProps={this.props}
          selfUrl="admins"
          defaultTitle="Register new admin"
          defaultValue={() => ({})}
          itemName="session"
          columns={this.columns}
          fetchItems={BackOfficeServices.fetchAdmins}
          showActions={false}
          showLink={false}
          extractKey={item => (item.registration ? item.registration.keyHandle : item.username)}
        />
      </div>
    );
  }
}
