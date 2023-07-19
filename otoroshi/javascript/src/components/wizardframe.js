import React, { Component } from 'react';
import { Button } from './Button';

function Header({ onClose, title }) {
  return (
    <label style={{ fontSize: '1.15rem' }}>
      <i className="fas fa-times me-3" onClick={onClose} style={{ cursor: 'pointer' }} />
      <span>{title}</span>
    </label>
  );
}

function WizardActions({ cancel, ok, cancelLabel, okLabel, noCancel, noOk }) {
  return (
    <div className="d-flex mt-auto justify-content-between align-items-center">
      {!noCancel && (
        <Button
          className="ms-auto"
          onClick={cancel}
          text={cancelLabel || 'Cancel'}
          type="save"
          style={{
            backgroundColor: 'var(--color-danger)',
            borderColor: 'var(--color-danger)',
            padding: '12px 48px',
          }}
        />
      )}
      {!noOk && (
        <Button
          className="ms-auto"
          onClick={ok}
          text={okLabel || 'Ok'}
          type="save"
          style={{
            backgroundColor: 'var(--color-primary)',
            borderColor: 'var(--color-primary)',
            padding: '12px 48px',
          }}
        />
      )}
    </div>
  );
}

export class WizardFrame extends Component {
  render() {
    return (
      <div className="wizard">
        <div className="wizard-container">
          <div className="d-flex" style={{ flexDirection: 'column', padding: '2.5rem', flex: 1 }}>
            <Header title={this.props.title} onClose={this.props.cancel} />
            {this.props.children
              ? this.props.children
              : this.props.body(this.props.ok, this.props.cancel)}
            <WizardActions {...this.props} />
          </div>
        </div>
      </div>
    );
  }
}
