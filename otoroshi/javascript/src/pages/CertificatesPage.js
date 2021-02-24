import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import {
  Table,
  TextInput,
  TextareaInput,
  LabelInput,
  BooleanInput,
  ArrayInput,
  SelectInput,
  NumberInput,
  BiColumnBooleanInput,
} from '../components/inputs';
import moment from 'moment';
import faker from 'faker';

const RevocationReason = {
  VALID: { value: 'VALID', label: 'Valid - The certificate is not revoked' },
  UNSPECIFIED: { value: 'UNSPECIFIED', label: 'Unspecified - Can be used to revoke certificates for reasons other than the specific codes.' },
  KEY_COMPROMISE: { value: 'KEY_COMPROMISE', label: 'KeyCompromise - It is known or suspected that the subject\'s private key or other aspects have been compromised.' },
  CA_COMPROMISE: { value: 'CA_COMPROMISE', label: 'CACompromise - It is known or suspected that the subject\'s private key or other aspects have been compromised.' },
  AFFILIATION_CHANGED : { value: 'AFFILIATION_CHANGED', label: 'AffiliationChanged - The subject\'s name or other information in the certificate has been modified but there is no cause to suspect that the private key has been compromised.' },
  SUPERSEDED : { value: 'SUPERSEDED', label: 'Superseded - The certificate has been superseded but there is no cause to suspect that the private key has been compromised' },
  CESSATION_OF_OPERATION : { value: 'CESSATION_OF_OPERATION', label: 'CessationOfOperation - The certificate is no longer needed for the purpose for which it was issued but there is no cause to suspect that the private key has been compromised' },
  CERTIFICATE_HOLD : { value: 'CERTIFICATE_HOLD', label: 'CertificateHold - The certificate is temporarily revoked but there is no cause to suspect that the private kye has been compromised' },
  REMOVE_FROM_CRL : { value: 'REMOVE_FROM_CRL', label: 'RemoveFromCRL - The certificate has been unrevoked' },
  PRIVILEGE_WITH_DRAWN : { value: 'PRIVILEGE_WITH_DRAWN', label: 'PrivilegeWithdrawn - The certificate was revoked because a privilege contained within that certificate has been withdrawn' },
  AA_COMPROMISE : { value: 'AA_COMPROMISE', label: 'AACompromise - It is known or suspected that aspects of the AA validated in the attribute certificate, have been compromised' }
}

class CertificateInfos extends Component {
  state = {
    cert: null,
    error: null,
  };

  update = (chain) => {
    BackOfficeServices.certData(chain)
      .then((cert) => {
        if (cert.error) {
          this.setState({ cert: null, error: cert.error });
        } else {
          this.setState({ cert, error: null });
          const domain = this.props.rawValue.domain;
          const rawCopy = { ...this.props.rawValue };
          if (!domain) {
            rawCopy.domain = cert.domain;
            this.props.rawOnChange(rawCopy);
          }
          if (domain && domain !== cert.domain) {
            rawCopy.domain = cert.domain;
            this.props.rawOnChange(rawCopy);
          }
        }
      })
      .catch((e) => {
        this.setState({ cert: null, error: e });
      });
  };

  componentDidMount() {
    this.update(this.props.rawValue.chain);
  }

  componentWillReceiveProps(next) {
    if (next.rawValue && next.rawValue !== this.props.rawValue) {
      this.update(next.rawValue.chain);
    }
  }

  render() {
    if (!this.state.cert) return null;
    if (!!this.state.error)
      return (
        <div>
          <LabelInput label="Infos" value={this.state.error} />
        </div>
      );
    return (
      <div>
        <TextInput label="Subject" disabled={true} value={this.state.cert.subjectDN} />
        <TextInput label="Issuer" disabled={true} value={this.state.cert.issuerDN} />
        <TextInput label="Domain" disabled={true} value={this.state.cert.domain} />
        {(this.state.cert.subAltNames || []).map((name, idx) => (
          <TextInput
            label={idx === 0 ? 'SANs' : ''}
            help={idx === 0 ? 'Certificate Subject Alternate Names' : null}
            disabled={true}
            value={name}
          />
        ))}
        {/*<ArrayInput label="Subject Alternate Names" disabled={true} value={this.state.cert.subAltNames || []} />*/}
        <BooleanInput
          label="Let's Encrypt"
          disabled={true}
          value={this.props.rawValue.letsEncrypt}
        />
        <BooleanInput label="Self signed" disabled={true} value={this.state.cert.selfSigned} />
        <BooleanInput label="CA" disabled={true} value={this.state.cert.ca} />
        <TextInput
          label="Serial number"
          disabled={true}
          value={'Ox' + this.state.cert.serialNumber.toUpperCase()}
        />
        <TextInput
          label="Valid from"
          disabled={true}
          value={moment(this.state.cert.notBefore).format('DD/MM/YYYY HH:mm:ss')}
        />
        <TextInput
          label="Valid until"
          disabled={true}
          value={moment(this.state.cert.notAfter).format('DD/MM/YYYY HH:mm:ss')}
        />
        <TextInput label="Signature" disabled={true} value={this.state.cert.signature} />
        <TextareaInput
          label="Public key"
          disabled={true}
          rows={6}
          style={{ fontFamily: 'monospace' }}
          value={this.state.cert.publicKey}
        />
      </div>
    );
  }
}

