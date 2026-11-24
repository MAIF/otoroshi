import React, { Component } from 'react';
import { ReactSelectOverride } from './inputs/ReactSelectOverride';
import { BooleanInput } from './inputs/BooleanInput';
import { Help } from './inputs/Help';
import { JsonObjectAsCodeInput } from './inputs/CodeInput';

const LEVELS = [
  { value: 'rw', label: 'Read and write' },
  { value: 'r', label: 'Read only' },
  { value: 'not', label: 'No access' },
];

// "team:rw" -> { value: 'team', level: 'rw' }, a value without level being read and write like the backend does
function splitAccess(raw) {
  const str = (raw || '').trim().toLowerCase();
  const idx = str.lastIndexOf(':');
  if (idx < 0) {
    return { value: str, level: 'rw' };
  }
  const level = str.substring(idx + 1);
  return {
    value: str.substring(0, idx),
    level:
      level === 'not'
        ? 'not'
        : level.includes('r') && level.includes('w')
          ? 'rw'
          : level.includes('r')
            ? 'r'
            : 'not',
  };
}

function joinAccess(value, level) {
  return `${(value || '').trim().toLowerCase()}:${level}`;
}

// a right without kinds gives access to every kind of entity
function restrictsKinds(right) {
  return (
    right.authorized_entities_read !== undefined || right.authorized_entities_write !== undefined
  );
}

function option(value, label) {
  return { value, label: label || value };
}

// the selects of the backoffice only show a value present in their options: a pattern typed by the user is added to
// them
function withValues(options, values) {
  const missing = values.filter((v) => v && !options.some((o) => o.value === v));
  return [...options, ...missing.map((v) => option(v))];
}

// a creatable select answers the option, created or not
function selectedValue(e) {
  return e && typeof e === 'object' ? e.value : e;
}

class Field extends Component {
  render() {
    return (
      <div className="row mb-3">
        <label className="col-xs-12 col-sm-2 col-form-label">
          {this.props.label} {this.props.help && <Help text={this.props.help} />}
        </label>
        <div className="col-sm-10">{this.props.children}</div>
      </div>
    );
  }
}

export class UserRightsEditor extends Component {
  state = {
    tenants: [],
    teams: [],
    kinds: [],
    asJson: false,
  };

  componentDidMount() {
    const get = (url) =>
      fetch(url, { method: 'GET', credentials: 'include', headers: { Accept: 'application/json' } })
        .then((r) => (r.status === 200 ? r.json() : null))
        .catch(() => null);
    Promise.all([
      get('/bo/api/proxy/apis/organize.otoroshi.io/v1/tenants'),
      get('/bo/api/proxy/apis/organize.otoroshi.io/v1/teams'),
      get('/bo/api/proxy/apis/entities?schema=false'),
    ]).then(([tenants, teams, entities]) => {
      const resources = (entities && entities.resources) || [];
      const groups = [...new Set(resources.map((r) => r.group))].sort();
      const kinds = [
        option('*', 'Every kind of entity'),
        ...groups.map((g) => option(`${g}/*`, `Every kind of ${g}`)),
        ...resources
          .map((r) =>
            option(`${r.group}/${r.plural_name}`, `${r.kind} (${r.group}/${r.plural_name})`)
          )
          .sort((a, b) => a.label.localeCompare(b.label)),
      ];
      this.setState({
        tenants: Array.isArray(tenants) ? tenants : [],
        teams: Array.isArray(teams) ? teams : [],
        kinds,
      });
    });
  }

  rights = () => (Array.isArray(this.props.value) ? this.props.value : []);

  changeRight = (index, f) => {
    this.props.onChange(this.rights().map((r, i) => (i === index ? f({ ...r }) : r)));
  };

  removeRight = (index) => {
    this.props.onChange(this.rights().filter((r, i) => i !== index));
  };

  addRight = () => {
    this.props.onChange([...this.rights(), { tenant: 'default:r', teams: ['default:r'] }]);
  };

  tenantOptions = (current) =>
    withValues(
      [
        option('*', 'Every organization'),
        ...this.state.tenants.map((t) => option(t.id, `${t.name} (${t.id})`)),
      ],
      [current]
    );

  teamOptions = (tenant, current) =>
    withValues(
      [
        option('*', 'Every team'),
        ...this.state.teams
          .filter(
            (t) => tenant === '*' || tenant.includes('*') || (t.tenant || 'default') === tenant
          )
          .map((t) => option(t.id, `${t.name} (${t.id})`)),
      ],
      [current]
    );

