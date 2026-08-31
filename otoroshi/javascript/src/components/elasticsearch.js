import React, { Component } from 'react';

export class CheckElasticsearchConnection extends Component {
  checkConnection = () => {
    fetch('/bo/api/elastic/_check', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(this.props.rawValue),
    })
      .then((r) => r.json())
      .then((r) => {
        console.log(r);
        if (r.none) {
          window.newAlert(
            'Missing informations to make the connection to the Elasticsearch cluster'
          );
        } else {
          window.popup('Elasticsearch connection', (ok, cancel) => (
            <ElasticsearchConnectionDiagnostic
              ok={ok}
              cancel={cancel}
              resp={r}
              spec={this.props.rawValue}
            />
          ));
        }
      });
  };

  fillVersion = () => {
    fetch('/bo/api/elastic/_check', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(this.props.rawValue),
    })
      .then((r) => r.json())
      .then((r) => {
        if (!r.none) {
          // config_version keeps the distribution when the cluster is an opensearch one, as its
          // version numbering cannot be told apart from an old elasticsearch one
          this.props.rawOnChange({
            ...this.props.rawValue,
            version: r.config_version || r.version,
          });
        } else {
          window.newAlert('Unable to get informations from the Elasticsearch cluster');
        }
      })
      .catch((err) => {
        window.newAlert('Unable to connect to the Elasticsearch cluster');
      });
  };

  applyTemplate = () => {
    fetch('/bo/api/elastic/_apply_template', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(this.props.rawValue),
    })
      .then((r) => r.json())
      .then((r) => {
        console.log(r);
        if (r.error) {
          window.newAlert(`Error during template apply: ${r.error}`);
        } else {
          window.newAlert('Index template has been applied !');
        }
      });
  };

  showTemplates = () => {
    fetch('/bo/api/elastic/_template', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(this.props.rawValue),
    })
      .then((r) => r.json())
      .then((r) => {
        console.log(r);
        if (r.error) {
          window.newAlert(`Error while fetching templates: ${r.error}`);
        } else {
          window.popup(
            'Elasticsearch index template',
            (ok, cancel) => <ElasticsearchTemplate ok={ok} cancel={cancel} template={r.template} />,
            { __style: { width: '100%' } }
          );
        }
      });
  };

  render() {
    console.log('ES props:', this.props);
    return (
      <div className="row mb-3">
        <label className="col-sm-2 col-form-label"></label>
        <div className="col-sm-10">
          <div className="btn-group">
            <button
              className="btn btn-sm btn-success"
              style={{ marginRight: 0 }}
              type="button"
              onClick={this.checkConnection}
            >
              Check connection
            </button>
            <button
              className="btn btn-sm btn-success"
              style={{ marginRight: 0 }}
              type="button"
              onClick={this.fillVersion}
            >
              Fill cluster version
            </button>
            <button
              className="btn btn-sm btn-success"
              style={{ marginRight: 0 }}
              type="button"
              onClick={this.applyTemplate}
            >
              Manually apply index template
            </button>
            <button
              className="btn btn-sm btn-success"
              style={{ marginRight: 0 }}
              type="button"
              onClick={this.showTemplates}
            >
              Show index template
            </button>
          </div>
        </div>
      </div>
    );
  }
}

class ElasticsearchConnectionDiagnostic extends Component {
  render() {
    const works = !this.props.resp.search.error && !this.props.resp.version.error;
    const isOpenSearch = `${this.props.resp.distribution || ''}`
      .toLowerCase()
      .includes('opensearch');
    const product = isOpenSearch ? 'OpenSearch' : 'Elasticsearch';
    const configuredVersion = `${this.props.spec.version || ''}`.toLowerCase();
    // an opensearch cluster must either be auto detected or configured with its distribution,
    // otherwise its version number is handled like an old elasticsearch one
    const versionMisconfigured =
      isOpenSearch && configuredVersion !== '' && !configuredVersion.startsWith('opensearch');
    return (
      <>
        <div className="modal-body">
          {this.props.resp.version.error && (
            <>
              {product} version: <span className="badge bg-danger">data not available</span>
            </>
          )}
          {!this.props.resp.version.error && (
            <>
              {product} version: <span className="badge bg-success">{this.props.resp.version}</span>
            </>
          )}
          <br />
          {this.props.resp.search.error && (
            <>
              {product} search API: <span className="badge bg-danger">data not available</span>
            </>
          )}
          {!this.props.resp.search.error && (
            <>
              {product} search API:{' '}
              <span className="badge bg-success">{this.props.resp.search} docs</span>
            </>
          )}
          {works && !versionMisconfigured && (
            <p style={{ marginTop: 20 }}>Connection to the {product} cluster works fine !</p>
          )}

          {versionMisconfigured && (
            <p style={{ marginTop: 20 }}>
              This cluster is an OpenSearch cluster but the version of this exporter is set to{' '}
              <span className="badge bg-default">{this.props.spec.version}</span>, which otoroshi
              handles like an Elasticsearch one. Leave the version empty to let otoroshi detect it,
              or set it to{' '}
              <span className="badge bg-default">
                {this.props.resp.config_version || `opensearch-${this.props.resp.version}`}
              </span>
              .
            </p>
          )}
          {this.props.spec.applyTemplate && this.props.resp.version.error && (
            <p style={{ marginTop: 20 }}>
              Unable to access {product} version. Maybe you don't have the rights to access it. It's
              needed to automatically apply otoroshi index template
            </p>
          )}
          {this.props.resp.search.error && (
            <p style={{ marginTop: 20 }}>
              Unable to access {product} search api on your index{' '}
              <span className="badge bg-default">{this.props.spec.index}</span>. Maybe you don't
              have the rights to access it.
            </p>
          )}
        </div>
        <div className="modal-footer">
          <button type="button" className="btn btn-danger" onClick={this.props.cancel}>
            Close
          </button>
        </div>
      </>
    );
  }
}

class ElasticsearchTemplate extends Component {
  render() {
    return (
      <>
        <div className="modal-body">
          <pre>
            <code
              dangerouslySetInnerHTML={{
                __html: this.props.template.replace(/\\n/g, '<br/>').replace(/\\"/g, '"'),
              }}
            ></code>
          </pre>
        </div>
        <div className="modal-footer">
          <button type="button" className="btn btn-danger" onClick={this.props.cancel}>
            Close
          </button>
        </div>
      </>
    );
  }
}
