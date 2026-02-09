import React, { Component } from 'react';
import { v4 as uuid } from 'uuid';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs/Table';

const extensionId = 'otoroshi.extensions.RemoteCatalogs';

export function setupRemoteCatalogsExtension(registerExtension) {
  registerExtension(extensionId, true, (ctx) => {
    class DeployButton extends Component {
      state = {
        loading: false,
        result: null,
      };

      handleDeploy = () => {
        const { rawValue } = this.props;
        if (!rawValue || !rawValue.id) return;
        this.setState({ loading: true, result: null }, () => {
          fetch('/extensions/remote-catalogs/_deploy', {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify([
              { id: rawValue.id, args: rawValue.test_deploy_args || {} },
            ]),
          })
            .then((r) => r.json())
            .then((data) => {
              this.setState({ result: { success: true, data }, loading: false });
            })
            .catch((e) => {
              this.setState({
                result: { success: false, error: e.message },
                loading: false,
              });
            });
        });
      };

      render() {
        const { loading, result } = this.state;
        return (
          <div className="row mb-3">
            <label className="col-sm-2 col-form-label" />
            <div className="col-sm-10">
              <div className="btn-group" style={{ marginBottom: 10 }}>
                <button
                  type="button"
                  className="btn btn-success"
                  disabled={loading}
                  onClick={this.handleDeploy}
                >
                  {loading ? (
                    <i className="fas fa-spinner fa-spin" />
                  ) : (
                    <i className="fas fa-play" />
                  )}{' '}
                  Deploy now
                </button>
                {result && (
                  <button
                    type="button"
                    className="btn btn-danger"
                    onClick={() => this.setState({ result: null })}
                  >
                    <i className="fas fa-times" /> Clear
                  </button>
                )}
              </div>
              {result && result.success && (
                <pre
                  style={{
                    maxHeight: 400,
                    overflow: 'auto',
                    fontSize: 12,
                    background: 'var(--bg-color_level2, #1b1b2f)',
                    color: 'var(--color_level2, #a8ff78)',
                    padding: 10,
                    borderRadius: 4,
                  }}
                >
                  {JSON.stringify(result.data, null, 2)}
                </pre>
              )}
              {result && !result.success && (
                <div className="alert alert-danger">{result.error}</div>
              )}
            </div>
          </div>
        );
      }
    }

    class TestButton extends Component {
      state = {
        loading: false,
        result: null,
      };

      handleTest = () => {
        const { rawValue } = this.props;
        if (!rawValue || !rawValue.id) return;
        this.setState({ loading: true, result: null }, () => {
          fetch('/extensions/remote-catalogs/_test', {
            method: 'POST',
            credentials: 'include',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
              id: rawValue.id,
              args: rawValue.test_deploy_args || {},
            }),
          })
            .then((r) => r.json())
            .then((data) => {
              this.setState({ result: { success: true, data }, loading: false });
            })
            .catch((e) => {
              this.setState({
                result: { success: false, error: e.message },
                loading: false,
              });
            });
        });
      };

      render() {
        const { loading, result } = this.state;
        return (
          <div className="row mb-3">
            <label className="col-sm-2 col-form-label" />
            <div className="col-sm-10">
              <div className="btn-group" style={{ marginBottom: 10 }}>
                <button
                  type="button"
                  className="btn btn-info"
                  disabled={loading}
                  onClick={this.handleTest}
                >
                  {loading ? (
                    <i className="fas fa-spinner fa-spin" />
                  ) : (
                    <i className="fas fa-vial" />
                  )}{' '}
                  Test / Dry run
                </button>
                {result && (
                  <button
                    type="button"
                    className="btn btn-danger"
                    onClick={() => this.setState({ result: null })}
                  >
                    <i className="fas fa-times" /> Clear
                  </button>
                )}
              </div>
              {result && result.success && (
                <pre
                  style={{
                    maxHeight: 400,
                    overflow: 'auto',
                    fontSize: 12,
                    background: 'var(--bg-color_level2, #1b1b2f)',
                    color: 'var(--color_level2, #78d6ff)',
                    padding: 10,
                    borderRadius: 4,
                  }}
                >
                  {JSON.stringify(result.data, null, 2)}
                </pre>
              )}
              {result && !result.success && (
                <div className="alert alert-danger">{result.error}</div>
              )}
            </div>
          </div>
        );
      }
    }

    class RemoteCatalogsPage extends Component {
      formSchema = {
        _loc: {
          type: 'location',
          props: {},
        },
        id: {
          type: 'string',
          disabled: true,
          props: { label: 'Id', placeholder: '---' },
        },
        name: {
          type: 'string',
          props: { label: 'Name', placeholder: 'My Remote Catalog' },
        },
        description: {
          type: 'string',
          props: { label: 'Description', placeholder: 'Description of the catalog' },
        },
        metadata: {
          type: 'object',
          props: { label: 'Metadata' },
        },
        tags: {
          type: 'array',
          props: { label: 'Tags' },
        },
        enabled: {
          type: 'bool',
          props: { label: 'Enabled' },
        },
        source_kind: {
          type: 'select',
          props: {
            label: 'Source kind',
            possibleValues: [
              { label: 'HTTP', value: 'http' },
              { label: 'File', value: 'file' },
              { label: 'GitHub', value: 'github' },
              { label: 'GitLab', value: 'gitlab' },
              { label: 'S3', value: 's3' },
            ],
          },
        },
        // File source fields
        'source_config.path': {
          type: 'string',
          props: { label: 'File path', placeholder: '/path/to/entities or /path/to/deploy.json' },
        },
        'source_config.pre_command': {
          type: 'array',
          props: { 
            label: 'Pre-command', 
            help: 'Command parts to run before fetch (e.g. git, pull, or rsync, or rclone, etc)',
            placeholder: 'Command parts to run before fetch (e.g. git, pull)' 
          },
        },
        // HTTP source fields
        'source_config.url': {
          type: 'string',
          props: { label: 'URL', placeholder: 'https://example.com/entities/deploy.json' },
        },
        'source_config.headers': {
          type: 'object',
          props: { label: 'Headers' },
        },
        'source_config.timeout': {
          type: 'number',
          props: { label: 'Timeout (ms)', placeholder: '30000' },
        },
        // GitHub / GitLab source fields
        'source_config.repo': {
          type: 'string',
          props: { label: 'Repository URL', placeholder: 'https://github.com/owner/repo.git' },
        },
        'source_config.branch': {
          type: 'string',
          props: { label: 'Branch', placeholder: 'main' },
        },
        'source_config.path': {
          type: 'string',
          props: { label: 'Path', placeholder: 'entities/ or entities/deploy.json' },
        },
        'source_config.token': {
          type: 'password',
          props: { label: 'Token', placeholder: 'ghp_xxx or glpat-xxx' },
        },
        'source_config.base_url': {
          type: 'string',
          props: { label: 'API base URL', placeholder: 'https://api.github.com or https://gitlab.com' },
        },
        // S3 source fields
        'source_config.bucket': {
          type: 'string',
          props: { label: 'Bucket', placeholder: 'my-bucket' },
        },
        'source_config.key': {
          type: 'string',
          props: { label: 'Key', placeholder: 'path/to/deploy.json' },
        },
        'source_config.region': {
          type: 'string',
          props: { label: 'Region', placeholder: 'eu-west-1' },
        },
        'source_config.access': {
          type: 'string',
          props: { label: 'Access key', placeholder: 'AKIA...' },
        },
        'source_config.secret': {
          type: 'password',
          props: { label: 'Secret key', placeholder: '***' },
        },
        'source_config.endpoint': {
          type: 'string',
          props: { label: 'Endpoint', placeholder: 'https://s3.amazonaws.com' },
        },
        // Raw source config
        source_config: {
          type: 'jsonobjectcode',
          props: { label: 'Source configuration' },
        },
        'scheduling.enabled': {
          type: 'bool',
          props: { label: 'Scheduling enabled' },
        },
        'scheduling.kind': {
          type: 'select',
          props: {
            label: 'Job kind',
            possibleValues: [
              { label: 'Scheduled Every', value: 'ScheduledEvery' },
              { label: 'Scheduled Once', value: 'ScheduledOnce' },
              { label: 'Cron', value: 'Cron' },
            ],
          },
        },
        'scheduling.instantiation': {
          type: 'select',
          props: {
            label: 'Job instantiation',
            possibleValues: [
              {
                label: 'One per Otoroshi instance',
                value: 'OneInstancePerOtoroshiInstance',
              },
              {
                label: 'One per Otoroshi cluster',
                value: 'OneInstancePerOtoroshiCluster',
              },
              {
                label: 'One per Otoroshi leader instance',
                value: 'OneInstancePerOtoroshiLeaderInstance',
              },
              {
                label: 'One per Otoroshi worker instance',
                value: 'OneInstancePerOtoroshiWorkerInstance',
              },
            ],
          },
        },
        'scheduling.initial_delay': {
          type: 'number',
          props: { label: 'Initial delay (ms)', placeholder: '10000' },
        },
        'scheduling.interval': {
          type: 'number',
          props: { label: 'Interval (ms)', placeholder: '60000' },
        },
        'scheduling.cron_expression': {
          type: 'string',
          props: { label: 'Cron expression', placeholder: '0 */5 * * * ?' },
        },
        'scheduling.deploy_args': {
          type: 'jsonobjectcode',
          props: { label: 'Scheduled deploy args' },
        },
        test_deploy_args: {
          type: 'jsonobjectcode',
          props: { label: 'Test deploy args' },
        },
        deploy_action: {
          type: DeployButton,
        },
        test_action: {
          type: TestButton,
        },
      };

      columns = [
        {
          title: 'Name',
          filterId: 'name',
          content: (item) => item.name,
        },
        {
          title: 'Source',
          filterId: 'source_kind',
          content: (item) => item.source_kind,
        },
        {
          title: 'Enabled',
          filterId: 'enabled',
          content: (item) => item.enabled,
          cell: (v, item) =>
            item.enabled ? (
              <span className="badge bg-success">yes</span>
            ) : (
              <span className="badge bg-danger">no</span>
            ),
        },
        {
          title: 'Scheduled',
          filterId: 'scheduled',
          content: (item) => item.scheduling && item.scheduling.enabled,
          cell: (v, item) =>
            item.scheduling && item.scheduling.enabled ? (
              <span className="badge bg-success">yes</span>
            ) : (
              <span className="badge bg-warning">no</span>
            ),
        },
      ];

      formFlow = (state) => [
        '_loc',
        'id',
        'name',
        'description',
        'tags',
        'metadata',
        '<<<Source',
        'enabled',
        'source_kind',

        state.source_kind === 'file' ? 'source_config.path' : null,
        state.source_kind === 'file' ? 'source_config.pre_command' : null,

        state.source_kind === 'http' ? 'source_config.url' : null,
        state.source_kind === 'http' ? 'source_config.headers' : null,
        state.source_kind === 'http' ? 'source_config.timeout' : null,

        (state.source_kind === 'github' || state.source_kind === 'gitlab') ? 'source_config.repo' : null,
        (state.source_kind === 'github' || state.source_kind === 'gitlab') ? 'source_config.branch' : null,
        (state.source_kind === 'github' || state.source_kind === 'gitlab') ? 'source_config.path' : null,
        (state.source_kind === 'github' || state.source_kind === 'gitlab') ? 'source_config.token' : null,
        (state.source_kind === 'github' || state.source_kind === 'gitlab') ? 'source_config.base_url' : null,

        state.source_kind === 's3' ? 'source_config.bucket' : null,
        state.source_kind === 's3' ? 'source_config.key' : null,
        state.source_kind === 's3' ? 'source_config.region' : null,
        state.source_kind === 's3' ? 'source_config.access' : null,
        state.source_kind === 's3' ? 'source_config.secret' : null,
        state.source_kind === 's3' ? 'source_config.endpoint' : null,

        '>>>Source raw config.',
        'source_config',
        '>>>Scheduling',
        'scheduling.enabled',
        'scheduling.kind',
        'scheduling.instantiation',
        'scheduling.initial_delay',
        'scheduling.interval',
        'scheduling.cron_expression',
        'scheduling.deploy_args',
        '>>>Test & Deploy',
        'test_deploy_args',
        'test_action',
        'deploy_action',
      ].filter(i => !!i);

      componentDidMount() {
        this.props.setTitle(`All Remote Catalogs`);
      }

      client = BackOfficeServices.apisClient(
        'catalogs.otoroshi.io',
        'v1',
        'remote-catalogs'
      );

      render() {
        return React.createElement(
          Table,
          {
            parentProps: this.props,
            selfUrl: 'extensions/remote-catalogs',
            defaultTitle: 'All Remote Catalogs',
            defaultValue: () => ({
              id: 'remote-catalog_' + uuid(),
              name: 'New Remote Catalog',
              description: 'A new remote catalog',
              tags: [],
              metadata: {},
              enabled: true,
              source_kind: 'http',
              source_config: {},
              scheduling: {
                enabled: false,
                kind: 'ScheduledEvery',
                instantiation: 'OneInstancePerOtoroshiInstance',
                initial_delay: null,
                interval: 60000,
                cron_expression: null,
                deploy_args: {},
              },
              test_deploy_args: {},
            }),
            itemName: 'Remote Catalog',
            formSchema: this.formSchema,
            formFlow: this.formFlow,
            columns: this.columns,
            stayAfterSave: true,
            fetchItems: (paginationState) => this.client.findAll(),
            updateItem: this.client.update,
            deleteItem: this.client.delete,
            createItem: this.client.create,
            navigateTo: (item) => {
              window.location = `/bo/dashboard/extensions/remote-catalogs/edit/${item.id}`;
            },
            itemUrl: (item) =>
              `/bo/dashboard/extensions/remote-catalogs/edit/${item.id}`,
            showActions: true,
            showLink: true,
            rowNavigation: true,
            extractKey: (item) => item.id,
            export: true,
            kubernetesKind: 'catalogs.otoroshi.io/RemoteCatalog',
          },
          null
        );
      }
    }

    return {
      id: extensionId,
      sidebarItems: [],
      creationItems: [],
      dangerZoneParts: [],
      features: [
        {
          title: 'Remote Catalogs',
          description: 'All your Remote Catalogs',
          img: 'cloud-download-alt',
          link: '/extensions/remote-catalogs',
          display: () => true,
          icon: () => 'fa-cloud-download-alt',
        },
      ],
      searchItems: [
        {
          action: () => {
            window.location.href = `/bo/dashboard/extensions/remote-catalogs`;
          },
          env: <span className="fas fa-cloud-download-alt" />,
          label: 'Remote Catalogs',
          value: 'remote-catalogs',
        },
      ],
      routes: [
        {
          path: '/extensions/remote-catalogs/:taction/:titem',
          component: (props) => {
            return <RemoteCatalogsPage {...props} />;
          },
        },
        {
          path: '/extensions/remote-catalogs/:taction',
          component: (props) => {
            return <RemoteCatalogsPage {...props} />;
          },
        },
        {
          path: '/extensions/remote-catalogs',
          component: (props) => {
            return <RemoteCatalogsPage {...props} />;
          },
        },
      ],
    };
  });
}
