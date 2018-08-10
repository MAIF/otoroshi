import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import faker from 'faker';
import { AuthModuleConfig } from '../components/AuthModuleConfig';

export class AuthModuleConfigsPage extends Component {

  columns = [
    { title: 'Name', content: item => item.name },
    { title: 'Description', content: item => item.desc },
  ];

  componentDidMount() {
    this.props.setTitle(`Global auth. configs`);
  }

  gotoConfig = config => {
    this.props.history.push({
      pathname: `auth-configs/edit/${config.id}`,
    });
  };

  render() {
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="auth-configs"
          defaultTitle="All Global auth. configs"
          defaultValue={() => {
            const defv = {
              ...AuthModuleConfig.defaultConfig,
              id: faker.random.alphaNumeric(64),
              name: 'New auth. config',
              desc: 'A auth. config',
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
          itemUrl={i => `/bo/dashboard/auth-configs/edit/${i.id}`}
          showActions={true}
          showLink={true}
          rowNavigation={true}
          firstSort={0}
          extractKey={item => item.id}
          formComponent={AuthModuleConfig}
        />
      </div>
    );
  }
}
