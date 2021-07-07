import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import _ from 'lodash';

import { OtoroshiCharts } from '../components/OtoroshiCharts';

export class ApiKeyStatsPage extends Component {
  state = {
    apikey: null,
  };

  componentDidMount() {
    this.props.setTitle(`ApiKey analytics`);
    //if (this.props.params.serviceId === '---') {
    BackOfficeServices.fetchStandaloneApiKey(this.props.params.titem).then((apikey) => {
      this.setState({ apikey });
      this.props.setTitle(`${apikey.clientName} analytics`);
    })
    //} else {
    //  BackOfficeServices.fetchApiKeyById(this.props.params.serviceId, this.props.params.titem).then(
    //    (apikey) => {
    //      this.setState({ apikey });
    //      this.props.setTitle(`${apikey.clientName} analytics`);
    //    }
    //  );
    //}
  }

  fetchData = (from, to) => {
    return BackOfficeServices.fetchStats('apikey', this.props.params.titem, from, to);
  };

  render() {
    return <OtoroshiCharts fetchData={this.fetchData} />;
  }
}
