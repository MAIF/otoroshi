import React, { Component } from 'react';
import ReactDOM from 'react-dom';
import isFunction from 'lodash/isFunction';
import isString from 'lodash/isString';
import { WizardFrame } from './wizardframe';

const KEY_NAME_ESC = 'Escape';
const KEY_EVENT_TYPE = 'keyup';

const handleEscKey = (event, onClose) => {
  if (event.key === KEY_NAME_ESC) {
    onClose();
  }
};

const handleClickOutside = (event, ref, onClose) => {
  if (ref.current && !ref.current.contains(event.target)) {
    onClose();
  }
};

class Alert extends Component {
  constructor(props) {
    super(props);
    this.modalRef = React.createRef();     
  }

  componentDidMount() {
    document.addEventListener(KEY_EVENT_TYPE, (event) => handleEscKey(event, this.props.close), false);
    document.addEventListener('mousedown', (event) => handleClickOutside(event, this.modalRef, this.props.close));
    this.okRef.focus();
  }

  componentWillUnmount() {
    document.removeEventListener(KEY_EVENT_TYPE, (event) => handleEscKey(event, this.props.close), false);
    document.removeEventListener('mousedown', (event) => handleClickOutside(event, this.modalRef, this.props.close));
  }

  render() {
    const res = isFunction(this.props.message)
      ? this.props.message(this.props.close)  
      : this.props.message;
    return (
      <div className="modal" tabIndex="-1" role="dialog" style={{ display: 'block' }}>
        <div className="modal-dialog" role="document" style={this.props.modalStyleOverride || {}} ref={this.modalRef}>
          <div className="modal-content" style={this.props.contentStyleOverride || {}}>
            <div className="modal-header">
              <h4 className="modal-title">{this.props.title ? this.props.title : 'Alert'}</h4>
              <button
                type="button"
                className="btn-close"
                data-dismiss="modal"
                onClick={this.props.close}
                aria-label="Close"
              ></button>
            </div>
            <div className="modal-body">
              {isString(res) && <p>{res}</p>}
              {!isString(res) && !isFunction(res) && res}
              {!isString(res) && isFunction(res) && res(this.props.close)}
            </div>
            <div className="modal-footer">
              {this.props.linkOpt && (
                <a
                  data-dismiss="modal"
                  href={this.props.linkOpt.to}
                  className="btn btn-default"
                  onClick={this.props.close}
                >
                  {this.props.linkOpt.title}
                </a>
              )}
              <button
                ref={(r) => (this.okRef = r)}
                type="button"
                className="btn btn-primary"
                onClick={this.props.close}
              >
                Close
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }
}

class Confirm extends Component {
  constructor(props) {
    super(props);
    this.modalRef = React.createRef();     
  }

  componentDidMount() {
      document.addEventListener(KEY_EVENT_TYPE, (event) => handleEscKey(event, this.props.cancel), false);
      document.addEventListener('mousedown', (event) => handleClickOutside(event, this.modalRef, this.props.cancel));
      document.body.addEventListener('keydown', this.defaultButton);
      this.okRef.focus();
  }

  componentWillUnmount() {
    document.removeEventListener(KEY_EVENT_TYPE, (event) => handleEscKey(event, this.props.close), false);
    document.removeEventListener('mousedown', (event) => handleClickOutside(event, this.modalRef, this.props.cancel));

  }
  
  render() {
    return (
      <div className="modal" tabIndex="-1" role="dialog" style={{ display: 'block' }}>
        <div className="modal-dialog" role="document" ref={this.modalRef}>
          <div className="modal-content">
            <div className="modal-header">
              <h4 className="modal-title">{this.props.title ? this.props.title : 'Confirm'}</h4>
              <button
                type="button"
                className="btn-close"
                data-dismiss="modal"
                onClick={this.props.cancel}
                aria-label="Close"
              ></button>
            </div>
            <div className="modal-body">
              <p>{this.props.message}</p>
            </div>
            <div className="modal-footer">
              <button
                ref={(r) => (this.cancelRef = r)}
                type="button"
                className="btn btn-danger"
                onClick={this.props.cancel}
              >
                {this.props.noText || 'Cancel'}
              </button>
              <button
                ref={(r) => (this.okRef = r)}
                type="button"
                className="btn btn-success"
                onClick={this.props.ok}
              >
                {this.props.yesText || 'Ok'}
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }
}

class Prompt extends Component {
  constructor(props) {
    super(props);
    this.modalRef = React.createRef();
  }
  
