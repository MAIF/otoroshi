import React, { Component } from 'react';
import { SelectInput } from './inputs';
import { nextClient } from '../services/BackOfficeServices';

// Edits ApiKey.apiRef: which api plan this apikey consumes. The plan select is fed by the selected
// api, so the two always stay consistent.
export class ApikeyApiRefField extends Component {
  state = { apis: [] };

  componentDidMount() {
    nextClient
      .forEntityNext(nextClient.ENTITIES.APIS)
      .findAll()
      .then((apis) => this.setState({ apis: Array.isArray(apis) ? apis : [] }));
  }

  ref = () => this.props.value || {};

  render() {
    const ref = this.ref();
    const currentApi = this.state.apis.find((api) => api.id === ref.api);
    const plans = currentApi?.plans || [];
    return (
      <>
        <SelectInput
          isClearable
          label="API"
          value={ref.api}
          help="The api this apikey is a consumer of. The plugins of the selected plan will run for every call made with this apikey."
          possibleValues={this.state.apis.map((api) => ({
            value: api.id,
            label: `${api.name} (${api.version})`,
          }))}
          onChange={(api) => {
            // changing the api invalidates the plan
            if (!api) this.props.onChange(null);
            else this.props.onChange({ api, plan: '', sub: ref.sub || '' });
          }}
        />
        {ref.api && (
          <SelectInput
            key={ref.api}
            label="Plan"
            isClearable
            value={ref.plan}
            help={plans.length === 0 ? 'This api has no plan yet' : 'The plan of the api'}
            possibleValues={plans.map((plan) => ({ value: plan.id, label: plan.name }))}
            onChange={(plan) => this.props.onChange({ ...ref, plan: plan || '' })}
          />
        )}
      </>
    );
  }
}

// Read only: what an apikey has to do with a shared credential. A shared credential (ApiKey.sharedCredential) shows
// its owner and its alias keys, one per subscription sharing it. An alias key (ApiRef.credential) shows the credential
// it is called through. Nothing is shown on the other apikeys.
export class ApikeySharedCredentialInfo extends Component {
  state = { loading: false, aliases: [], apis: [], subscriptions: [] };

  componentDidMount() {
    this.load();
  }

  componentDidUpdate(prevProps) {
    if ((prevProps.rawValue || {}).clientId !== (this.props.rawValue || {}).clientId) this.load();
  }

  load = () => {
    const apikey = this.props.rawValue || {};
    if (!apikey.sharedCredential || !apikey.clientId) return;
    const list = (entity) =>
      nextClient
        .forEntityNext(entity)
        .findAll()
        .then((items) => (Array.isArray(items) ? items : []))
        .catch(() => []);
    this.setState({ loading: true });
    Promise.all([
      list(nextClient.ENTITIES.APIKEYS),
      list(nextClient.ENTITIES.APIS),
      list(nextClient.ENTITIES.API_SUBSCRIPTIONS),
    ]).then(([apikeys, apis, subscriptions]) =>
      this.setState({
        loading: false,
        aliases: apikeys.filter((key) => key.apiRef && key.apiRef.credential === apikey.clientId),
        apis,
        subscriptions,
      })
    );
  };

  row = (label, content) => (
    <div className="row mb-3">
      <label className="col-sm-2 col-form-label">{label}</label>
      <div
        className="col-sm-10"
        style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center' }}
      >
        {content}
      </div>
    </div>
  );

  // a visible link: the links of the forms have the color of the text
  linkStyle = { color: 'var(--color-primary)', textDecoration: 'underline', cursor: 'pointer' };

  keyLink = (clientId) => (
    <a href={`/bo/dashboard/apikeys/edit/${clientId}`} style={this.linkStyle}>
      {clientId}
    </a>
  );

  aliases = () => {
    if (this.state.loading) return <span>Loading ...</span>;
    if (this.state.aliases.length === 0)
      return <span>No subscription shares this credential yet.</span>;
    const cell = {
      padding: '6px 8px',
      borderBottom: '1px solid var(--border-color)',
      textAlign: 'left',
    };
    return (
      // no bootstrap table here: its white background is unreadable with the dark theme
      <table style={{ width: '100%', color: 'var(--text)' }}>
        <thead>
          <tr>
            {['API', 'Plan', 'Subscription', 'Alias key', 'Status'].map((title) => (
              <th key={title} style={{ ...cell, color: 'var(--text-muted)', fontWeight: 'normal' }}>
                {title}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {this.state.aliases.map((alias) => {
            const api = this.state.apis.find((a) => a.id === alias.apiRef.api);
            const plan = ((api || {}).plans || []).find((p) => p.id === alias.apiRef.plan);
            const subscription = this.state.subscriptions.find((s) => s.id === alias.apiRef.sub);
            // an alias only serves while its subscription shares the credential and lists it
            const refs = (subscription || {}).token_refs || [];
            const minted =
              !!subscription &&
              refs.some(
                (ref) => ref && ref.shared === true && ref.apikey === alias.apiRef.credential
              ) &&
              refs.some((ref) => ref && ref.shared !== true && ref.apikey === alias.clientId);
            return (
              <tr key={alias.clientId}>
                <td style={cell}>
                  {api ? (
                    <a href={`/bo/dashboard/apis/${api.id}`} style={this.linkStyle}>
                      {api.name}
                    </a>
                  ) : (
                    alias.apiRef.api
                  )}
                </td>
                <td style={cell}>{plan ? plan.name : alias.apiRef.plan}</td>
                <td style={cell}>{subscription ? subscription.name : alias.apiRef.sub}</td>
                <td style={cell}>{this.keyLink(alias.clientId)}</td>
                <td style={cell}>
                  {!minted && <span className="badge bg-warning">not served</span>}
                  {minted && alias.enabled && <span className="badge bg-success">enabled</span>}
                  {minted && !alias.enabled && <span className="badge bg-danger">disabled</span>}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    );
  };

  render() {
    const apikey = this.props.rawValue || {};
    const credential = (apikey.apiRef || {}).credential;
    if (apikey.sharedCredential) {
      return (
        <>
          {this.row(
            'Shared credential',
            <span>
              This apikey is the shared credential of{' '}
              <strong>{apikey.sharedCredential.owner}</strong>. It is authorized on nothing by
              itself: the consumer calls each api below with its client id and its secret, through
              the alias key of its subscription. Disabled, expired or deleted, it stops every one of
              them.
            </span>
          )}
          {this.row('Shared by', this.aliases())}
        </>
      );
    }
    if (credential) {
      return this.row(
        'Alias key',
        <span>
          This apikey is an alias of the shared credential {this.keyLink(credential)}: the consumer
          calls with the client id and the secret of that credential, and is identified as this
          apikey, with the quotas and the plugins of its plan. Called with its own client id and
          secret, it is refused.
        </span>
      );
    }
    return null;
  }
}
