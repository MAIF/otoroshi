import React, { Component } from 'react';
import moment from 'moment';
import { v4 as uuid } from 'uuid';

class Toast extends Component {
  render() {
    let backgroundColor = 'var(--color-primary)';
    let color = 'var(--color_level3)';
    if (this.props.toast.kind === 'error') {
      backgroundColor = 'var(--color-red)';
    }
    if (this.props.toast.kind === 'warn') {
      backgroundColor = 'orange';
    }
    return (
      <div
        style={{
          padding: 10,
          marginBottom: 10,
          width: '100%',
          backgroundColor,
          borderRadius: '5px',
        }}
      >
        <div
          style={{
            fontWeight: 'bold',
            color,
            width: '100%',
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
          }}
        >
          {this.props.toast.title}{' '}
          <button
            type="button"
            className="btn btn-sm btn-danger"
            onClick={(e) => this.props.close()}
          >
            <i className="fas fa-times" style={{ color: '#fff' }} />
          </button>
        </div>
        <div
          style={{
            width: '100%',
            display: 'flex',
            color: 'var(--color_level3)',
            fontSize: '1rem',
            flexDirection: 'column',
            marginTop: '10px',
          }}
        >
          {this.props.toast.body}
          <small>{moment().format('YYYY-MM-DD HH:mm:ss')}</small>{' '}
        </div>
      </div>
    );
  }
}

export class Toasts extends Component {
  state = { toasts: [] };

  toast = (title, body, kind = 'normal', duration = 3000) => {
    const toast = {
      id: uuid(),
      title: title,
      body: body,
      kind: kind,
    };
    this.setState({ toasts: [toast, ...this.state.toasts] });
    setTimeout(() => {
      this.close(toast);
    }, duration);
  };

  close = (toast) => {
    this.setState({ toasts: this.state.toasts.filter((t) => t.id !== toast.id) });
  };

  componentDidMount() {
    window.toast = this.toast;
  }

  componentWillUnmount() {
    delete window.toast;
  }

  render() {
    return (
      <div
        id="otoroshi-toasts"
        style={{
          margin: 10,
          paddingBottom: 0,
          zIndex: 999999,
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'flex-end',
          alignItems: 'center',
          position: 'fixed',
          right: 0,
          top: 50,
          width: '30vw',
        }}
      >
        {this.state.toasts.map((toast) => (
          <Toast toast={toast} close={(e) => this.close(toast)} />
        ))}
      </div>
    );
  }
}
