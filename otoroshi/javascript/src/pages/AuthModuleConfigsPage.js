import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import faker from 'faker';
import { AuthModuleConfig, Oauth2ModuleConfig } from '../components/AuthModuleConfig';
import { Button } from '../components/Button';
import { ClassifiedForms } from '../forms';

export class AuthModuleConfigsPage extends Component {
  state = {
    showWizard: false,
  };

  columns = [
    { title: 'Name', filterId: 'name', content: (item) => item.name },
    { title: 'Description', filterId: 'desc', content: (item) => item.desc },
  ];

  componentDidMount() {
    this.props.setTitle(`Global auth. configs`);
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
        <Table
          parentProps={this.props}
          selfUrl="auth-configs"
          defaultTitle="All Global auth. configs"
          defaultValue={BackOfficeServices.createNewAuthConfig}
          _defaultValue={() => {
            const defv = {
              ...Oauth2ModuleConfig.defaultConfig,
              id: faker.random.alphaNumeric(64),
              name: 'New auth. config',
              desc: 'A auth. config',
              type: 'oauth2',
            };
            return defv;
          }}
          itemName="Auth. config"
          formSchema={this.formSchema}
          formFlow={this.formFlow}
          columns={this.columns}
          stayAfterSave={true}
          fetchItems={(paginationState) =>
            BackOfficeServices.findAllAuthConfigs({
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
          kubernetesKind="AuthModule"
          injectToolbar={(s, ss) => {
            return (
              <div className="mb-3 btnsService">
                <div className="displayGroupBtn">
                  <button
                    className="btn btn-primary"
                    type="button"
                    style={{ marginRight: 20 }}
                    onClick={(e) => this.duplicate(s, ss, e)}>
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
              style={{ _backgroundColor: '#f9b000', _borderColor: '#f9b000', marginLeft: 5 }}>
              <i className="fas fa-hat-wizard" /> Create with wizard
            </Button>
          )}
        />
      </div>
    );
  }
}
