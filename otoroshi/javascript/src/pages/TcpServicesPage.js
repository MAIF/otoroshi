import React, { Component } from 'react';
import { Table, Form } from '../components/inputs';

import * as BackOfficeServices from '../services/BackOfficeServices';

class Target extends Component {

  formFlow = [
    'domain',
    'target.host',
    'target.ip',
    'target.port',
    'target.tls',
  ]

  formSchema = {
    domain: {
      type: 'string',
      props: {
        label: 'Matching domain name',
      },
    },
    'target.host': {
      type: 'string',
      props: {
        label: 'Target host',
      },
    },
    'target.ip': {
      type: 'string',
      props: {
        label: 'Target ip address',
      },
    },
    'target.port': {
      type: 'number',
      props: {
        label: 'Target port',
      },
    },
    'target.tls': {
      type: 'bool',
      props: {
        label: 'TLS call',
      },
    },
  }

  render() {
    const domain = this.props.domain;
    const target = this.props.target;
    return (
      <div style={{ backgroundColor: 'rgb(65, 65, 65)', borderRadius: 4, padding: 10, width: '100%', marginBottom: 5 }}>
        <Form
          value={{ domain, target }}
          onChange={this.props.onChange}
          flow={this.formFlow}
          schema={this.formSchema}
          style={{ marginTop: 5 }}
        />
        <div style={{ display: 'flex', width: '100%', justifyContent: 'flex-end', alignItems: 'center' }}>
          <button type="button" className="btn btn-danger" onClick={this.props.delete}><i className="glyphicon glyphicon-trash" /></button>
        </div>
      </div>
    );
  }
}

class Targets extends Component {

  changeTarget = (idx, oldRule, oldTarget, newTarget) => {
    const value = this.props.rawValue;
    value.rules.forEach(r => {
      if (r.domain === oldRule.domain) {
        if (r.domain !== newTarget.domain) {
          r.domain = newTarget.domain;
        } else {
          r.targets.forEach((target, i) => {
            if (i === idx) {
              target.host = newTarget.target.host;
              target.ip = newTarget.target.ip;
              target.port = newTarget.target.port;
              target.tls = newTarget.target.tls;
            }
          });
        }
      }
    });
    this.props.rawOnChange(value);
  }

  deleteTarget = (domain, target, idx) => {
    const value = this.props.rawValue;
    value.rules.forEach(r => {
      if (r.domain === domain) {
        r.targets = r.targets.filter((t, i) => i !== idx);
      }
    });
    value.rules = value.rules.filter(r => r.targets.length > 0);
    this.props.rawOnChange(value);
  }

  addTarget = () => {
    const value = this.props.rawValue;
    const rules = value.rules.filter(r => r.domain !== '*');
    const rule = value.rules.filter(r => r.domain === '*')[0] || { domain: '*', targets: [] };
    rule.targets.push({ 
      host: 'my.new.host',
      ip: null,
      port: 1234,
      tls: false
    });
    rules.push(rule);
    value.rules = rules;
    this.props.rawOnChange(value);
  }

  render() {
    return (
      <div>
        {
          this.props.value.map(rule => {
            return rule.targets.map((target, idx) => {
              return <Target 
                domain={rule.domain} 
                target={target} 
                onChange={newTarget => this.changeTarget(idx, rule, target, newTarget)} 
                delete={() => this.deleteTarget(rule.domain, target, idx)} 
              />
            });
          })
        }
        <div style={{ display: 'flex', width: '100%', justifyContent: 'center', alignItems: 'center' }}>
          <button type="button" className="btn btn-primary" onClick={this.addTarget}><i className="glyphicon glyphicon-plus-sign" /></button>
        </div>
      </div>
    )
  }
}

export class TcpServicesPage extends Component {

