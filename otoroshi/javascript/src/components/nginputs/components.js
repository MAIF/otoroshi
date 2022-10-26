import React, { Component } from 'react';
import isEqual from 'lodash/isEqual';
import { isFunction } from 'lodash';

export class NgStepNotFound extends Component {
  render() {
    return <h3>step not found {this.props.name}</h3>;
  }
}

export class NgFlowNotFound extends Component {
  return() {
    return <h3>flow type not found {this.props.type}</h3>;
  }
}

export class NgRendererNotFound extends Component {
  render() {
    return <h3>renderer not found {this.props.name}</h3>;
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
            {this.props.validation.__errors.map((err, idx) => (
              <p key={idx} style={{ color: 'red', marginBottom: 0 }}>
                {err.message || err}
              </p>
            ))}
          </>
        </div>
      );
    }
  }
}

export class NgFormRenderer extends Component {

  state = {
    folded: true,
  };

  componentDidMount() {
    if (this.props && this.props.rawSchema) {
      const folded =
        ((this.props.rawSchema.props ? this.props.rawSchema.props.collapsable : false) ||
          this.props.rawSchema.collapsable) &&
        ((this.props.rawSchema.props ? this.props.rawSchema.props.collapsed : true) ||
          this.props.rawSchema.collapsed);

      this.setState({ folded: folded === undefined ? true : folded });
    }
  }

  setBreadcrumb = () => {
    if (this.props.setBreadcrumb) {
      this.props.setBreadcrumb(this.props.path)
    }
    else
      this.setState({
        folded: !this.state.folded
      })
  }

  match = (test, breadcrumb) => {
    const lowerTest = test.join('-').toLowerCase();
    const lowerBreadcrumb = breadcrumb.join('-').toLowerCase();
    return lowerTest.startsWith(lowerBreadcrumb) || lowerBreadcrumb.startsWith(lowerTest);
  }

  getChildrenVisibility = (pathAsArray, breadcrumbAsArray) => {
    if (!this.props.setBreadcrumb)
      return !this.state.folded;

    if (this.props.breadcrumb === undefined)
      return false;

    return pathAsArray.length <= breadcrumbAsArray.length && this.match(pathAsArray, breadcrumbAsArray);
  }

  isAnObject = v => typeof v === 'object' && v !== null && !Array.isArray(v);
  firstLetterUppercase = (str) => str.charAt(0).toUpperCase() + str.slice(1);

  displaySummary = (fields, expectedSummaryFields) => {
    const subFilter = expectedSummaryFields.length > 0;

    return (fields || [])
      .filter(entry => !this.isAnObject(entry[1]) && !Array.isArray(entry[1]) && entry[1] !== undefined && (typeof entry[1] === 'boolean' ? true : entry[1].length > 0))
      .filter(entry => subFilter ? expectedSummaryFields.includes(entry[0]) : true)
      .map(entry => {
        return <div className='d-flex me-3 flex-wrap' key={entry[0]}>
          <span className='me-1' style={{ fontWeight: 'bold' }}>{this.firstLetterUppercase(entry[0])}: </span>
          <span>{typeof entry[1] === 'boolean' ? (entry[1] ? ' true' : 'false') : entry[1]}</span>
        </div>
      })
  }

