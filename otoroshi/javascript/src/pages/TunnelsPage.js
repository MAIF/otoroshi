import React, { Component } from 'react';
import moment from 'moment';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table, Form } from '../components/inputs';
import { v4 } from 'uuid';

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

  state = { tunnel: null, routes: [] }

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
        BackOfficeServices.nextClient.forEntity('routes').findAll().then(routes => {
          this.setState({ routes });
        });
      });
    });
  }

  exposeRoute = (originalRoute) => {
    const routesClient = BackOfficeServices.nextClient.forEntity('routes');
    const [hostname, ...parts] = (originalRoute.frontend.indexOf('/') > -1) ? originalRoute.frontend.split('/') : [originalRoute.frontend];
    const root = '/' + parts.join('/');
    const route = {
      "_loc": {
        "tenant": "default",
        "teams": [
          "default"
        ]
      },
      "id": `route_${v4()}`,
      "name": `tunnel exposed '${originalRoute.name}'`,
      "description": `route '${originalRoute.name}' exposed through tunnel '${this.state.tunnel.tunnel_id}'`,
      "tags": [],
      "metadata": {
        from_tunnel_id: this.state.tunnel.tunnel_id,
        from_route_id: originalRoute.id
      },
      "enabled": false,
      "debug_flow": false,
      "export_reporting": false,
      "capture": false,
      "groups": [
        "default"
      ],
      "frontend": {
        "domains": [
          `${originalRoute.id}.oto.tools${root}`
        ],
        "strip_path": true,
        "exact": false,
        "headers": {},
        "query": {},
        "methods": []
      },
      "backend": {
        "targets": [
          {
            "id": "target_1",
            "hostname": hostname,
            "port": 0,
            "tls": false,
            "weight": 1,
            "predicate": {
              "type": "AlwaysMatch"
            },
            "protocol": "HTTP/1.1",
            "ip_address": null,
            "tls_config": {
              "certs": [],
              "trusted_certs": [],
              "enabled": false,
              "loose": false,
              "trust_all": false
            }
          }
        ],
        "target_refs": [],
        "root": root,
        "rewrite": false,
        "load_balancing": {
          "type": "RoundRobin"
        },
        "client": {
          "retries": 1,
          "max_errors": 20,
          "retry_initial_delay": 50,
          "backoff_factor": 2,
          "call_timeout": 30000,
          "call_and_stream_timeout": 120000,
          "connection_timeout": 10000,
          "idle_timeout": 60000,
          "global_timeout": 30000,
          "sample_interval": 2000,
          "proxy": {},
          "custom_timeouts": [],
          "cache_connection_settings": {
            "enabled": false,
            "queue_size": 2048
          }
        },
        "health_check": null
      },
      "backend_ref": null,
      "plugins": [
        {
          "enabled": true,
          "debug": false,
          "plugin": "cp:otoroshi.next.tunnel.TunnelPlugin",
          "include": [],
          "exclude": [],
          "config": {
            "tunnel_id": this.state.tunnel.tunnel_id
          },
          "plugin_index": {}
        }
      ],
    };
    routesClient.create(route).then(r => {
      window.location = `/bo/dashboard/routes/${route.id}?tab=flow`;
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
            <th style={{ textAlign: 'center', top: 10 }}>actions</th>
          </thead>
          <tbody>
            {this.state.tunnel.routes.map(node => {
              const route = this.state.routes
                .filter(r => r.metadata.from_tunnel_id === this.state.tunnel.tunnel_id)
                .find(r => r.metadata.from_route_id === node.id);
              const exposed = !!route;
              return (
                <tr>
                  <td>{node.id}</td>
                  <td>{node.name}</td>
                  <td>{node.frontend}</td>
                  <td>
                    {exposed && <button type="button" className="btn btn-success btn-sm" disabled>expose</button>}
                    {!exposed && <button type="button" className="btn btn-success btn-sm" onClick={e => this.exposeRoute(node)}>expose</button>}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <a className="btn btn-info btn-sm" href="/bo/dashboard/tunnels">Back to tunnels</a>
      </>
    );
  }
}
