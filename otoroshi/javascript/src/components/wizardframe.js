import React, { Component, useState } from 'react';
import { Button } from './Button';

function Header({ onClose, title }) {
  return (
    <label style={{ fontSize: '1.15rem' }}>
      <i className="fas fa-times me-3" onClick={onClose} style={{ cursor: 'pointer' }} />
      <span>{title}</span>
    </label>
  );
}

function WizardActions({
  cancel,
  ok,
  cancelLabel,
  okLabel,
  noCancel,
  noOk,
  okClassName = '',
  state,
}) {
  return (
    <div
      className="d-flex mt-auto justify-content-between align-items-center"
      style={{
        position: 'sticky',
        bottom: 0,
        right: 0,
        left: 0,
        marginLeft: '-50px',
        padding: '1rem',
        background: 'var(--bg-color_level2)',
        zIndex: 100,
      }}
    >
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
          className={okClassName ? okClassName : 'ms-auto'}
          onClick={(e) => ok(e, state)}
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

export function WizardFrame(props) {
  const [state, setState] = useState();

  return (
    <div className="wizard">
      <div className="wizard-container">
        <div
          className="d-flex"
          style={{
            flexDirection: 'column',
            padding: '2.5rem 0 0 2.5rem',
            flex: 1,
            position: 'relative',
          }}
        >
          <Header title={props.title} onClose={props.cancel} />
          {props.children ? props.schildren : props.body(props.ok, props.cancel, state, setState)}
          <WizardActions {...props} state={state} />
        </div>
      </div>
    </div>
  );
}
