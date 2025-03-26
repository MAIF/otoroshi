import React from 'react';
import { NgForm } from '../../components/nginputs';

export class BackendForm extends React.Component {
  render() {
    const { form } = this.props.state;

    return (
      <div>
        <NgForm
          value={form.value}
          schema={form.schema}
          flow={form.flow}
          onChange={this.props.onChange}
        />
      </div>
    );
  }
}
