import React, { Component } from 'react';
import { Table, Form } from '../components/inputs';

import * as BackOfficeServices from '../services/BackOfficeServices';

export class ErrorTemplatesPage extends Component {
  state = { list: [], fetched: false };

  columns = [
    {
      title: 'Name',
      filterId: 'name',
      content: (item) => item.name,
    },
    {
      title: 'Description',
      filterId: 'description',
      content: (item) => item.description,
    },
  ];

  template = (title, message, comeBack = false, error = false) => {
    return `<!DOCTYPE html>
<html lang="en">
  <head>
      <title>${title}</title>
      <meta charset="utf-8">
      <meta name="viewport" content="width=device-width,initial-scale=1,shrink-to-fit=no">
      <meta name="theme-color" content="#000000">
      <meta name="robots" content="noindex, nofollow">
      <link rel="shortcut icon" type="image/png" href="/__otoroshi_assets/images/favicon.png">
      <link rel="stylesheet" href="/__otoroshi_assets/stylesheets/bootstrap.min.css">
      // <link rel="stylesheet" href="/__otoroshi_assets/stylesheets/bootstrap-theme.min.css">
      <link rel="stylesheet" media="screen" href="/__otoroshi_assets/stylesheets/otoroshiapps.css">
      <link href="/assets/fonts/raleway/raleway.css" rel="stylesheet">
      <link rel="stylesheet" media="screen" href="/__otoroshi_assets/stylesheets/error.css">
  </head>
  <body>
      <div class="container">
        <div class="header">
            <nav class="navbar-inverse"></nav>
            <a class="navbar-brand" href="/" style="display: flex;">
            <span>おとろし</span>&nbsp; Otoroshi
            </a>
        </div>
        <div class="jumbotron">
            ${
              error
                ? `<h2><i class="fas fa-exclamation-triangle"></i> ${title}</h2>`
                : `<h2 style="color:white;">${title}</h2>`
            }
            <p class="lead">
              ${message}
            </p>
            ${comeBack ? '<p class="lead">try to come back later 😉</p>' : ''}
            <p><img class="logo" src="/__otoroshi_assets/images/otoroshi-logo-color.png" style="width: 300px;"></p>
        </div>
      </div>
  </body>
</html>`;
  };

  createTemplate = () => {
    return {
      serviceId: null,
      name: 'New error template',
      description: 'Error template for the xxx route',
      tags: [],
      metadata: {},
      templateBuild: this.template(
        'Custom service under construction',
        "The service you're trying to reach is under construction",
        true
      ),
      templateMaintenance: this.template(
        'Custom service in maintenance',
        "The service you're trying to reach is in maintenance",
        true
      ),
      template40x: this.template(
        'Otoroshi error',
        '${message} - ${cause} - error number: ${errorId}',
        false,
        true
      ),
      template50x: this.template(
        'Otoroshi error',
        '${message} - ${cause} - error number: ${errorId}',
        false,
        true
      ),
      messages: {
        'message-400': '400',
        'message-403': '403',
        'message-404': '404',
        'message-417': '417',
        'message-429': '429',
        'message-500': '500',
        'message-502': '502',
        'message-503': '503',
        'errors.cant.process.more.request': 'Proxy cannot process more request',
        'errors.service.in.maintenance': 'Service in maintenance mode',
        'errors.service.under.construction': 'Service under construction',
        'errors.client.error': 'Client error',
        'errors.server.error': 'Server error',
        'errors.entity.too.big': 'Entity is too big for processing',
        'errors.service.not.found': 'Service not found',
        'errors.request.timeout': 'Request timeout',
        'errors.circuit.breaker.open': 'Service is overwhelmed',
        'errors.connection.refused': 'Connection refused to service',
        'errors.proxy.error': 'Proxy error',
        'errors.no.service.found': 'No service found',
        'errors.service.not.secured': 'The service is not secured',
        'errors.service.down': 'The service is down',
        'errors.too.much.requests': 'Too much requests',
        'errors.invalid.api.key': 'Invalid ApiKey provided',
        'errors.bad.api.key': 'Bad ApiKey provided',
        'errors.no.api.key': 'No ApiKey provided',
        'errors.ip.address.not.allowed': 'IP address not allow',
        'errors.not.found': 'Page not found',
        'errors.bad.origin': 'Bad origin',
      },
    };
  };

