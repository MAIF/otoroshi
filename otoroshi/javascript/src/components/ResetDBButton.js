import React, { Component } from 'react';

import * as BackOfficeServices from '../services/BackOfficeServices';

export class ResetDBButton extends Component {
  state = {
    loading: false,
  };

  resetDB = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    window.newConfirm('Are you sure you want to reset datastore ?').then((ok) => {
      window.newConfirm('Are you really sure ?').then((ok2) => {
        if (ok && ok2) {
          this.setState({ loading: true });
          BackOfficeServices.resetDB().then(() => {
            this.setState({ loading: false });
          });
        }
      });
    });
  };

  render() {
    if (this.state.loading) {
      return (
        <button type="button" className="btn btn-default active">
          <i className="fas fa-fire" /> Reset DB
        </button>
      );
    }
    return (
      <button type="button" className="btn btn-danger" onClick={this.resetDB}>
        <i className="fas fa-fire" /> Reset DB
      </button>
    );
  }
}