  render() {
    const breadcrumbAsArray = this.props.breadcrumb || [];
    const pathAsArray = this.props.path || [];

    const showChildren = this.props.readOnly ? true : this.getChildrenVisibility(pathAsArray, breadcrumbAsArray);
    const clickable = !this.props.setBreadcrumb ? true : !breadcrumbAsArray.join('-').toLowerCase()
      .startsWith(pathAsArray.join('-').toLowerCase());
    const isLeaf = !this.props.setBreadcrumb ? true : pathAsArray.length >= breadcrumbAsArray.length;

    if (!this.match(pathAsArray, breadcrumbAsArray))
      return null;

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
      if (!this.props.rawSchema.props) this.props.rawSchema.props = {};
      const collapsable = this.props.rawSchema.props.collapsable || this.props.rawSchema.collapsable;
      const noBorder = this.props.rawSchema.props.noBorder || this.props.rawSchema.noBorder;
      const noTitle = this.props.rawSchema.props.noTitle || this.props.rawSchema.noTitle;
      let title = '...';

      const titleVar = this.props.rawSchema.props.label ||
        this.props.rawSchema.label ||
        this.props.name;

      const summaryFields = this.props.readOnly ? [] : (this.props.rawSchema.props.summaryFields || this.props.rawSchema.summaryFields || []);

      let showSummary = false;

      if (this.props.rawSchema.props.showSummary || this.props.rawSchema.showSummary || (summaryFields.length > 0)) {
        showSummary = true
      }

      try {
        title = isFunction(titleVar) ? titleVar(this.props.value) : titleVar.replace(/_/g, ' ');
      } catch (e) {
        // console.log(e)
      }

      const showTitle = !noTitle && (isLeaf || clickable);
      const summary = Object.entries(this.props.value || {});

      const titleComponent = (!showChildren && showSummary) ?
        <div style={{ marginLeft: 5, marginTop: 7, marginBottom: 10 }}>
          <span style={{ color: 'rgb(249, 176, 0)', fontWeight: 'bold' }}>{title}</span>
          {summary.length > 0 && <div className='d-flex mt-3 ms-3 flex-wrap'>
            {this.displaySummary(summary, summaryFields)}
          </div>}
        </div>
        : isFunction(titleVar) && React.isValidElement(title) && !showChildren ?
          <div style={{ marginLeft: 5, marginTop: 7, marginBottom: 10 }}>{title}</div> :
          <div style={{
            color: 'rgb(249, 176, 0)',
            fontWeight: 'bold',
            marginLeft: 5,
            marginTop: 7,
            marginBottom: 10
          }}>{title}</div>

      let EnabledTagComponent = null

      if (this.props.rawSchema?.schema &&
        Object.keys(this.props.rawSchema?.schema).includes('enabled') &&
        this.props.value)
        EnabledTagComponent = <span className={`badge bg-${this.props.value.enabled ? 'success' : 'danger'} me-3`}>
          {this.props.value.enabled ? 'Enabled' : 'Disabled'}
        </span>

      if (collapsable) {
        return (
          <div
            style={{
              outline: clickable ? '1px solid #41413e' : 'none',
              padding: clickable ? 5 : 0,
              margin: clickable ? '5px 0' : '',
              display: 'flex',
              flexDirection: 'column',
              width: '100%',
              ...(this.props.rawSchema.style || {})
            }} onClick={() => {
              if (clickable)
                this.setBreadcrumb()
            }}>
            <div
              style={{
                display: 'flex',
                flexDirection: 'row',
                justifyContent: 'space-between',
                alignItems: 'center',
              }}>
              {showTitle && titleComponent}
              <div>
                {showTitle && EnabledTagComponent}

                {(!this.props.setBreadcrumb && !this.props.readOnly) && (
                  <button
                    type="button"
                    className="btn btn-info float-end btn-sm"
                    onClick={this.setBreadcrumb}>
                    <i className={`fas fa-eye${this.state.folded ? '-slash' : ''}`} />
                  </button>
                )}
                {(this.props.setBreadcrumb && clickable && !this.props.readOnly) && <button
                  type="button"
                  className="btn btn-info float-end btn-sm"
                  onClick={this.setBreadcrumb}>
                  <i className="fas fa-chevron-right"></i>
                </button>}
              </div>
            </div>
            {showChildren &&
              <div onClick={e => e.stopPropagation()}>
                {this.props.children}
              </div>}
          </div>
        );
      } else if (noBorder) {
        return (
          <div style={{ width: '100%' }}>
            {showTitle && titleComponent}
            {this.props.children}
          </div>
        );
      } else {
        return (
          <div
            style={{
              outline: clickable ? '1px solid #41413e' : 'none',
              padding: clickable ? 5 : 0,
              margin: clickable ? '5px 0' : '',
              display: 'flex',
              flexDirection: 'column',
              width: '100%',
            }}>
            <div
              style={{
                display: 'flex',
                flexDirection: 'row',
                justifyContent: 'space-between',
                alignItems: 'center',
              }}>
              {showTitle && titleComponent}
            </div>
            {this.props.children}
          </div>
        );
      }
    }
  }
}