  state = {
    text: this.props.value || '',
  };

  componentDidMount() {
    document.addEventListener(KEY_EVENT_TYPE, (event) => handleEscKey(event, this.props.cancel), false);
    document.addEventListener('mousedown', (event) => handleClickOutside(event, this.modalRef, this.props.cancel));
    this.okRef.focus();
    if (this.ref) {
      this.ref.focus();
    }
  }
  
  componentWillUnmount() {
    document.removeEventListener(KEY_EVENT_TYPE, (event) => handleEscKey(event, this.props.close), false);
    document.removeEventListener('mousedown', (event) => handleClickOutside(event, this.modalRef, this.props.cancel));
  }
  
  render() {
    return (
      <div className="modal" tabIndex="-1" role="dialog" style={{ display: 'block' }}>
        <div className="modal-dialog" role="document" ref={this.modalRef}>
          <div className="modal-content">
            <div className="modal-header">
              <h4 className="modal-title">{this.props.title ? this.props.title : 'Prompt'}</h4>
              <button
                type="button"
                className="btn-close"
                data-bs-dismiss="modal"
                onClick={this.props.cancel}
                aria-label="Close"
              ></button>
            </div>
            <div className="modal-body">
              <p>{this.props.message}</p>
              {!this.props.textarea && (
                <input
                  type={this.props.type || 'text'}
                  className="form-control"
                  value={this.state.text}
                  ref={(r) => (this.ref = r)}
                  onChange={(e) => this.setState({ text: e.target.value })}
                />
              )}
              {this.props.textarea && (
                <textarea
                  className="form-control"
                  value={this.state.text}
                  ref={(r) => (this.ref = r)}
                  rows={this.props.rows || 5}
                  onChange={(e) => this.setState({ text: e.target.value })}
                />
              )}
            </div>
            <div className="modal-footer">
              <button type="button" className="btn btn-danger" onClick={this.props.cancel}>
                Cancel
              </button>
              <button
                type="button"
                className="btn btn-success"
                ref={(r) => (this.okRef = r)}
                onClick={(e) => this.props.ok(this.state.text)}
              >
                Ok
              </button>
            </div>
          </div>
        </div>
      </div>
    );
  }
}

class Popup extends Component {
  constructor(props) {
    super(props);
    this.modalRef = React.createRef();
  }

  componentDidMount() {
    document.addEventListener(KEY_EVENT_TYPE, (event) => handleEscKey(event, this.props.cancel), false);
    document.addEventListener('mousedown', (event) => handleClickOutside(event, this.modalRef, this.props.cancel));
  }

  componentWillUnmount() {
    document.removeEventListener(KEY_EVENT_TYPE, (event) => handleEscKey(event, this.props.close), false);
    document.removeEventListener('mousedown', (event) => handleClickOutside(event, this.modalRef, this.props.cancel));
  }

