import React, { Component } from 'react';
import moment from 'moment';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table, Form } from '../components/inputs';
import { v4 } from 'uuid';
import ReactTable from 'react-table';
import { Collapse } from '../components/inputs/Collapse';
import { Link } from 'react-router-dom';

export class TunnelsPage extends Component {
  state = {};

  columns = [
    {
      title: 'Id',
      filterId: 'id',
      content: (item) => item.tunnel_id,
    },
    {
      title: 'Name',
      filterId: 'name',
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
      filterId: 'last_seen',
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
            <Link className="btn btn-primary btn-sm" to={`/tunnels/${item.tunnel_id}`}>
              <i className="fas fa-eye" />
            </Link>
          </div>
        );
      },
    },
  ];

  componentDidMount() {
    this.props.setTitle(`Connected tunnels`);
    this.interval = setInterval(this.update, 5000);
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
  state = { tunnel: null, routes: [] };

  schema = {
    tunnel_id: { type: 'string', disabled: true, props: { label: 'id', placeholder: '---' } },
    name: { type: 'string', disabled: true, props: { label: 'name', placeholder: '---' } },
    last_seen: {
      type: 'string',
      disabled: true,
      props: { label: 'last_seen', placeholder: '---' },
    },
  };

  flow = ['tunnel_id', 'name', 'last_seen'];

  componentDidMount() {
    const id = this.props.match.params.id;
    BackOfficeServices.fetchTunnels().then((tunnels) => {
      const tunnel = tunnels.filter((t) => t.tunnel_id == id)[0];
      this.props.setTitle(`Connected tunnel '${tunnel.name}'`);
      this.setState({ tunnel });
      BackOfficeServices.nextClient
        .forEntity('routes')
        .findAll()
        .then((routes) => {
          this.setState({ routes });
        });
    });
  }

  exposeRoute = (originalRoute) => {
    const routesClient = BackOfficeServices.nextClient.forEntity('routes');
    const [hostname, ...parts] =
      originalRoute.frontend.indexOf('/') > -1
        ? originalRoute.frontend.split('/')
        : [originalRoute.frontend];
    const root = '/' + parts.join('/');
    const route = {
      _loc: {
        tenant: 'default',
        teams: ['default'],
      },
      id: `route_${v4()}`,
      name: `tunnel exposed '${originalRoute.name}'`,
      description: `route '${originalRoute.name}' exposed through tunnel '${this.state.tunnel.tunnel_id}'`,
      tags: [],
      metadata: {
        from_tunnel_id: this.state.tunnel.tunnel_id,
        from_route_id: originalRoute.id,
      },
      enabled: false,
      debug_flow: false,
      export_reporting: false,
      capture: false,
      groups: ['default'],
      frontend: {
        domains: [`${originalRoute.id}.oto.tools${root}`],
        strip_path: true,
        exact: false,
        headers: {},
        query: {},
        methods: [],
      },
      backend: {
        targets: [
          {
            id: 'target_1',
            hostname: hostname,
            port: 0,
            tls: false,
            weight: 1,
            predicate: {
              type: 'AlwaysMatch',
            },
            protocol: 'HTTP/1.1',
            ip_address: null,
            tls_config: {
              certs: [],
              trusted_certs: [],
              enabled: false,
              loose: false,
              trust_all: false,
            },
          },
        ],
        root: root,
        rewrite: false,
        load_balancing: {
          type: 'RoundRobin',
        },
        client: {
          retries: 1,
          max_errors: 20,
          retry_initial_delay: 50,
          backoff_factor: 2,
          call_timeout: 30000,
          call_and_stream_timeout: 120000,
          connection_timeout: 10000,
          idle_timeout: 60000,
          global_timeout: 30000,
          sample_interval: 2000,
          proxy: {},
          custom_timeouts: [],
          cache_connection_settings: {
            enabled: false,
            queue_size: 2048,
          },
        },
        health_check: null,
      },
      backend_ref: null,
      plugins: [
        {
          enabled: true,
          debug: false,
          plugin: 'cp:otoroshi.next.tunnel.TunnelPlugin',
          include: [],
          exclude: [],
          config: {
            tunnel_id: this.state.tunnel.tunnel_id,
          },
          plugin_index: {},
        },
      ],
    };
    routesClient.create(route).then((r) => {
      window.location = `/bo/dashboard/routes/${route.id}?tab=flow`;
    });
  };

  columnBuilder = (c) => {
    return {
      Header: c.title,
      id: c.id || c.title,
      headerStyle: c.style,
      width: c.style && c.style.width ? c.style.width : undefined,
      style: { ...c.style, height: 30 },
      sortable: !c.notSortable,
      filterable: !c.notFilterable,
      accessor: (d) => (c.content ? c.content(d) : d),
      Filter: (d) => (
        <input
          type="text"
          className="form-control input-sm"
          value={d.filter ? d.filter.value : ''}
          onChange={(e) => d.onChange(e.target.value)}
          placeholder="Search ..."
        />
      ),
      Cell: (r) => {
        const value = r.value;
        const original = r.original;
        if (c.cell) {
          return c.cell(value, original, this);
        } else {
          return value[c.id || c.title];
        }
      },
    };
  };

  nodeColumns = [
    { title: 'id' },
    { title: 'name' },
    { title: 'location' },
    { title: 'http_port', style: { width: 80 } },
    { title: 'https_port', style: { width: 80 } },
    { title: 'last_seen' },
    { title: 'type', style: { width: 80 } },
  ].map(this.columnBuilder);

  routesColumns = [
    { title: 'id' },
    { title: 'name' },
    // { title: 'frontend' },
    {
      title: 'actions',
      notFilterable: true,
      content: () => '',
      style: { width: 100 },
      cell: (item, node) => {
        const route = this.state.routes
          .filter((r) => r.metadata.from_tunnel_id === this.state.tunnel.tunnel_id)
          .find((r) => r.metadata.from_route_id === node.id);
        const exposed = !!route;
        if (exposed) {
          return (
            <button type="button" className="btn btn-success btn-sm" disabled>
              expose
            </button>
          );
        } else {
          return (
            <button
              type="button"
              className="btn btn-success btn-sm"
              onClick={(e) => this.exposeRoute(node)}>
              expose
            </button>
          );
        }
      },
    },
  ].map(this.columnBuilder);

  render() {
    if (!this.state.tunnel) {
      return null;
    }
    return (
      <>
        <Form value={this.state.tunnel} onChange={() => ''} flow={this.flow} schema={this.schema} />
        <Collapse label={`Nodes (${this.state.tunnel.nodes.length})`} noLeftColumn>
          <ReactTable
            className="fulltable -striped -highlight"
            data={this.state.tunnel.nodes}
            loading={false}
            filterable={true}
            filterAll={true}
            defaultSorted={[{ id: 'desc' }]}
            defaultFiltered={[]}
            defaultPageSize={
              this.state.tunnel.nodes.length > 20 ? 20 : this.state.tunnel.nodes.length
            }
            columns={this.nodeColumns}
            defaultFilterMethod={(filter, row, column) => {
              const id = filter.pivotId || filter.id;
              if (row[id] !== undefined) {
                const value = String(row[id]);
                return value.toLowerCase().indexOf(filter.value.toLowerCase()) > -1;
              } else {
                return true;
              }
            }}
          />
        </Collapse>
        <Collapse label={`Routes (${this.state.tunnel.routes.length})`} noLeftColumn>
          <ReactTable
            className="fulltable -striped -highlight"
            data={this.state.tunnel.routes}
            loading={false}
            filterable={true}
            filterAll={true}
            defaultSorted={[{ id: 'desc' }]}
            defaultFiltered={[]}
            defaultPageSize={
              this.state.tunnel.routes.length > 20 ? 20 : this.state.tunnel.routes.length
            }
            columns={this.routesColumns}
            defaultFilterMethod={(filter, row, column) => {
              const id = filter.pivotId || filter.id;
              if (row[id] !== undefined) {
                const value = String(row[id]);
                return value.toLowerCase().indexOf(filter.value.toLowerCase()) > -1;
              } else {
                return true;
              }
            }}
          />
        </Collapse>
        <div
          style={{
            display: 'flex',
            flexDirection: 'row',
            alignItems: 'flex-end',
            justifyContent: 'flex-end',
            width: '100%',
          }}>
          <Link className="btn btn-danger btn-sm" to="/tunnels" style={{ marginTop: 30 }}>
            <i className="fas fa-times" /> Cancel
          </Link>
        </div>
      </>
    );
  }
}
