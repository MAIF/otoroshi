import React, { Component } from 'react';
import _ from 'lodash';

import { Loader } from './Loader';
import * as BackOfficeServices from '../services/BackOfficeServices';

let lastEnv = {
  changePassword: false,
  mailgun: false,
  clevercloud: false,
  apiReadOnly: false,
  u2fLoginOnly: true,
  env: 'prod',
  redirectToDev: false,
  displayPrivateApps: false,
  clientIdHeader: 'Otoroshi-Client-Id',
  clientSecretHeader: 'Otoroshi-Client-Secret',
};

export class WithEnv extends Component {
  state = {
    env: lastEnv,
  };

  componentDidMount() {
    BackOfficeServices.env().then(env => {
      lastEnv = env;
      this.setState({ env });
    });
  }

  render() {
    if (!this.state.env) {
      return null; // <Loader />;
    }
    const Comp = this.props.component || this.props.children;
    if (!Comp) {
      return null;
    }
    const props = { ...this.props };
    if (this.props.predicate && !this.props.predicate(this.state.env)) {
      return null;
    }
    delete props.component;
    delete props.predicate;
    if (_.isFunction(Comp) && !(Comp instanceof Component)) {
      return Comp(this.state.env);
    }
    if (Comp instanceof Component) {
      return <Comp {...props} env={this.state.env} />;
    } else {
      return Comp;
    }
  }
}
