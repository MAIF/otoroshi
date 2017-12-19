import React, { Component } from 'react';

import * as BackOfficeServices from '../services/BackOfficeServices';

export class ResetDBButton extends Component {
  state = {
    loading: false,
  };

  resetDB = e => {
    if (e && e.preventDefault) e.preventDefault();
    confirm('Etes vous sûr de vouloir effacer la base de données ?');
    confirm('Vraiment sûr de vouloir effacer toutes ces précieuses données ?');
    this.setState({ loading: true });
    BackOfficeServices.resetDB().then(() => {
      this.setState({ loading: false });
    });
  };

  render() {
    if (this.state.loading) {
      return (
        <button type="button" className="btn btn-default active">
          <i className="glyphicon glyphicon-fire" /> Reset DB
        </button>
      );
    }
    return (
      <button type="button" className="btn btn-danger" onClick={this.resetDB}>
        <i className="glyphicon glyphicon-fire" /> Reset DB
      </button>
    );
  }
}
