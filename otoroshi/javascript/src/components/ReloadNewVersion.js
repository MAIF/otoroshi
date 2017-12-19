import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';

export class ReloadNewVersion extends Component {
  state = {
    display: this.props.debug || false,
    version: null,
    versionClosed: false,
  };

  componentDidMount() {
    // console.log('Mount version loader');
    this.checkVersion();
    this.interval = setInterval(this.checkVersion, 30000);
  }

  componentWillUnmount() {
    // console.log('Unmount version loader');
    if (this.interval) {
      clearInterval(this.interval);
    }
  }

  checkVersion = () => {
    BackOfficeServices.version().then(
      data => {
        const { versionClosed, version, display } = this.state;
        if (version && !versionClosed && version !== data.version) {
          this.setState({ display: true, version: data.version });
        } else {
          this.setState({ version: data.version });
        }
      },
      e => console.log('error during version check', e)
    );
  };

  render() {
    if (!this.state.display) {
      return null;
    }
    return (
      <div className="newVersionPopup">
        A new version of Otoroshi has been deployed
        <button
          type="button"
          className="btn btn-danger btn-sm"
          style={{ marginLeft: 10 }}
          onClick={e => window.location.reload()}>
          <i className="glyphicon glyphicon-refresh" />
        </button>
        <button
          type="button"
          className="btn btn-info btn-sm"
          style={{ marginLeft: 10 }}
          onClick={e => {
            e.preventDefault();
            this.setState({ display: false, versionClosed: true });
          }}>
          <i className="glyphicon glyphicon-remove" />
        </button>
      </div>
    );
  }
}
