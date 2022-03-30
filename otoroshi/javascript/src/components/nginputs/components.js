import React, { Component } from 'react';
import _ from 'lodash';

export class NgStepNotFound extends Component {
  render() {
    return (
      <h3>step not found {this.props.name}</h3>
    );
  }
}

export class NgRendererNotFound extends Component {
  render() {
    return (
      <h3>renderer not found {this.props.name}</h3>
    );
  }
}

export class NgValidationRenderer extends Component {
  render() {
    if (this.props.validation.__valid) {
      return this.props.children;
    } else {
      return (
        <div style={{ outline: '1px solid red', display: 'flex', flexDirection: 'column' }}>
          {this.props.children}
          <>
            {this.props.validation.__errors.map(err => <p style={{ color: 'red', marginBottom: 0 }}>{err.message || err}</p>)}
          </>
        </div>
      );
    };
  }
}

export class NgFormRenderer extends Component {
  state = { 
    folded: true
  }
  componentDidMount() {
    if (this.props && !this.props.embedded && this.props.rawSchema) {
      const folded = (
        (this.props.rawSchema.props ? this.props.rawSchema.props.collapsable : false) || this.props.rawSchema.collapsable
      ) && (
        (this.props.rawSchema.props ? this.props.rawSchema.props.collasped : false) || this.props.rawSchema.collasped
      ) 
      this.setState({ folded });
    }
  }
  render() {
    if (!this.props.embedded) {
      return (
        <form style={this.props.style} className={this.props.className}>
          {this.props.children}
        </form>
      );
    } else {
      if (!this.props.rawSchema) {
        return null;
      }
      const collapsable = this.props.rawSchema.props.collapsable || this.props.rawSchema.collapsable
      const noBorder = this.props.rawSchema.props.noBorder || this.props.rawSchema.noBorder
      const noTitle = this.props.rawSchema.props.noTitle || this.props.rawSchema.noTitle
      const title = (this.props.rawSchema.props.label || this.props.rawSchema.label || this.props.name || '...').replace(/_/g, ' ')
      const titleComponent = <span style={{ color: 'rgb(249, 176, 0)', fontWeight: 'bold', marginLeft: 5, marginTop: 7, marginBottom: 10 }}>{title}</span>
      if (collapsable) {
        return (
          <div style={{ outline: '1px solid yellow', padding: 5, margin: 5, display: 'flex', flexDirection: 'column', width: '100%' }}>
            <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
              {!noTitle && titleComponent}
              {!this.state.folded && <button type="button" className="btn btn-info float-end btn-sm" onClick={e => this.setState({ folded: !this.state.folded })}><i className="fas fa-eye-slash"></i></button>}
              {this.state.folded && <button type="button" className="btn btn-info float-end btn-sm" onClick={e => this.setState({ folded: !this.state.folded })}><i className="fas fa-eye"></i></button>}
            </div>
            {!this.state.folded && this.props.children}
          </div>
        );
      } else if (noBorder) {
        return (
          <div style={{ width: '100%' }}>
            {!noTitle && titleComponent}
            {this.props.children}
          </div>
        );
      } else {
        return (
          <div style={{ outline: '1px solid yellow', padding: 5, margin: 5, display: 'flex', flexDirection: 'column', width: '100%' }}>
            <div style={{ display: 'flex', flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' }}>
            {!noTitle && titleComponent}
            </div>
            {this.props.children}
          </div>
        );
      }
    }
  }
}