  render() {
    return (
      <div
        className="modal"
        tabIndex="-1"
        role="dialog"
        style={{ display: 'block', ...this.props.style }}
      >
        <div
          className={
            'modal-dialog' + (this.props.additionalClass ? ' ' + this.props.additionalClass : '')
          }
          role="document"
          ref={this.modalRef}
        >
          <div className="modal-content">
            <div className="modal-header">
              <h4 className="modal-title">{this.props.title}</h4>
              <button
                type="button"
                className="btn-close"
                data-dismiss="modal"
                onClick={this.props.cancel}
                aria-label="Close"
              ></button>
            </div>
            {this.props.body(this.props.ok, this.props.cancel)}
          </div>
        </div>
      </div>
    );
  }
}

export function registerAlert() {
  window.oldAlert = window.alert;
  if (!document.getElementById('otoroshi-alerts-container')) {
    const div = document.createElement('div');
    div.setAttribute('id', 'otoroshi-alerts-container');
    document.body.appendChild(div);
  }
  window.newAlert = (
    message,
    title,
    linkOpt,
    modalStyleOverride = {},
    contentStyleOverride = {}
  ) => {
    return new Promise((success) => {
      ReactDOM.render(
        <Alert
          message={message}
          title={title}
          linkOpt={linkOpt}
          contentStyleOverride={contentStyleOverride}
          modalStyleOverride={modalStyleOverride}
          close={() => {
            ReactDOM.unmountComponentAtNode(document.getElementById('otoroshi-alerts-container'));
            success();
          }}
        />,
        document.getElementById('otoroshi-alerts-container')
      );
    });
  };
}

export function registerConfirm() {
  window.oldConfirm = window.confirm;
  if (!document.getElementById('otoroshi-alerts-container')) {
    const div = document.createElement('div');
    div.setAttribute('id', 'otoroshi-alerts-container');
    document.body.appendChild(div);
  }
  window.newConfirm = (message, props) => {
    return new Promise((success, failure) => {
      ReactDOM.render(
        <Confirm
          message={message}
          {...props}
          ok={() => {
            success(true);
            ReactDOM.unmountComponentAtNode(document.getElementById('otoroshi-alerts-container'));
          }}
          cancel={() => {
            success(false);
            ReactDOM.unmountComponentAtNode(document.getElementById('otoroshi-alerts-container'));
          }}
        />,
        document.getElementById('otoroshi-alerts-container')
      );
    });
  };
}

export function registerPrompt() {
  window.oldPrompt = window.prompt;
  if (!document.getElementById('otoroshi-alerts-container')) {
    const div = document.createElement('div');
    div.setAttribute('id', 'otoroshi-alerts-container');
    document.body.appendChild(div);
  }
  window.newPrompt = (message, opts = {}) => {
    return new Promise((success, failure) => {
      ReactDOM.render(
        <Prompt
          message={message}
          title={opts.title}
          value={opts.value}
          type={opts.type}
          textarea={opts.textarea}
          rows={opts.rows}
          ok={(inputValue) => {
            success(inputValue);
            ReactDOM.unmountComponentAtNode(document.getElementById('otoroshi-alerts-container'));
          }}
          cancel={() => {
            success(null);
            ReactDOM.unmountComponentAtNode(document.getElementById('otoroshi-alerts-container'));
          }}
        />,
        document.getElementById('otoroshi-alerts-container')
      );
    });
  };
}

export function registerPopup() {
  if (!document.getElementById('otoroshi-alerts-container')) {
    const div = document.createElement('div');
    div.setAttribute('id', 'otoroshi-alerts-container');
    document.body.appendChild(div);
  }
  registerWizard();
  window.popup = (title, fn, props = {}) => {
    return new Promise((success, failure) => {
      ReactDOM.render(
        <Popup
          body={fn}
          title={title}
          ok={(inputValue) => {
            success(inputValue);
            ReactDOM.unmountComponentAtNode(document.getElementById('otoroshi-alerts-container'));
          }}
          cancel={() => {
            success(null);
            ReactDOM.unmountComponentAtNode(document.getElementById('otoroshi-alerts-container'));
          }}
          {...props}
        />,
        document.getElementById('otoroshi-alerts-container')
      );
    });
  };
}

export function registerWizard() {
  window.wizard = (title, fn, props = {}) => {
    return new Promise((success, failure) => {
      ReactDOM.render(
        <WizardFrame
          body={fn}
          title={title}
          ok={(inputValue) => {
            success(inputValue);
            ReactDOM.unmountComponentAtNode(document.getElementById('otoroshi-alerts-container'));
          }}
          cancel={() => {
            success(null);
            ReactDOM.unmountComponentAtNode(document.getElementById('otoroshi-alerts-container'));
          }}
          {...props}
        />,
        document.getElementById('otoroshi-alerts-container')
      );
    });
  };
}