class Commands extends Component {
  state = {};

  createCASigned = (e, id) => {
    e.preventDefault();
    e.stopPropagation();
    window
      .popup(
        'New Certificate',
        (ok, cancel) => <NewCertificateForm ok={ok} cancel={cancel} caRef={id} />,
        { style: { width: '100%' } }
      )
      .then((form) => {
        if (form) {
          BackOfficeServices.createCertificateFromForm(form).then((cert) => {
            this.props.setTitle(`Create a new Certificate`);
            window.history.replaceState({}, '', `/bo/dashboard/certificates/add`);
            if (form.letsEncrypt) {
              this.table.setState({ currentItem: cert, showEditForm: true });
            } else {
              this.table.setState({ currentItem: cert, showAddForm: true });
            }
          });
        }
      });
    // window.newPrompt('Certificate hostname').then(value => {
    //   if (value && value.trim() !== '') {
    //     BackOfficeServices.caSignedCert(id, value).then(cert => {
    //       this.props.setTitle(`Create a new certificate`);
    //       window.history.replaceState({}, '', `/bo/dashboard/certificates/add`);
    //       this.props.table().setState({ currentItem: cert, showAddForm: true });
    //     });
    //   }
    // });
  };

  componentDidMount() {
    const cert = this.props.rawValue.chain
      ? this.props.rawValue.chain.split('-----END CERTIFICATE-----')[0] +
        '-----END CERTIFICATE-----'
      : '';
    this.setState({
      fullChainUrl: URL.createObjectURL(
        new Blob([this.props.rawValue.chain], { type: 'text/plain' })
      ),
      privateKeyUrl: URL.createObjectURL(
        new Blob([this.props.rawValue.privateKey], { type: 'text/plain' })
      ),
      fullPkUrl: URL.createObjectURL(
        new Blob([this.props.rawValue.chain + '\n' + this.props.rawValue.privateKey], {
          type: 'text/plain',
        })
      ),
      certUrl: URL.createObjectURL(new Blob([cert], { type: 'text/plain' })),
    });
  }

  render() {
    const certIsEmpty = !(this.props.rawValue.chain && this.props.rawValue.privateKey);
    const canRenew =
      this.props.rawValue.letsEncrypt ||
      this.props.rawValue.ca ||
      this.props.rawValue.selfSigned ||
      !!this.props.rawValue.caRef;
    return (
      <div style={{ width: '100%', display: 'flex', justifyContent: 'flex-end', marginBottom: 20 }}>
        <div className="btn-group">
          {this.props.rawValue.ca && (
            <button
              style={{ marginRight: 0 }}
              type="button"
              className="btn btn-sm btn-success"
              onClick={(e) => {
                this.createCASigned(e, this.props.rawValue.id);
              }}>
              <i className="fas fa-plus-circle" /> Create cert.
            </button>
          )}
          {canRenew && (
            <button
              style={{ marginRight: 0 }}
              type="button"
              className="btn btn-sm btn-success"
              onClick={(e) => {
                BackOfficeServices.renewCert(this.props.rawValue.id).then((cert) => {
                  this.props.rawOnChange(cert);
                });
              }}>
              <i className="fas fa-redo" /> Renew
            </button>
          )}
          {false && (
            <button
              style={{ marginRight: 0 }}
              type="button"
              className="btn btn-sm btn-success"
              onClick={(e) => {
                window.newPrompt('Certificate host ?').then((value) => {
                  if (value && value.trim() !== '') {
                    BackOfficeServices.selfSignedCert(value).then((cert) => {
                      this.props.rawOnChange(cert);
                    });
                  }
                });
              }}>
              <i className="fas fa-screwdriver" /> Generate self signed cert.
            </button>
          )}
          <a
            style={{ marginRight: 0 }}
            href={this.state.certUrl}
            download={`${this.props.rawValue.domain}.cer`}
            className="btn btn-sm btn-success">
            <i className="fas fa-download" /> Certificate Only
          </a>
          <a
            style={{ marginRight: 0 }}
            href={this.state.fullChainUrl}
            download={`${this.props.rawValue.domain}.fullchain.cer`}
            className="btn btn-sm btn-success">
            <i className="fas fa-download" /> Full Chain
          </a>
          <a
            style={{ marginRight: 0 }}
            href={this.state.privateKeyUrl}
            download={`${this.props.rawValue.domain}.key`}
            className="btn btn-sm btn-success">
            <i className="fas fa-download" /> Private Key
          </a>
          <a
            style={{ marginRight: 0 }}
            href={this.state.fullPkUrl}
            download={`${this.props.rawValue.domain}.pem`}
            className="btn btn-sm btn-success">
            <i className="fas fa-download" /> Full Chain + Private Key
          </a>
        </div>
      </div>
    );
  }
}