  columns = [
    {
      title: 'Name',
      content: item => item.name,
    },
    {
      title: 'Port',
      style: { textAlign: 'center', width: 80 },
      content: item => item.port
    },
    {
      title: 'Interface',
      style: { textAlign: 'center', width: 80 },
      content: item => item.interface
    },
    {
      title: 'Tls',
      style: { textAlign: 'center', width: 70 },
      notFilterable: true,
      noMobile: true,
      content: item => item.tls,
      cell: (v, item) => {
        if (item.tls === 'Disabled') {
          return <i className="" />
        } else if (item.tls === 'PassThrough') {
          return <i className="fas fa-unlock-alt fa-lg" />
        } else {
          return <i className="fas fa-lock fa-lg" />
        }
      }
    },
    {
      title: 'Client Auth.',
      style: { textAlign: 'center', width: 70 },
      notFilterable: true,
      noMobile: true,
      content: item => item.clientAuth,
      cell: (v, item) => {
        if (item.clientAuth === 'None') {
          return <i className="" />
        } else if (item.clientAuth === 'Want') {
          return <span className="glyphicon glyphicon-ok-sign" /> 
        } else {
          return <span className="glyphicon glyphicon-ok-sign" /> 
        }
      }
    },
    {
      title: 'SNI routing',
      style: { textAlign: 'center', width: 70 },
      notFilterable: true,
      noMobile: true,
      content: item => item.clientAuth,
      cell: (v, item) => {
        if (!item.sni.enabled) {
          return <i className="" />
        } else {
          return <span className="glyphicon glyphicon-ok-sign" /> 
        }
      }
    }
  ];

  deleteService = (service, table) => {
    window
      .newConfirm('Are you sure you want to delete service "' + service.name + '"')
      .then(confirmed => {
        if (confirmed) {
          BackOfficeServices.deleteTcpService(service).then(() => {
            table.update();
          });
        }
      });
  };

  componentDidMount() {
    this.props.setTitle("All Tcp Services");
  }

  gotoService = service => {
    this.props.history.push({
      pathname: `/lines/${service.env}/tcp/services/${service.id}`,
    });
  };

  formFlow = [
    'id',
    'name',
    'enabled',
    'port',
    'interface',
    '>>>TLS',
    'tls',
    'clientAuth',
    '>>>SNI',
    'sni.enabled',
    'sni.forwardIfNoMatch',
    'sni.forwardsTo.host',
    'sni.forwardsTo.ip',
    'sni.forwardsTo.port',
    'sni.forwardsTo.tls',
    '>>>Rules',
    'rules',
  ];

  formSchema = {
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Tcp Service name', placeholder: 'My Awesome Service' },
    },
    enabled: {
      type: 'bool',
      props: { label: 'Enabled' },
    },
    port: {
      type: 'number',
      props: { label: 'Tcp Service port' },
    },
    interface: {
      type: 'string',
      props: { label: 'Tcp Service interface', placeholder: '0.0.0.0' },
    },
    tls: {
      type: 'select',
      props: {
        label: 'TLS mode',
        possibleValues: [
          { label: 'Disabled', value: 'Disabled' },
          { label: 'PassThrough', value: 'PassThrough' },
          { label: 'Enabled', value: 'Enabled' },
        ]
      }
    },
    'sni.enabled': {
      type: 'bool',
      props: { label: 'SNI routing enabled' },
    },
    'sni.forwardIfNoMatch': {
      type: 'bool',
      props: { label: 'Forward to target if no SNI match' },
    },
    'sni.forwardsTo.host': {
      type: 'string',
      props: { label: 'Target host', placeholder: 'foo.bar' },
    },
    'sni.forwardsTo.ip': {
      type: 'string',
      props: { label: 'Target ip address', placeholder: '1.1.1.1' },
    },
    'sni.forwardsTo.port': {
      type: 'string',
      props: { label: 'Target port' },
    },
    'sni.forwardsTo.tls': {
      type: 'bool',
      props: { label: 'TLS call' },
    },
    clientAuth: {
      type: 'select',
      props: {
        label: 'Client Auth.',
        possibleValues: [
          { label: 'None', value: 'None' },
          { label: 'Want', value: 'Want' },
          { label: 'Need', value: 'Need' },
        ]
      }
    },
    rules: {
      type: Targets
    }
  };

  render() {
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="tcp/services"
          formSchema={this.formSchema}
          formFlow={this.formFlow}
          defaultTitle={this.title}
          defaultValue={BackOfficeServices.createNewTcpService}
          itemName="Tcp Service"
          columns={this.columns}
          stayAfterSave={true}
          fetchItems={BackOfficeServices.findAllTcpServices}
          updateItem={BackOfficeServices.updateTcpService}
          deleteItem={BackOfficeServices.deleteTcpService}
          createItem={BackOfficeServices.createTcpService}
          showActions={true}
          showLink={false}
          rowNavigation={true}
          navigateTo={this.gotoService}
          firstSort={0}
          extractKey={item => {
            console.log(item.id)
            return item.id
          }}
          itemUrl={i => `/bo/dashboard/tcp/services/edit/${i.id}`}
        />
      </div>
    );
  }
}
