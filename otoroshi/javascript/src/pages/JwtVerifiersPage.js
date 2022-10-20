import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import { JwtVerifier } from '../components/JwtVerifier';
import { Button } from '../components/Button';
import { ClassifiedForms } from '../forms';

export class JwtVerifiersPage extends Component {
  state = {
    showWizard: false // TODO - resert to false
  }

  columns = [
    { title: 'Name', content: (item) => item.name },
    { title: 'Description', content: (item) => item.description },
  ];

  componentDidMount() {
    this.props.setTitle(`Global Jwt Verifiers`);
  }

  gotoVerifier = (verifier) => {
    window.location = `/bo/dashboard/jwt-verifiers/edit/${verifier.id}`;
  };

  render() {
    const { showWizard } = this.state;

    const JwtVerifierWizard = ClassifiedForms.wizards.JwtVerifierWizard

    return (
      <div>
        {showWizard && <JwtVerifierWizard hide={() => this.setState({ showWizard: false })} />}
        <Table
          parentProps={this.props}
          selfUrl="jwt-verifiers"
          defaultTitle="All Global Jwt Verifiers"
          defaultValue={BackOfficeServices.createNewJwtVerifier}
          itemName="Jwt Verifier"
          formSchema={this.formSchema}
          formFlow={this.formFlow}
          columns={this.columns}
          stayAfterSave={true}
          fetchItems={BackOfficeServices.findAllJwtVerifiers}
          updateItem={BackOfficeServices.updateJwtVerifier}
          deleteItem={BackOfficeServices.deleteJwtVerifier}
          createItem={BackOfficeServices.createJwtVerifier}
          navigateTo={this.gotoVerifier}
          itemUrl={(i) => `/bo/dashboard/jwt-verifiers/edit/${i.id}`}
          showActions={true}
          showLink={false}
          rowNavigation={true}
          firstSort={0}
          extractKey={(item) => item.id}
          formComponent={JwtVerifier}
          formPassProps={{ global: true }}
          export={true}
          kubernetesKind="JwtVerifier"
          injectTopBar={() => (
            <Button
              type="primary"
              onClick={() => {
                this.setState({
                  showWizard: true
                })
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
