import React, { Component } from 'react';
import * as BackOfficeServices from '../services/BackOfficeServices';
import _ from 'lodash';

import { OtoroshiCharts } from '../components/OtoroshiCharts';

export class GroupStatsPage extends Component {
  state = {
    group: null,
  };

  componentDidMount() {
    this.props.setTitle(`Group analytics`);
    BackOfficeServices.findGroupById(this.props.params.titem).then(group => {
      this.setState({ group });
      this.props.setTitle(`${group.name} analytics`);
    });
  }

  fetchData = (from, to) => {
    return BackOfficeServices.fetchStats('group', this.props.params.titem, from, to);
  };

  render() {
    return <OtoroshiCharts fetchData={this.fetchData} />;
  }
}
