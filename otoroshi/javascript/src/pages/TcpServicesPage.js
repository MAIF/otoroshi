import React, { Component } from 'react';
import { Table, SelectInput } from '../components/inputs';

import * as BackOfficeServices from '../services/BackOfficeServices';

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
    //'rules',
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
          itemName="Tcp Services"
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
          extractKey={item => item.id}
          itemUrl={i => `/bo/dashboard/lines/${i.env}/tcp/services/${i.id}`}
        />
      </div>
    );
  }
}