class CertificateValid extends Component {
  state = {
    loading: false,
    valid: null,
    revoked: RevocationReason.VALID.value,
    error: null,
  };

  update = (cert) => {
    if (!cert.privateKey || cert.privateKey.trim() === '') {
      return;
    }
    this.setState({ loading: true }, () => {
      BackOfficeServices.certValid(cert)
        .then((payload) => {
          if (payload.error) {
            this.setState({ loading: false, valid: false, error: payload.error });
          } else {
            this.setState({ valid: payload.valid, loading: false, error: null, revoked: cert.revoked });
          }
        })
        .catch((e) => {
          this.setState({ loading: false, valid: false, error: e });
        });
    });
  };

  componentDidMount() {
    this.update(this.props.rawValue);
  }

  componentWillReceiveProps(next) {
    if (next.rawValue && next.rawValue !== this.props.rawValue) {
      this.update(next.rawValue);
    }
  }

  render() {
    if (this.state.loading)
      return (
        <div>
          <LabelInput label="Error" value="Loading ..." />
        </div>
      );
    if (!!this.state.error)
      return (
        <div>
          <LabelInput label="Error" value={this.state.error} />
        </div>
      );

    return (
      <div className="form-group">
        <label className="col-sm-2 control-label" />
        <div className="col-sm-10">
          {this.state.valid === true && (
            this.state.revoked === RevocationReason.VALID.value ? 
            <div className="alert alert-success" role="alert">
              Your certificate is valid
            </div> : 
            this.state.revoked && <div className="alert alert-warning" role="alert">
              Your certificate is valid but it has been revoked : {this.state.revoked}
            </div>
          )}
          {this.state.valid === false && (
            <div className="alert alert-danger" role="alert">
              Your certificate is not valid {this.state.revoked !== RevocationReason.VALID.value ? (' : ' + this.state.revoked) : ''}
            </div>
          )}
        </div>
      </div>
    );
  }
}

export class CertificatesPage extends Component {
  formSchema = {
    id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
    name: {
      type: 'string',
      props: { label: 'Name', placeholder: 'www.oto.tools' },
    },
    description: {
      type: 'string',
      props: { label: 'Description', placeholder: 'Certificate for www.oto.tools' },
    },
    domain: {
      type: 'string',
      disabled: true,
      props: { label: 'Certificate domain', placeholder: 'www.oto.tools' },
    },
    metadata: {
      type: 'object',
      props: { label: 'Certificate metadata' },
    },
    commands: {
      type: Commands,
      props: {
        setTitle: (t) => this.props.setTitle(t),
        table: () => this.table,
      },
    },
    infos: {
      type: CertificateInfos,
      props: {},
    },
    valid: {
      type: CertificateValid,
      props: {},
    },
    chain: {
      type: 'text',
      props: { label: 'Certificate full chain', rows: 6, style: { fontFamily: 'monospace' } },
    },
    password: {
      type: 'password',
      props: {
        label: 'Private key password',
        placeholder: 'key password if any',
        rows: 6,
        style: { fontFamily: 'monospace' },
      },
    },
    privateKey: {
      type: 'text',
      props: { label: 'Certificate private key', rows: 6, style: { fontFamily: 'monospace' } },
    },
    autoRenew: {
      type: 'bool',
      props: { label: 'Auto renew cert.' },
    },
    exposed: {
      type: 'bool',
      props: {
        label: 'Public key exposed',
        help:
          'If true, the public key will be exposed on http://otoroshi-api.your-domain/.well-known/jwks.json',
      },
    },
    revoked: {
      type: 'select',
      props: {
        label: 'Certificate status',
        defaultValue: RevocationReason.VALID.value,
        // value: () => (this.props.value || RevocationReason.VALID.value),
        possibleValues: Object.values(RevocationReason),
      },
    },
    client: {
      type: 'bool',
      props: { label: 'Client cert.' },
    },
    keypair: {
      type: 'bool',
      props: { label: 'Keypair' },
    },
    _loc: {
      type: 'location',
      props: {},
    },
  };