  renderKinds = (index, right, field, label, help) => {
    const values = right[field] || [];
    return (
      <Field label={label} help={help}>
        <ReactSelectOverride
          creatable
          isMulti
          value={values.map((v) => ({ value: v }))}
          options={withValues(this.state.kinds, values)}
          formatCreateLabel={(v) => `Use the kind "${v}"`}
          onChange={(e) =>
            this.changeRight(index, (r) => ({ ...r, [field]: (e || []).map(selectedValue) }))
          }
        />
      </Field>
    );
  };

  renderRight = (right, index) => {
    const tenant = splitAccess(right.tenant);
    const teams = (right.teams || []).map(splitAccess);
    const kinds = restrictsKinds(right);
    const setTeams = (r, f) => ({
      ...r,
      teams: f(teams).map((t) => joinAccess(t.value, t.level)),
    });
    return (
      <div key={index} className="sub-container sub-container__bg-color mb-3">
        <Field label="Organization" help="The organization the right applies to, * for all of them">
          <div className="d-flex" style={{ gap: 8 }}>
            <div style={{ flex: 3 }}>
              <ReactSelectOverride
                creatable
                value={tenant.value}
                options={this.tenantOptions(tenant.value)}
                formatCreateLabel={(v) => `Use the pattern "${v}"`}
                onChange={(e) =>
                  this.changeRight(index, (r) => ({
                    ...r,
                    tenant: joinAccess(selectedValue(e), tenant.level),
                  }))
                }
              />
            </div>
            <div style={{ flex: 2 }}>
              <ReactSelectOverride
                value={tenant.level}
                options={LEVELS}
                onChange={(l) =>
                  this.changeRight(index, (r) => ({ ...r, tenant: joinAccess(tenant.value, l) }))
                }
              />
            </div>
          </div>
        </Field>
        <Field
          label="Teams"
          help="The teams of the organization the right applies to, * for all of them"
        >
          {teams.map((team, ti) => (
            <div key={ti} className="d-flex mb-2" style={{ gap: 8 }}>
              <div style={{ flex: 3 }}>
                <ReactSelectOverride
                  creatable
                  value={team.value}
                  options={this.teamOptions(tenant.value, team.value)}
                  formatCreateLabel={(v) => `Use the pattern "${v}"`}
                  onChange={(e) =>
                    this.changeRight(index, (r) =>
                      setTeams(r, (ts) =>
                        ts.map((t, i) => (i === ti ? { ...t, value: selectedValue(e) } : t))
                      )
                    )
                  }
                />
              </div>
              <div style={{ flex: 2 }}>
                <ReactSelectOverride
                  value={team.level}
                  options={LEVELS}
                  onChange={(l) =>
                    this.changeRight(index, (r) =>
                      setTeams(r, (ts) => ts.map((t, i) => (i === ti ? { ...t, level: l } : t)))
                    )
                  }
                />
              </div>
              <button
                type="button"
                className="btn btn-sm btn-danger"
                title="Remove this team"
                onClick={() =>
                  this.changeRight(index, (r) => setTeams(r, (ts) => ts.filter((t, i) => i !== ti)))
                }
              >
                <i className="fas fa-trash" />
              </button>
            </div>
          ))}
          <button
            type="button"
            className="btn btn-sm btn-primary"
            onClick={() =>
              this.changeRight(index, (r) => ({ ...r, teams: [...(r.teams || []), 'default:r'] }))
            }
          >
            <i className="fas fa-plus" /> Add a team
          </button>
        </Field>
        <BooleanInput
          label="Some kinds only"
          help="Limit the right to some kinds of entities. Otherwise, it gives access to every kind"
          value={kinds}
          onChange={(v) =>
            this.changeRight(index, (r) => {
              const { authorized_entities_read, authorized_entities_write, ...rest } = r;
              return v
                ? { ...rest, authorized_entities_read: ['*'], authorized_entities_write: [] }
                : rest;
            })
          }
        />
        {kinds &&
          this.renderKinds(
            index,
            right,
            'authorized_entities_read',
            'Readable kinds',
            'The kinds of entities the right can read'
          )}
        {kinds &&
          this.renderKinds(
            index,
            right,
            'authorized_entities_write',
            'Writable kinds',
            'The kinds of entities the right can write. They are readable too'
          )}
        <div className="d-flex justify-content-end">
          <button
            type="button"
            className="btn btn-sm btn-danger"
            onClick={() => this.removeRight(index)}
          >
            <i className="fas fa-trash" /> Remove this right
          </button>
        </div>
      </div>
    );
  };

