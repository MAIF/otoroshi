import React, { Component } from 'react';
import { TextInput } from '../components/inputs';
import CodeInput from '../components/inputs/CodeInput';
import * as BackOfficeServices from '../services/BackOfficeServices';

export class PersonalAdminApikeyPage extends Component {
  state = {
    loading: true,
    data: null,
    error: null,
    showSecret: false,
  };

  componentDidMount() {
    this.props.setTitle('My admin apikey');
    this.load();
  }

  handle = (promise) => {
    this.setState({ loading: true });
    return promise.then(({ status, body }) => {
      if (status >= 400) {
        this.setState({ loading: false, error: body.error || 'error' });
      } else {
        this.setState({ loading: false, data: body, error: null });
      }
    });
  };

  load = () => this.handle(BackOfficeServices.fetchPersonalAdminApikey());

  create = () => this.handle(BackOfficeServices.createPersonalAdminApikey());

  rotate = () => {
    window
      .newConfirm(
        'Regenerate the secret of your admin apikey? Every tool using the current secret will stop working'
      )
      .then((ok) => {
        if (ok) {
          this.handle(BackOfficeServices.rotatePersonalAdminApikey()).then(() =>
            this.setState({ showSecret: true })
          );
        }
      });
  };

  delete = () => {
    window
      .newConfirm(
        'Delete your admin apikey? Every tool using it will stop working, a new one will have new credentials'
      )
      .then((ok) => {
        if (ok) {
          BackOfficeServices.deletePersonalAdminApikey().then(() => this.load());
        }
      });
  };

  renderApikey(data) {
    const { apikey } = data;
    const port = window.location.port ? ':' + window.location.port : '';
    const curl = [
      `curl '${window.location.protocol}//${data.adminApiHost}${port}/apis/proxy.otoroshi.io/v1/routes' \\`,
      `  -H '${data.clientIdHeader}: ${apikey.clientId}' \\`,
      `  -H '${data.clientSecretHeader}: ${this.state.showSecret ? apikey.clientSecret : '<client secret>'}'`,
    ].join('\n');
    return (
      <>
        <TextInput label="Client id" value={apikey.clientId} disabled onChange={() => ''} />
        <TextInput
          label="Client secret"
          type={this.state.showSecret ? 'text' : 'password'}
          value={apikey.clientSecret}
          disabled
          onChange={() => ''}
          suffix={<i className={`fas ${this.state.showSecret ? 'fa-eye-slash' : 'fa-eye'}`} />}
          suffixStyle={{ cursor: 'pointer' }}
          suffixCb={() => this.setState({ showSecret: !this.state.showSecret })}
        />
        <CodeInput
          label="Usage"
          mode="text"
          height="80px"
          readOnly
          value={curl}
          onChange={() => ''}
        />
        <CodeInput
          label="Current rights"
          help="The rights of the apikey are the ones you have when you log in, read on each call: when your rights or your rights policy change, the apikey follows"
          mode="json"
          height="200px"
          readOnly
          value={JSON.stringify(data.rights || [], null, 2)}
          onChange={() => ''}
        />
        <div className="d-flex justify-content-end" style={{ gap: 8 }}>
          <button type="button" className="btn btn-sm btn-primary" onClick={this.rotate}>
            <i className="fas fa-sync" /> Regenerate the secret
          </button>
          <button type="button" className="btn btn-sm btn-danger" onClick={this.delete}>
            <i className="fas fa-trash" /> Delete the apikey
          </button>
        </div>
      </>
    );
  }

  render() {
    const { loading, data, error } = this.state;
    if (loading && !data) {
      return null;
    }
    return (
      <div className="sub-container sub-container__bg-color">
        {error && <div className="alert alert-danger">{error}</div>}
        {data && !data.allowed && (
          <p>
            {data.reason}
            {data.source === 'auth-module' && !data.policy && (
              <>
                {' '}
                (the rights policy of <code>{data.email}</code>)
              </>
            )}
            {data.apikey && ' Your current admin apikey has no rights until then.'}
          </p>
        )}
        {data && data.allowed && !data.apikey && (
          <div className="d-flex align-items-center justify-content-between">
            <p className="mb-0">
              You have no admin apikey yet. It lets you call the admin api of Otoroshi with the
              rights you have when you log in
              {data.source === 'local-admin'
                ? ' (your rights of local admin, capped by your rights policy if you have one).'
                : ' (the rights of your rights policy).'}
            </p>
            <button type="button" className="btn btn-sm btn-primary" onClick={this.create}>
              <i className="fas fa-key" /> Create my admin apikey
            </button>
          </div>
        )}
        {data && data.allowed && data.apikey && this.renderApikey(data)}
      </div>
    );
  }
}