  columns = [
    { title: 'Name', content: (item) => item.name },
    { title: 'Description', content: (item) => item.description },
    // { title: 'Domain', content: item => (!item.ca ? item.domain : '') },
    { title: 'Subject', content: (item) => item.subject },
    // {
    //   title: 'Valid',
    //   content: item => {
    //     const now = Date.now();
    //     return item.valid && (now > item.from && now < item.to) ? 'yes' : 'no';
    //   },
    //   style: { textAlign: 'center', width: 70 },
    //   notFilterable: true,
    // },
    {
      title: ' ',
      content: (item) =>
        !item.ca ? null : (
          /*'yes'*/ <button
            type="button"
            className="btn btn-primary btn-sm"
            onClick={(e) => this.createCASigned(e, item.id)}>
            <i className="fas fa-plus-circle" />
          </button>
        ),
      style: { textAlign: 'center', width: 70 },
      notFilterable: true,
    },
    {
      title: 'Type',
      cell: (v, item, table) =>
        item.client ? (
          <span className="label label-primary">client</span>
        ) : item.ca ? (
          <span className="label label-info">ca</span>
        ) : item.letsEncrypt ? (
          <span className="label label-warning">let's encrypt</span>
        ) : item.keypair ? (
          <span className="label label-default">keypair</span>
        ) : item.selfSigned ? (
          <span className="label label-danger">self signed</span>
        ) : (
          <span className="label label-success">certificate</span>
        ),
      content: (item) =>
        item.client
          ? 'client'
          : item.ca
          ? 'ca'
          : item.letsEncrypt
          ? 'letsencrypt'
          : item.keypair
          ? 'keypair'
          : item.selfSigned
          ? 'selfsigned'
          : 'certificate',
      style: { textAlign: 'center', width: 100 },
      notFilterable: false,
    },
    {
      title: 'revoked',
      cell: (v, item) => (item.revoked !== RevocationReason.VALID.value ? <span className="label label-danger">yes</span> : ''),
      content: (item) => (item.revoked !== RevocationReason.VALID.value ? 'yes' : 'no'),
      style: { textAlign: 'center', width: 70 },
    },
    // {
    //   title: 'Client',
    //   content: item => (!item.client ? 'no' : <span className="label label-success">yes</span>),
    //   style: { textAlign: 'center', width: 70 },
    //   notFilterable: true,
    // },
    // {
    //   title: 'Self signed',
    //   content: item => (item.selfSigned ? <span className="label label-danger">yes</span> : 'no'),
    //   style: { textAlign: 'center', width: 90 },
    //   notFilterable: true,
    // },
    // {
    //   title: 'Let\'s Encrypt',
    //   content: item => (!item.letsEncrypt ? 'no' : <span className="label label-success">yes</span>),
    //   style: { textAlign: 'center', width: 90 },
    //   notFilterable: true,
    // },
    {
      title: 'From',
      content: (item) => moment(item.from).format('DD/MM/YYYY HH:mm:ss'),
      style: { textAlign: 'center', width: 150 },
    },
    {
      title: 'To',
      content: (item) => moment(item.to).format('DD/MM/YYYY HH:mm:ss'),
      style: { textAlign: 'center', width: 150 },
    },
  ];

  formFlow = [
    '_loc',
    'id',
    'name',
    'description',
    'autoRenew',
    'client',
    'keypair',
    'exposed',
    'revoked',
    'commands',
    'valid',
    'chain',
    'privateKey',
    'password',
    'infos',
    'metadata',
  ];

  componentDidMount() {
    this.props.setTitle(`All certificates`);
    if (window.history.state && window.history.state.cert) {
      this.props.setTitle(`Create a new certificate`);
      this.table.setState({ currentItem: window.history.state.cert, showAddForm: true });
    }
  }

  createSelfSigned = () => {
    window.newPrompt('Certificate domain').then((value) => {
      if (value && value.trim() !== '') {
        BackOfficeServices.selfSignedCert(value).then((cert) => {
          this.props.setTitle(`Create a new certificate`);
          window.history.replaceState({}, '', `/bo/dashboard/certificates/add`);
          this.table.setState({ currentItem: cert, showAddForm: true });
        });
      }
    });
  };

  createSelfSignedClient = () => {
    window.newPrompt('Certificate DN').then((value) => {
      if (value && value.trim() !== '') {
        BackOfficeServices.selfSignedClientCert(value).then((cert) => {
          this.props.setTitle(`Create a new certificate`);
          window.history.replaceState({}, '', `/bo/dashboard/certificates/add`);
          this.table.setState({ currentItem: cert, showAddForm: true });
        });
      }
    });
  };

