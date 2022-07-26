import React, { Component } from 'react';
import moment from 'moment';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table, Form } from '../components/inputs';

export class TunnelsPage extends Component {

  state = { env: null }

  columns = [
    {
      title: 'Id',
      content: (item) => item.tunnel_id,
    },
    {
      title: 'Name',
      content: (item) => item.name,
    },
    {
      title: 'Routes',
      style: { textAlign: 'center', width: 100 },
      content: (item) => item.routes.length,
    },
    {
      title: 'Nodes',
      style: { textAlign: 'center', width: 100 },
      content: (item) => item.nodes.length,
    },
    {
      title: 'Last Seen',
      content: (item) => item.last_seen,
    },
    {
      title: 'Actions',
      content: (item) => '',
      notFilterable: true,
      style: { textAlign: 'center', width: 100 },
      cell: (a, item) => {
        return (
          <div style={{ width: '100%' }}>
            <a className="btn btn-info btn-sm" href={`/bo/dashboard/tunnels/${item.tunnel_id}`}>
              <i className="fas fa-eye" />
            </a>
          </div>
        )
      },
    }
  ];

  componentDidMount() {
    this.props.setTitle(`Connected tunnels`);
    this.interval = setInterval(this.update, 5000);
    BackOfficeServices.env().then((env) => {
      this.setState({ env })
    });
  }

  componentWillUnmount() {
    if (this.interval) {
      clearInterval(this.interval);
    }
  }

  gotoTunnel = (tunnel) => {
    this.props.history.push({
      pathname: `tunnels/${tunnel.id}`,
    });
  };

  render() {
    if (!window.__user.superAdmin) {
      return null;
    }
    return (
      <Table
        parentProps={this.props}
        selfUrl="tunnels"
        defaultTitle="Connected tunnels"
        defaultValue={() => ({})}
        itemName="tunnel"
        columns={this.columns}
        fetchItems={BackOfficeServices.fetchTunnels}
        showActions={false}
        showLink={false}
        extractKey={(item) => item.id}
      />
    );
  }
}

export class TunnelPage extends Component {

  state = { tunnel: null }

  schema = {
    tunnel_id: { type: 'string', disabled: true, props: { label: 'id', placeholder: '---' } },
    name: { type: 'string', disabled: true, props: { label: 'name', placeholder: '---' } },
    last_seen: { type: 'string', disabled: true, props: { label: 'last_seen', placeholder: '---' } },
  }

  flow = [
    'tunnel_id', 'name', 'last_seen'
  ]

  componentDidMount() {
    const id = this.props.match.params.id;
    BackOfficeServices.env().then((env) => {
      this.setState({ env });
      BackOfficeServices.fetchTunnels().then(tunnels => {
        const tunnel = tunnels.filter(t => t.tunnel_id == id)[0];
        this.props.setTitle(`Connected tunnel '${tunnel.name}'`);
        this.setState({ tunnel })
      });
    });
  }

  render() {
    if (!this.state.tunnel) {
      return null;
    }
    return (
      <>
        <Form value={this.state.tunnel} onChange={() => ''} flow={this.flow} schema={this.schema} />
        <h3 style={{ marginTop: 20 }}>Nodes ({this.state.tunnel.nodes.length})</h3>
        <table className="table table-striped table-hover table-sm">
          <thead style={{ color: 'white' }}>
            <th style={{ textAlign: 'center', top: 10 }}>id</th>
            <th style={{ textAlign: 'center', top: 10 }}>name</th>
            <th style={{ textAlign: 'center', top: 10 }}>location</th>
            <th style={{ textAlign: 'center', top: 10 }}>http_port</th>
            <th style={{ textAlign: 'center', top: 10 }}>https_port</th>
            <th style={{ textAlign: 'center', top: 10 }}>last_seen</th>
            <th style={{ textAlign: 'center', top: 10 }}>type</th>
          </thead>
          <tbody>
            {this.state.tunnel.nodes.map(node => (
              <tr>
                <td>{node.id}</td>
                <td>{node.name}</td>
                <td>{node.location}</td>
                <td>{node.http_port}</td>
                <td>{node.https_port}</td>
                <td>{node.last_seen}</td>
                <td>{node.type}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <h3 style={{ marginTop: 20 }}>Routes ({this.state.tunnel.routes.length})</h3>
        <table className="table table-striped table-hover table-sm">
          <thead style={{ color: 'white' }}>
            <th style={{ textAlign: 'center', top: 10 }}>id</th>
            <th style={{ textAlign: 'center', top: 10 }}>name</th>
            <th style={{ textAlign: 'center', top: 10 }}>frontend</th>
          </thead>
          <tbody>
            {this.state.tunnel.routes.map(node => (
              <tr>
                <td>{node.id}</td>
                <td>{node.name}</td>
                <td>{node.frontend}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <a className="btn btn-info btn-sm" href="/bo/dashboard/tunnels">Back to tunnels</a>
      </>
    );
  }
}
