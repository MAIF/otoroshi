import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import { Table, TextInput, TextareaInput, LabelInput } from '../components/inputs';
import moment from 'moment';
import faker from 'faker';

class CertificateInfos extends Component {
  state = {
    cert: null,
    error: null,
  };

  update = chain => {
    BackOfficeServices.certData(chain)
      .then(cert => {
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
      .catch(e => {
        console.log('update: ffuuuuu');
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
        <TextInput
          label="Self signed"
          disabled={true}
          value={this.state.cert.selfSigned ? 'yes' : 'no'}
        />
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
        <TextareaInput
          label="Signature"
          disabled={true}
          rows={6}
          value={this.state.cert.signature}
        />
        <TextareaInput
          label="Public key"
          disabled={true}
          rows={6}
          value={this.state.cert.publicKey}
        />
      </div>
    );
  }
}

class SelfSignedCert extends Component {
  render() {
    return (
      <div style={{ width: '100%', display: 'flex', justifyContent: 'flex-end', marginBottom: 20 }}>
        <button
          type="button"
          className="btn btn-sm btn-success"
          onClick={e => {
            const value = prompt('Certificate host ?');
            if (value && value.trim() !== '') {
              BackOfficeServices.selfSignedCert(value).then(cert => {
                this.props.rawOnChange(cert);
              });
            }
          }}>
          <i className="fas fa-screwdriver" /> Generate self signed cert.
        </button>
      </div>
    );
  }
}

class CertificateValid extends Component {
  state = {
    loading: false,
    valid: false,
    error: null,
  };

  update = cert => {
    this.setState({ loading: true }, () => {
      BackOfficeServices.certValid(cert)
        .then(payload => {
          if (payload.error) {
            this.setState({ loading: false, valid: false, error: payload.error });
          } else {
            this.setState({ valid: payload.valid, loading: false, error: null });
          }
        })
        .catch(e => {
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
          {this.state.valid && (
            <div className="alert alert-success" role="alert">
              Your certificate is valid
            </div>
          )}
          {!this.state.valid && (
            <div className="alert alert-danger" role="alert">
              Your certificate is not valid
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
    domain: {
      type: 'string',
      disabled: true,
      props: { label: 'Certificate domain', placeholder: 'www.foo.bar' },
    },
    selfCert: {
      type: SelfSignedCert,
      props: {},
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
      props: { label: 'Certificate full chain', rows: 6 },
    },
    privateKey: {
      type: 'text',
      props: { label: 'Certificate private key', rows: 6 },
    },
  };

  columns = [
    { title: 'Id', content: item => item.id },
    { title: 'Domain', content: item => item.domain },
  ];

  formFlow = ['id', 'domain', 'selfCert', 'valid', 'chain', 'privateKey', 'infos'];

  componentDidMount() {
    this.props.setTitle(`All certificates (experimental)`);
  }

  render() {
    return (
      <Table
        parentProps={this.props}
        selfUrl="certificates"
        defaultTitle="All certificates"
        defaultValue={() => ({ id: faker.random.alphaNumeric(64) })}
        itemName="certificate"
        formSchema={this.formSchema}
        formFlow={this.formFlow}
        columns={this.columns}
        stayAfterSave={true}
        fetchItems={BackOfficeServices.findAllCertificates}
        updateItem={BackOfficeServices.updateCertificate}
        deleteItem={BackOfficeServices.deleteCertificate}
        createItem={BackOfficeServices.createCertificate}
        navigateTo={item => {
          window.location = `/bo/dashboard/certificates`;
        }}
        itemUrl={i => `/bo/dashboard/certificates/${i.id}`}
        showActions={true}
        showLink={true}
        rowNavigation={true}
        extractKey={item => item.id}
      />
    );
  }
}