  render() {
    const rights = this.rights();
    return (
      <div>
        {!this.props.noJsonToggle && (
          <Field label={this.props.label || 'Rights'} help={this.props.help}>
            <div className="d-flex justify-content-end">
              <button
                type="button"
                className="btn btn-sm btn-quiet"
                onClick={() => this.setState({ asJson: !this.state.asJson })}
              >
                {this.state.asJson ? 'Edit with the form' : 'Edit as json'}
              </button>
            </div>
          </Field>
        )}
        {this.state.asJson && (
          <JsonObjectAsCodeInput
            label=""
            height="300px"
            value={rights}
            onChange={(v) => this.props.onChange(v)}
          />
        )}
        {!this.state.asJson && (
          <>
            {rights.length === 0 && (
              <Field label="">
                <p className="mt-2">No rights: the user cannot read nor write anything.</p>
              </Field>
            )}
            {rights.map(this.renderRight)}
            <div className="d-flex justify-content-end">
              <button type="button" className="btn btn-sm btn-primary" onClick={this.addRight}>
                <i className="fas fa-plus" /> Add a right
              </button>
            </div>
          </>
        )}
      </div>
    );
  }
}

// the rights of some users by email, like the rightsOverride of the auth modules: the form edits the rights of each
// user, the raw json stays available
export class RightsOverrideEditor extends Component {
  state = {
    asJson: false,
    email: '',
  };

  value = () =>
    this.props.value && typeof this.props.value === 'object' && !Array.isArray(this.props.value)
      ? this.props.value
      : {};

  addUser = () => {
    const email = this.state.email.trim().toLowerCase();
    if (email && !this.value()[email]) {
      this.props.onChange({
        ...this.value(),
        [email]: [{ tenant: 'default:r', teams: ['default:r'] }],
      });
    }
    this.setState({ email: '' });
  };

  removeUser = (email) => {
    const { [email]: removed, ...rest } = this.value();
    this.props.onChange(rest);
  };

  render() {
    const value = this.value();
    const emails = Object.keys(value);
    return (
      <div>
        <Field label={this.props.label || 'Rights override'} help={this.props.help}>
          <div className="d-flex justify-content-end">
            <button
              type="button"
              className="btn btn-sm btn-quiet"
              onClick={() => this.setState({ asJson: !this.state.asJson })}
            >
              {this.state.asJson ? 'Edit with the form' : 'Edit as json'}
            </button>
          </div>
        </Field>
        {this.state.asJson && (
          <JsonObjectAsCodeInput
            label=""
            mode="json"
            height="300px"
            value={value}
            onChange={(v) => this.props.onChange(v)}
            example={{
              'jane.doe@oto.tools': [{ tenant: '*:r', teams: ['*:r'] }],
            }}
          />
        )}
        {!this.state.asJson && (
          <>
            {emails.map((email) => (
              <div key={email} className="sub-container sub-container__bg-color mb-3">
                <div className="d-flex align-items-center justify-content-between mb-2">
                  <strong>{email}</strong>
                  <button
                    type="button"
                    className="btn btn-sm btn-danger"
                    onClick={() => this.removeUser(email)}
                  >
                    <i className="fas fa-trash" /> Remove this user
                  </button>
                </div>
                <UserRightsEditor
                  noJsonToggle
                  label="Rights"
                  value={value[email]}
                  onChange={(rights) => this.props.onChange({ ...value, [email]: rights })}
                />
              </div>
            ))}
            <Field label="Add a user">
              <div className="d-flex" style={{ gap: 8 }}>
                <input
                  type="text"
                  className="form-control"
                  placeholder="jane.doe@oto.tools"
                  value={this.state.email}
                  onChange={(e) => this.setState({ email: e.target.value })}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      this.addUser();
                    }
                  }}
                />
                <button
                  type="button"
                  className="btn btn-sm btn-primary"
                  style={{ whiteSpace: 'nowrap' }}
                  onClick={this.addUser}
                >
                  <i className="fas fa-plus" /> Add
                </button>
              </div>
            </Field>
          </>
        )}
      </div>
    );
  }
}