  importP12 = () => {
    const input = document.querySelector('input[type="file"]');
    const data = new FormData();
    data.append('file', input.files[0]);
    return window.newPrompt('Certificate password ?').then((password) => {
      if (password) {
        return BackOfficeServices.importP12(password, input.files[0]).then((cert) => {
          // this.table.update();
          this.props.setTitle(`Create a new certificate`);
          window.history.replaceState({}, '', `/bo/dashboard/certificates/add`);
          this.table.setState({ currentItem: cert, showAddForm: true });
        });
      }
    });
  };

  createLetsEncrypt = () => {
    window
      .popup(
        'New Certificate',
        (ok, cancel) => <NewCertificateForm ok={ok} cancel={cancel} letsEncrypt={true} />,
        { style: { width: '100%' } }
      )
      .then((form) => {
        if (form) {
          BackOfficeServices.createCertificateFromForm(form).then((cert) => {
            this.props.setTitle(`Create a new Certificate`);
            window.history.replaceState({}, '', `/bo/dashboard/certificates/add`);
            if (form.letsEncrypt) {
              this.table.setState({ currentItem: cert, showEditForm: true });
            } else {
              this.table.setState({ currentItem: cert, showAddForm: true });
            }
          });
        }
      });
    // window.newPrompt('Certificate domain').then(value => {
    //   if (value && value.trim() !== '') {
    //     if (value.indexOf('*') > -1 ) {
    //       window.newAlert('Domain name cannot contain * character')
    //     } else {
    //       window.newAlert(<LetsEncryptCreation
    //         domain={value}
    //         onCreated={(cert, setError) => {
    //           if (!cert.chain) {
    //             setError(`Error while creating let's encrypt certificate: ${cert.error}`)
    //           } else {
    //             this.props.setTitle(`Edit certificate`);
    //             window.history.replaceState({}, '', `/bo/dashboard/certificates/edit/${cert.id}`);
    //             this.table.setState({ currentItem: cert, showEditForm: true });
    //           }
    //         }} />, `Ordering certificate for ${value}`);
    //     }
    //   }
    // });
  };

  createCASigned = (e, id) => {
    e.preventDefault();
    e.stopPropagation();
    window
      .popup(
        'New Certificate',
        (ok, cancel) => <NewCertificateForm ok={ok} cancel={cancel} caRef={id} />,
        { style: { width: '100%' } }
      )
      .then((form) => {
        if (form) {
          BackOfficeServices.createCertificateFromForm(form).then((cert) => {
            this.props.setTitle(`Create a new Certificate`);
            window.history.replaceState({}, '', `/bo/dashboard/certificates/add`);
            if (form.letsEncrypt) {
              this.table.setState({ currentItem: cert, showEditForm: true });
            } else {
              this.table.setState({ currentItem: cert, showAddForm: true });
            }
          });
        }
      });
    // window.newConfirm("Is certificate a client certificate ?").then(ok => {
    //   if (ok) {
    //     window.newPrompt('Certificate DN').then(value => {
    //       if (value && value.trim() !== '') {
    //         BackOfficeServices.caSignedClientCert(id, value).then(cert => {
    //           this.props.setTitle(`Create a new certificate`);
    //           window.history.replaceState({}, '', `/bo/dashboard/certificates/add`);
    //           this.table.setState({ currentItem: cert, showAddForm: true });
    //         });
    //       }
    //     });
    //   } else {
    //     window.newPrompt('Certificate hostname').then(value => {
    //       if (value && value.trim() !== '') {
    //         BackOfficeServices.caSignedCert(id, value).then(cert => {
    //           this.props.setTitle(`Create a new certificate`);
    //           window.history.replaceState({}, '', `/bo/dashboard/certificates/add`);
    //           this.table.setState({ currentItem: cert, showAddForm: true });
    //         });
    //       }
    //     });
    //   }
    // })
  };

  createCA = () => {
    window.newPrompt('Certificate Authority CN').then((value) => {
      if (value && value.trim() !== '') {
        BackOfficeServices.caCert(value).then((cert) => {
          this.props.setTitle(`Create a new Certificate Authority`);
          window.history.replaceState({}, '', `/bo/dashboard/certificates/add`);
          this.table.setState({ currentItem: cert, showAddForm: true });
        });
      }
    });
  };

