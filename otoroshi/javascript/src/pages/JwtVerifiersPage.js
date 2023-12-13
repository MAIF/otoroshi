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
    { title: 'Name', filterId: 'name', content: (item) => item.name },
    { title: 'Description', filterId: 'desc', content: (item) => item.desc },
    { title: 'Strategy', filterId: 'strategy.type', content: (item) => item.strategy?.type },
  ];

  componentDidMount() {
    this.setTitle('Jwt verifiers');
  }

  setTitle = (title, onPress, verifier) => {
    this.props.setTitle(() => {
      const pathname = window.location.href;
      const isEditPage = pathname.includes('/edit');

      const SaveButton = isEditPage ? (
        <FeedbackButton
          className="ms-2"
          onPress={onPress}
          text="Save JWT verifier"
          icon={() => <i className="fas fa-paper-plane" />}
        />
      ) : null;

      return (
        <PageTitle title={title} {...this.props}>
          {isEditPage && (
            <Dropdown>
              <YAMLExportButton value={verifier} entityKind="JwtVerifier" />
              <JsonExportButton value={verifier} entityKind="JwtVerifier" />
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
          itemName="Jwt verifier"
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
          fetchItems={(paginationState) => {
            return BackOfficeServices.findAllJwtVerifiers({
              ...paginationState,
              fields: ['id', 'name', 'desc', 'strategy.type'],
            });
          }}
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
            return (
              <div className="d-flex align-items-center justify-content-end">
                <Button type="danger" className="btn-sm me-1" onClick={closeEditForm}>
                  <i className="fas fa-times" /> Cancel
                </Button>
                {buttons || null}
                {state.showEditForm && (
                  <FormSelector
                    onChange={(showAdvancedForm) => setState({ showAdvancedForm })}
                    entity={ENTITIES.JWT_VERIFIERS}
                  />
                )}
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
