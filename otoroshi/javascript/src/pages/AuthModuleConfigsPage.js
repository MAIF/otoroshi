import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import faker from 'faker';
import { AuthModuleConfig, Oauth2ModuleConfig } from '../components/AuthModuleConfig';
import { Button } from '../components/Button';
import { ClassifiedForms } from '../forms';
import InfoCollapse from '../components/InfoCollapse';

export class AuthModuleConfigsPage extends Component {
  state = {
    showWizard: false,
  };

  columns = [
    { title: 'Name', filterId: 'name', content: (item) => item.name },
    { title: 'Description', filterId: 'desc', content: (item) => item.desc },
  ];

  componentDidMount() {
    this.props.setTitle(`Authentication modules`);
  }

  gotoConfig = (config) => {
    this.props.history.push({
      pathname: `auth-configs/edit/${config.id}`,
    });
  };

  duplicate = (s, ss, e) => {
    if (e && e.preventDefault) e.preventDefault();
    window.newConfirm(`Are you sure you want to duplicate ${s.currentItem.name} ?`).then((dup) => {
      if (dup) {
        BackOfficeServices.createNewAuthConfig().then((auth) => {
          const newModule = { ...s.currentItem };
          newModule.id = auth.id;
          newModule.name = newModule.name + ' (duplicated)';
          ss({ currentItem: newModule, showAddForm: true, showEditForm: false });
          window.history.replaceState({}, '', `/bo/dashboard/auth-configs/add/${newModule.id}`);
        });
      }
    });
  };

  render() {
    const { showWizard } = this.state;

    const AuthenticationWizard = ClassifiedForms.wizards.AuthenticationWizard;

    return (
      <div>
        {showWizard && (
          <AuthenticationWizard
            hide={() => this.setState({ showWizard: false })}
            disableSelectMode={true}
          />
        )}
        <InfoCollapse title="What is an Authentication Module?">
          <p>
            An Authentication Module lets you <strong>secure the Otoroshi admin UI, your HTTP Routes, and your APIs</strong> using
            either a built-in in-memory user store or an external identity provider.
          </p>
          <p>
            Otoroshi supports a wide range of protocols and providers out of the box:
          </p>
          <ul>
            <li><strong>In-memory</strong> — a simple, built-in user directory for quick setups and testing.</li>
            <li><strong>LDAP</strong> — connect to your enterprise directory (Active Directory, OpenLDAP, etc.).</li>
            <li><strong>OAuth2 / OpenID Connect</strong> — integrate with providers like Keycloak, Auth0, Google, GitHub, or any OIDC-compliant server.</li>
            <li><strong>SAML 2.0</strong> — connect to enterprise identity providers like PingFederate, ADFS, or Okta.</li>
          </ul>
          <p>
            Beyond simple authentication, modules unlock powerful capabilities:
          </p>
          <ul>
            <li><strong>Single Sign-On (SSO)</strong> — share one module across multiple HTTP Routes and APIs so users authenticate once and access everything seamlessly.</li>
            <li><strong>Cluster-wide</strong> — sessions are replicated across all nodes in the cluster, so authentication works consistently in multi-node deployments.</li>
            <li><strong>Session administration</strong> — view, monitor, and revoke active user sessions from the admin UI.</li>
            <li><strong>Runtime customization</strong> — modify module configuration on the fly without restart or redeployment.</li>
          </ul>
        </InfoCollapse>
        <Table
          parentProps={this.props}
          selfUrl="auth-configs"
          defaultTitle="All Global auth. modules"
          defaultValue={BackOfficeServices.createNewAuthConfig}
          _defaultValue={() => {
            const defv = {
              ...Oauth2ModuleConfig.defaultConfig,
              id: faker.random.alphaNumeric(64),
              name: 'New auth. module',
              desc: 'A auth. module',
              type: 'oauth2',
            };
            return defv;
          }}
          itemName="Authentication module"
          formSchema={this.formSchema}
          formFlow={this.formFlow}
          columns={this.columns}
          stayAfterSave={true}
          fetchItems={(paginationState) =>
            BackOfficeServices.findAllAuthConfigs({
              // findAuthConfigs
              ...paginationState,
              fields: ['id', 'name', 'desc'],
            })
          }
          updateItem={BackOfficeServices.updateAuthConfig}
          deleteItem={BackOfficeServices.deleteAuthConfig}
          createItem={BackOfficeServices.createAuthConfig}
          navigateTo={this.gotoConfig}
          itemUrl={(i) => `/bo/dashboard/auth-configs/edit/${i.id}`}
          showActions={true}
          showLink={false}
          rowNavigation={true}
          firstSort={0}
          extractKey={(item) => item.id}
          formComponent={AuthModuleConfig}
          export={true}
          kubernetesKind="security.otoroshi.io/AuthModule"
          injectToolbar={(s, ss) => {
            return (
              <div className="mb-3 btnsService">
                <div className="displayGroupBtn">
                  <button
                    className="btn btn-primary"
                    type="button"
                    title="Duplicate auth. module"
                    style={{ marginRight: 20 }}
                    onClick={(e) => this.duplicate(s, ss, e)}
                  >
                    <i className="far fa-copy" aria-hidden="true" />
                  </button>
                </div>
              </div>
            );
          }}
          injectTopBar={() => (
            <Button
              type="primary"
              onClick={() => {
                this.setState({
                  showWizard: true,
                });
              }}
              className="btn-sm"
              style={{
                _backgroundColor: 'var(--color-primary)',
                _borderColor: 'var(--color-primary)',
                marginLeft: 5,
              }}
            >
              <i className="fas fa-hat-wizard" /> Create with wizard
            </Button>
          )}
        />
      </div>
    );
  }
}