  createCertificate = (e) => {
    e.preventDefault();
    window
      .popup('New Certificate', (ok, cancel) => <NewCertificateForm ok={ok} cancel={cancel} />, {
        style: { width: '100%' },
      })
      .then((form) => {
        if (form) {
          BackOfficeServices.createCertificateFromForm(form).then((cert) => {
            this.props.setTitle(`Create a new Certificate`);
            window.history.replaceState({}, '', `/bo/dashboard/certificates/add`);
            if (form.letsEncrypt) {
              this.table.setState({ currentItem: cert, showEditForm: true });
            } else {
              this.table.setState({ currentItem: cert, showAddForm: true });
            }
          });
        }
      });
  };

  updateCertificate = cert => {
    if(cert.revoked === RevocationReason.VALID)
      delete cert.metadata.revocationReason
    else {
      cert.metadata.revocationReason = cert.revoked
      cert.revoked = true;
    }

    BackOfficeServices.updateCertificate(cert)
  }

  findAllCertificates = () => {
    return BackOfficeServices.findAllCertificates()
      .then(certificates => certificates.map(cert => {
        if(cert.metadata.revocationReason)
          cert.revoked = RevocationReason[cert.metadata.revocationReason]? RevocationReason[cert.metadata.revocationReason].value : RevocationReason.UNSPECIFIED
        else if(cert.revoked) // cert was revoked before revocation reason list implementation so set unspecified as reason 
          cert.revoked = RevocationReason.UNSPECIFIED.value
        else 
          cert.revoked = RevocationReason.VALID.value
        return cert;
      }))
  }

  render() {
    return (
      <Table
        parentProps={this.props}
        selfUrl="certificates"
        defaultTitle="All SSL/TLS certificates"
        defaultValue={() => ({ id: faker.random.alphaNumeric(64) })}
        _defaultValue={BackOfficeServices.createNewCertificate}
        itemName="certificate"
        formSchema={this.formSchema}
        formFlow={this.formFlow}
        columns={this.columns}
        stayAfterSave={true}
        fetchItems={this.findAllCertificates}
        updateItem={this.updateCertificate}
        deleteItem={BackOfficeServices.deleteCertificate}
        createItem={BackOfficeServices.createCertificate}
        navigateTo={(item) => {
          window.location = `/bo/dashboard/certificates/edit/${item.id}`;
        }}
        itemUrl={(i) => `/bo/dashboard/certificates/edit/${i.id}`}
        showActions={true}
        showLink={true}
        rowNavigation={true}
        extractKey={(item) => item.id}
        export={true}
        kubernetesKind="Certificate"
        injectTable={(table) => (this.table = table)}
        injectTopBar={() => (
          <>
            {/*<div className="btn-group" style={{ marginRight: 5 }}>
            <button
              type="button"
              onClick={this.createLetsEncrypt}
              style={{ marginRight: 0 }}
              className="btn btn-primary">
              <i className="fas fa-plus-circle" /> Let's Encrypt cert.
            </button>
          </div>*/}
            <div className="btn-group">
              {/*<button
              type="button"
              onClick={this.createSelfSigned}
              style={{ marginRight: 0 }}
              className="btn btn-primary">
              <i className="fas fa-plus-circle" /> Self signed cert.
            </button>
            <button
              type="button"
              onClick={this.createSelfSignedClient}
              style={{ marginRight: 0 }}
              className="btn btn-primary">
              <i className="fas fa-plus-circle" /> Self signed client cert.
            </button>
            <button
              type="button"
              onClick={this.createCA}
              style={{ marginRight: 0 }}
              className="btn btn-primary">
              <i className="fas fa-plus-circle" /> Self signed CA
            </button>*/}
              <button
                type="button"
                onClick={this.createLetsEncrypt}
                style={{ marginRight: 0 }}
                className="btn btn-primary">
                <i className="fas fa-plus-circle" /> Let's Encrypt Certificate
              </button>
              <button
                type="button"
                onClick={this.createCertificate}
                style={{ marginRight: 0 }}
                className="btn btn-primary">
                <i className="fas fa-plus-circle" /> Create Certificate
              </button>
              <input
                type="file"
                name="export"
                id="export"
                className="inputfile btn btn-primary"
                ref={(ref) => (this.fileUpload = ref)}
                style={{ display: 'none' }}
                onChange={this.importP12}
              />
              <label
                htmlFor="export"
                style={{ marginRight: 0 }}
                className="fake-inputfile btn btn-primary ">
                <i className="fas fa-file" /> Import .p12 file
              </label>
            </div>
          </>
        )}
      />
    );
  }
}