  deleteErrorTemplate = (errorTemplate, table) => {
    window
      .newConfirm('Are you sure you want to delete error template "' + errorTemplate.name + '"')
      .then((confirmed) => {
        if (confirmed) {
          BackOfficeServices.deleteErrorTemplate(errorTemplate).then(() => {
            table.update();
          });
        }
      });
  };

  componentDidMount() {
    this.props.setTitle('Error templates');
    BackOfficeServices.findAllServicesWithPagination({
      page: 1,
      pageSize: 999,
      fields: ['id', 'name'],
    }).then((services) => {
      BackOfficeServices.findAllRoutesWithPagination({
        page: 1,
        pageSize: 999,
        fields: ['id', 'name'],
      }).then((routes) => {
        BackOfficeServices.findAllRouteCompositionsWithPagination({
          page: 1,
          pageSize: 999,
          fields: ['id', 'name'],
        }).then((routeCompositions) => {
          const list = [
            ...services.data.map((s) => ({
              label: (
                <div>
                  <span className="badge bg-warning">service</span> {s.name}
                </div>
              ),
              value: s.id,
            })),
            ...routes.data.map((s) => ({
              label: (
                <div>
                  <span className="badge bg-info">route</span> {s.name}
                </div>
              ),
              value: s.id,
            })),
            ...routeCompositions.data.map((s) => ({
              label: (
                <div>
                  <span className="badge bg-secondary">route-comp.</span> {s.name}
                </div>
              ),
              value: s.id,
            })),
          ];
          this.setState({ list, fetched: true });
        });
      });
    });
  }

  gotoErrorTemplate = (errorTemplate) => {
    this.props.history.push({
      pathname: `/error-templates/edit/${errorTemplate.serviceId}`,
    });
  };

  formFlow = [
    '_loc',
    'serviceId',
    'name',
    'description',
    'metadata',
    'tags',
    'template40x',
    'template50x',
    'templateBuild',
    'templateMaintenance',
    'messages',
  ];

  formSchema = () => ({
    serviceId: {
      type: 'select',
      props: {
        label: 'Id',
        placeholder: 'route or service id',
        possibleValues: this.state.list,
      },
    },
    _loc: {
      type: 'location',
      props: {},
    },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'Nice error template' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'A nice error template to do whatever you want' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Metadata' },
    },
    tags: {
      type: 'array',
      props: { label: 'Tags' },
    },
    template40x: {
      type: 'code',
      props: { mode: 'html', label: '40x template', placeholder: 'template for 40x errors' },
    },
    template50x: {
      type: 'code',
      props: { mode: 'html', label: '50x template', placeholder: 'template for 50x errors' },
    },
    templateBuild: {
      type: 'code',
      props: { mode: 'html', label: 'build template', placeholder: 'template for build errors' },
    },
    templateMaintenance: {
      type: 'code',
      props: {
        mode: 'html',
        label: 'maintenance template',
        placeholder: 'template for maintenance errors',
      },
    },
    messages: { type: 'object', props: { label: 'messages' } },
  });

  render() {
    // if (!this.state.fetched) {
    //   return null;
    // }
    console.log(this.state.list);
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="error-templates"
          formSchema={this.formSchema()}
          formFlow={this.formFlow}
          defaultTitle={this.title}
          defaultValue={this.createTemplate}
          itemName="Error Template"
          columns={this.columns}
          stayAfterSave={true}
          fetchItems={(paginationState) =>
            BackOfficeServices.findAllErrorTemplatesWithPagination({
              ...paginationState,
              fields: ['serviceId', 'name', 'description'],
            })
          }
          updateItem={BackOfficeServices.updateErrorTemplate}
          deleteItem={BackOfficeServices.deleteErrorTemplate}
          createItem={BackOfficeServices.createErrorTemplate}
          showActions={true}
          showLink={false}
          rowNavigation={true}
          navigateTo={this.gotoErrorTemplate}
          firstSort={0}
          extractKey={(item) => {
            return item.serviceId;
          }}
          itemUrl={(i) => `/bo/dashboard/error-templates/edit/${i.id}`}
          export={true}
          kubernetesKind="ErrorTemplate"
        />
      </div>
    );
  }
}
