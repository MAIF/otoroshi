import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table } from '../components/inputs';
import { JwtVerifier } from '../components/JwtVerifier';
import { Button } from '../components/Button';
import { ClassifiedForms } from '../forms';
import { FeedbackButton } from './RouteDesigner/FeedbackButton';
import PageTitle from '../components/PageTitle';
import { Dropdown } from '../components/Dropdown';
import { YAMLExportButton } from '../components/exporters/YAMLButton';
import { JsonExportButton } from '../components/exporters/JSONButton';
import { SquareButton } from '../components/SquareButton';
import { ENTITIES, FormSelector } from '../components/FormSelector';

export class JwtVerifiersPage extends Component {
  state = {
    showWizard: false,
  };

  columns = [
    { title: 'Name', content: (item) => item.name },
    { title: 'Description', content: (item) => item.desc },
    { title: 'Strategy', content: (item) => item.strategy?.type },
  ];

  componentDidMount() {
    this.setTitle('Global Jwt Verifiers');
  }

  setTitle = (title, onPress, verifier) => {
    this.props.setTitle(() => {
      const pathname = window.location.href;
      const isEditPage = pathname.includes('edit');

      const SaveButton = isEditPage ? (
        <FeedbackButton
          className="ms-2"
          onPress={onPress}
          text="Save JWT verifier"
          icon={() => <i className="fas fa-paper-plane" />}
        />
      ) : null;

      return (
        <PageTitle title={title}>
          {isEditPage && (
            <Dropdown>
              <YAMLExportButton value={verifier} />
              <JsonExportButton value={verifier} />
              <SquareButton
                level="danger"
                onClick={() => {
                  const what = window.location.pathname.split('/')[3];
                  const id = window.location.pathname.split('/')[5];
                  window.newConfirm('Delete this verifier ?').then((ok) => {
                    if (ok) {
                      BackOfficeServices.deleteJwtVerifier(id).then(() => {
                        history.push('/' + what);
                      });
                    }
                  });
                }}
                icon="fa-trash"
                text="Delete"
              />
            </Dropdown>
          )}
          {SaveButton}
        </PageTitle>
      );
    });
  };

  gotoVerifier = (verifier) => {
    window.location = `/bo/dashboard/jwt-verifiers/edit/${verifier.id}`;
  };

  render() {
    const { showWizard } = this.state;

    const JwtVerifierWizard = ClassifiedForms.wizards.JwtVerifierWizard;

    return (
      <div>
        {showWizard && (
          <JwtVerifierWizard
            hide={() => this.setState({ showWizard: false })}
            disableSelectMode={true}
          />
        )}
        <Table
          parentProps={{
            ...this.props,
            setTitle: this.setTitle,
          }}
          selfUrl="jwt-verifiers"
          defaultTitle="Global Jwt Verifiers"
          itemName="Jwt Verifier"
          kubernetesKind="JwtVerifier"
          export={false}
          displayTrash={false}
          newForm={true}
          showActions={true}
          hideAllActions={true}
          showLink={false}
          rowNavigation={true}
          firstSort={0}
          columns={this.columns}
          formComponent={JwtVerifier}
          navigateTo={this.gotoVerifier}
          defaultValue={BackOfficeServices.createNewJwtVerifier}
          fetchItems={BackOfficeServices.findAllJwtVerifiers}
          updateItem={BackOfficeServices.updateJwtVerifier}
          deleteItem={BackOfficeServices.deleteJwtVerifier}
          createItem={BackOfficeServices.createJwtVerifier}
          itemUrl={(i) => `/bo/dashboard/jwt-verifiers/edit/${i.id}`}
          style={{ paddingTop: 0 }}
          extractKey={(item) => item.id}
          formPassProps={{
            global: true,
            showHeader: window.location.href.includes('edit'),
          }}
          injectBottomBar={({ closeEditForm, state, setState, buttons }) => {
            return <div className="d-flex align-items-center justify-content-end">
              {buttons || null}
              <FormSelector
                onChange={showAdvancedForm => setState({ showAdvancedForm })}
                entity={ENTITIES.JWT_VERIFIERS}
              />
              <Button type="danger" className='btn-sm ms-1' onClick={closeEditForm}>
                <i className="fas fa-times" /> Cancel
              </Button>
            </div>
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