export class NewCertificateForm extends Component {
  state = {
    ca: this.props.ca || false,
    client: this.props.client || false,
    letsEncrypt: this.props.letsEncrypt || false,
    caRef: this.props.caRef || null,
    keyType: 'RSA',
    keySize: 2048,
    duration: 365,
    subject:
      this.props.subject || 'SN=Foo, OU=User Certificates, OU=Otoroshi Certificates, O=Otoroshi',
    host: this.props.host || 'www.foo.bar',
    hosts: this.props.host ? [this.props.host] : this.props.hosts || [],
    signatureAlg: 'SHA256WithRSAEncryption',
    digestAlg: 'SHA-256',
    includeAIA: false
  };

  componentDidMount() {
    this.okRef.focus();
  }

  changeTheValue = (name, value) => {
    this.setState({ [name]: value });
  };

  csr = (e) => {
    BackOfficeServices.createCSR(this.state).then((csr) => {
      console.log('csr', csr);
      const url = URL.createObjectURL(
        new Blob([csr.csr], {
          type: 'application/x-pem-file',
        })
      );
      const a = document.createElement('a');
      a.setAttribute('href', url);
      a.setAttribute('download', 'csr.pem');
      a.click();
    });
  };

  render() {
    if (this.state.letsEncrypt) {
      return (
        <>
          <div className="modal-body">
            <form className="form-horizontal" style={{ overflowY: 'auto' }}>
              <BooleanInput
                label="Let's Encrypt"
                value={this.state.letsEncrypt}
                onChange={(v) => this.changeTheValue('letsEncrypt', v)}
                help="Is your certificate a Let's Encrypt certificate"
              />
              <TextInput
                label="Host"
                value={this.state.host}
                onChange={(v) => this.changeTheValue('host', v)}
                help="The host of your certificate"
              />
            </form>
          </div>
          <div className="modal-footer">
            <button type="button" className="btn btn-danger" onClick={this.props.cancel}>
              Cancel
            </button>
            <button
              type="button"
              className="btn btn-success"
              ref={(r) => (this.okRef = r)}
              onClick={(e) => this.props.ok(this.state)}>
              Create
            </button>
          </div>
        </>
      );
    }
    return (
      <>
        <div className="modal-body">
          <form className="form-horizontal" style={{ overflowY: 'auto', maxHeight: '80vh' }}>
            <SelectInput
              label="Issuer"
              value={this.state.caRef}
              onChange={(v) => this.changeTheValue('caRef', v)}
              help="The CA used to sign your certificate"
              placeholder="The CA used to sign your certificate"
              valuesFrom="/bo/api/proxy/api/certificates?ca=true"
              transformer={(a) => ({ value: a.id, label: a.name + ' - ' + a.description })}
            />
            <div className="row">
              <div className="col-md-6">
                {!this.state.client && (
                  <BiColumnBooleanInput
                    label="CA certificate"
                    value={this.state.ca}
                    onChange={(v) => this.changeTheValue('ca', v)}
                    help="Is your certificate a CA"
                  />
                )}
                {!this.state.ca && (
                  <BiColumnBooleanInput
                    label="Client certificate"
                    value={this.state.client}
                    onChange={(v) => this.changeTheValue('client', v)}
                    help="Is your certificate a client certificate"
                  />
                )}
              </div>
              <div className="col-md-6">
                <BiColumnBooleanInput
                  label="Let's Encrypt"
                  value={this.state.letsEncrypt}
                  onChange={(v) => this.changeTheValue('letsEncrypt', v)}
                  help="Is your certificate a Let's Encrypt certificate"
                />
                <BiColumnBooleanInput
                  label="Include A.I.A"
                  value={this.state.includeAIA}
                  onChange={(v) => this.changeTheValue('includeAIA', v)}
                  help="Include authority information access urls in the certificate"
                />
              </div>
            </div>
            <SelectInput
              label="Key Type"
              help="The type of the private key"
              value={this.state.keyType}
              onChange={(v) => {
                this.changeTheValue('keyType', v);
                this.changeTheValue('keySize', 256);
                this.changeTheValue('signatureAlg', 'SHA256withECDSA');
                this.changeTheValue('digestAlg', 'SHA-256');
              }}
              possibleValues={[
                { label: 'RSA', value: 'RSA' },
                { label: 'ECDSA', value: 'ECDSA' },
              ]}
            />
            <SelectInput
              label={this.state.keyType !== 'RSA' ? 'Curve' : 'Key Size'}
              help={
                this.state.keyType !== 'RSA'
                  ? 'The curve used for the key'
                  : 'The size of the private key'
              }
              value={this.state.keySize}
              onChange={(v) => this.changeTheValue('keySize', v)}
              possibleValues={
                this.state.keyType === 'RSA'
                  ? [
                      { label: '2048', value: 2048 },
                      { label: '4096', value: 4096 },
                      { label: '6144', value: 6144 },
                    ]
                  : [
                      { label: 'P256', value: 256 },
                      { label: 'P384', value: 384 },
                      { label: 'P521', value: 521 },
                    ]
              }
            />
            <SelectInput
              label="Signature Algorithm"
              help="The signature algorithm used"
              value={this.state.signatureAlg}
              onChange={(v) => this.changeTheValue('signatureAlg', v)}
              possibleValues={
                this.state.keyType === 'RSA'
                  ? [
                      { label: 'SHA224WithRSAEncryption', value: 'SHA224WithRSAEncryption' },
                      { label: 'SHA256WithRSAEncryption', value: 'SHA256WithRSAEncryption' },
                      { label: 'SHA384WithRSAEncryption', value: 'SHA384WithRSAEncryption' },
                      { label: 'SHA512WithRSAEncryption', value: 'SHA512WithRSAEncryption' },
                    ]
                  : [
                      { label: 'SHA256withECDSA', value: 'SHA256withECDSA' },
                      { label: 'SHA384withECDSA', value: 'SHA384withECDSA' },
                      { label: 'SHA512withECDSA', value: 'SHA512withECDSA' },
                    ]
              }
            />
            <SelectInput
              label="Digest Algorithm"
              help="The digest algorithm used"
              value={this.state.digestAlg}
              onChange={(v) => this.changeTheValue('digestAlg', v)}
              possibleValues={[
                { label: 'SHA-224', value: 'SHA-224' },
                { label: 'SHA-256', value: 'SHA-256' },
                { label: 'SHA-384', value: 'SHA-384' },
                { label: 'SHA-512', value: 'SHA-512' },
                { label: 'SHA-512-224', value: 'SHA-512-224' },
                { label: 'SHA-512-256', value: 'SHA-512-256' },
              ]}
            />
            <NumberInput
              label="Validity"
              value={this.state.duration}
              onChange={(v) => this.changeTheValue('duration', v)}
              help="How much time your certificate will be valid"
              suffix="days"
            />
            <TextInput
              label="Subject DN"
              value={this.state.subject}
              onChange={(v) => this.changeTheValue('subject', v)}
              help="The subject DN of your certificate"
            />
            {!this.state.ca && !this.state.client && (
              <ArrayInput
                label="Hosts"
                value={this.state.hosts}
                onChange={(v) => this.changeTheValue('hosts', v)}
                help="The hosts of your certificate"
              />
            )}
          </form>
        </div>
        <div className="modal-footer">
          {this.state.caRef && (
            <button type="button" className="btn btn-primary" onClick={this.csr}>
              <i className="fas fa-file" /> Download CSR
            </button>
          )}
          <button type="button" className="btn btn-danger" onClick={this.props.cancel}>
            Cancel
          </button>
          <button
            type="button"
            className="btn btn-success"
            ref={(r) => (this.okRef = r)}
            onClick={(e) => this.props.ok(this.state)}>
            Create
          </button>
        </div>
      </>
    );
  }
}

