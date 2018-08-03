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
    this.props.setTitle(`Global OAuth2 configs`);
  }

  gotoConfig = config => {
    this.props.history.push({
      pathname: `oauth-configs/edit/${config.id}`,
    });
  };

  render() {
    return (
      <div>
        <Table
          parentProps={this.props}
          selfUrl="oauth-configs"
          defaultTitle="All Global OAuth2 configs"
          defaultValue={() => {
            const defv = {
              ...AuthModuleConfig.defaultConfig,
              id: faker.random.alphaNumeric(64),
              type: 'oauth2-global',
              name: 'New OAuth2 config',
              desc: 'A OAuth2 config',
            };
            return defv;
          }}
          itemName="OAuth2 config"
          formSchema={this.formSchema}
          formFlow={this.formFlow}
          columns={this.columns}
          stayAfterSave={true}
          fetchItems={BackOfficeServices.findAllOAuth2Configs}
          updateItem={BackOfficeServices.updateOAuth2Config}
          deleteItem={BackOfficeServices.deleteOAuth2Config}
          createItem={BackOfficeServices.createOAuth2Config}
          navigateTo={this.gotoConfig}
          itemUrl={i => `/bo/dashboard/oauth-configs/edit/${i.id}`}
          showActions={true}
          showLink={true}
          rowNavigation={false}
          firstSort={0}
          extractKey={item => item.id}
          formComponent={AuthModuleConfig}
        />
      </div>
    );
  }
}
