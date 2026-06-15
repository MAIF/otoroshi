import React from 'react';

export class LocalChangesRenderer extends React.Component {
  state = {
    folded: true,
  };

  toggle = () => this.setState({ folded: !this.state.folded });

  render() {
    const { validation, itemProps, onChange } = this.props;
    const Renderer = this.props.renderer;

    const { schema, flow } = itemProps;

    const v2Props = itemProps.schema.props?.v2 || {};

    const { folded } = this.state;

    return (
      <div
        className={`ng-v2-block ${folded ? 'ng-v2-block--folded' : 'ng-v2-block--open'}`}
        style={{ position: 'relative', minHeight: 120 }}
      >
        <button
          type="button"
          className="ng-v2-toggle"
          title={folded ? 'Edit' : 'Collapse'}
          onClick={this.toggle}
        >
          <i className={`fas ${folded ? 'fa-pen' : 'fa-chevron-up'}`} />
        </button>
        <Renderer
          validation={validation}
          {...itemProps}
          embedded
          readOnly={folded}
          onChange={onChange}
          schema={schema.schema || schema}
          flow={folded ? v2Props.folded : v2Props.flow}
          rawSchema={schema}
          rawFlow={flow}
        />
      </div>
    );
  }
}
