import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import faker from 'faker';
import { AuthModuleConfig, Oauth2ModuleConfig } from '../components/AuthModuleConfig';

export class AuthModuleConfigsPage extends Component {
  columns = [
    { title: 'Name', content: (item) => item.name },
    { title: 'Description', content: (item) => item.desc },
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
    return (
      <div>
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
          fetchItems={BackOfficeServices.findAllAuthConfigs}
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
              <div className="btn__group-fixed--right mb-20 grid-template-col-xs-up__1fr-5fr">
                <div className="">
                  <button
                    className="btn btn-info"
                    type="button"
                    onClick={(e) => this.duplicate(s, ss, e)}>
                    <i className="far fa-copy" aria-hidden="true" />
                  </button>
                </div>
              </div>
            );
          }}
        />
      </div>
    );
  }
}