export class LetsEncryptCreation extends Component {
  state = { error: null, done: false };

  componentDidMount() {
    BackOfficeServices.letsEncryptCert(this.props.domain)
      .then((cert) => {
        this.setState({ done: true });
        setTimeout(() => {
          this.props.onCreated(cert, (e) => this.setState({ error: e }));
        }, 1000);
      })
      .catch((e) => {
        this.setState({ error: e.message ? e.message : e });
      });
  }

  render() {
    if (this.state.error) {
      return <span className="label label-danger">{this.state.error}</span>;
    }
    if (this.state.done) {
      return (
        <span className="label label-success">
          Certificate for {this.props.domain} created successfully !
        </span>
      );
    }
    return (
      <div
        style={{
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'center',
          width: '100%',
          height: 300,
        }}>
        <svg
          width="142px"
          height="142px"
          viewBox="0 0 100 100"
          preserveAspectRatio="xMidYMid"
          className="uil-ring-alt">
          <rect x="0" y="0" width="100" height="100" fill="none" className="bk" />
          <circle cx="50" cy="50" r="40" stroke="#222222" fill="none" strokeLinecap="round" />
          <circle cx="50" cy="50" r="40" stroke="#f9b000" fill="none" strokeLinecap="round">
            <animate
              attributeName="stroke-dashoffset"
              dur="2s"
              repeatCount="indefinite"
              from="0"
              to="502"
            />
            <animate
              attributeName="stroke-dasharray"
              dur="2s"
              repeatCount="indefinite"
              values="150.6 100.4;1 250;150.6 100.4"
            />
          </circle>
        </svg>
      </div>
    );
  }
}
