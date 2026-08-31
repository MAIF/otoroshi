import React, { Component } from 'react';
import { SelectInput } from './inputs';
import { nextClient } from '../services/BackOfficeServices';

// Edits ApiKey.apiRef: which api plan this apikey consumes. The plan select is fed by the selected
// api, so the two always stay consistent.
export class ApikeyApiRefField extends Component {
  state = { apis: [] };

  componentDidMount() {
    nextClient
      .forEntityNext(nextClient.ENTITIES.APIS)
      .findAll()
      .then((apis) => this.setState({ apis: Array.isArray(apis) ? apis : [] }));
  }

  ref = () => this.props.value || {};

  render() {
    const ref = this.ref();
    const currentApi = this.state.apis.find((api) => api.id === ref.api);
    const plans = currentApi?.plans || [];
    return (
      <>
        <SelectInput
          isClearable
          label="API"
          value={ref.api}
          help="The api this apikey is a consumer of. The plugins of the selected plan will run for every call made with this apikey."
          possibleValues={this.state.apis.map((api) => ({
            value: api.id,
            label: `${api.name} (${api.version})`,
          }))}
          onChange={(api) => {
            // changing the api invalidates the plan
            if (!api) this.props.onChange(null);
            else this.props.onChange({ api, plan: '', sub: ref.sub || '' });
          }}
        />
        {ref.api && (
          <SelectInput
            key={ref.api}
            label="Plan"
            isClearable
            value={ref.plan}
            help={plans.length === 0 ? 'This api has no plan yet' : 'The plan of the api'}
            possibleValues={plans.map((plan) => ({ value: plan.id, label: plan.name }))}
            onChange={(plan) => this.props.onChange({ ...ref, plan: plan || '' })}
          />
        )}
      </>
    );
  }
}
