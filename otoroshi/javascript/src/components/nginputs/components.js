import React, { Component } from 'react';
import isFunction from 'lodash/isFunction';
import get from 'lodash/get';

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
      this.props.setBreadcrumb(this.props.path);
    } else
      this.setState({
        folded: !this.state.folded,
      });
  };

  match = (test, breadcrumb) => {
    const lowerTest = test.join('-').toLowerCase();
    const lowerBreadcrumb = breadcrumb.join('-').toLowerCase();
    return lowerTest.startsWith(lowerBreadcrumb) || lowerBreadcrumb.startsWith(lowerTest);
  };

  getChildrenVisibility = (pathAsArray, breadcrumbAsArray) => {
    if (!this.props.setBreadcrumb) return !this.state.folded;

    if (this.props.breadcrumb === undefined) return false;

    return (
      pathAsArray.length <= breadcrumbAsArray.length && this.match(pathAsArray, breadcrumbAsArray)
    );
  };

  isAnObject = (v) => typeof v === 'object' && v !== null && !Array.isArray(v);
  firstLetterUppercase = (str) => str.charAt(0).toUpperCase() + str.slice(1);

  displaySummary = (fields, expectedSummaryFields) => {
    const subFilter = expectedSummaryFields.length > 0;
    const formattedFields = (fields || []).map((entry) => ({ key: entry[0], value: entry[1] }));

    const filteredFields = formattedFields.filter(({ key, value }) => {
      const isNotAnObject =
        !this.isAnObject(value) &&
        (Array.isArray(value)
          ? subFilter
            ? expectedSummaryFields.find((f) => f.startsWith(key))
            : false
          : true) &&
        value !== undefined &&
        (typeof value === 'boolean' ? true : value && value.length > 0);

      if (subFilter) {
        return isNotAnObject && expectedSummaryFields.find((f) => f.startsWith(key));
      } else {
        return isNotAnObject;
      }
    });

    if (filteredFields.length === 0) {
      return null;
    } else {
      return (
        <div className="d-flex mt-3 ms-3 flex-wrap">
          {filteredFields.map(({ key, value }) => {
            return (
              <div className="d-flex me-3 flex-wrap" key={key}>
                <span className="me-1" style={{ fontWeight: 'bold' }}>
                  {this.firstLetterUppercase(key)}:{' '}
                </span>
                {Array.isArray(value) ? (
                  value.map((item) => {
                    const path = expectedSummaryFields.find((f) => f.startsWith(key));
                    return <span key={item}>{get(item, (path.split('.') || []).slice(1))}</span>;
                  })
                ) : (
                  <span>{typeof value === 'boolean' ? (value ? ' true' : 'false') : value}</span>
                )}
              </div>
            );
          })}
        </div>
      );
    }
  };

  render() {
    const breadcrumbAsArray = this.props.breadcrumb || [];
    const pathAsArray = this.props.path || [];

    const showChildren = this.props.readOnly
      ? true
      : this.getChildrenVisibility(pathAsArray, breadcrumbAsArray);
    const clickable = !this.props.setBreadcrumb
      ? true
      : !breadcrumbAsArray.join('-').toLowerCase().startsWith(pathAsArray.join('-').toLowerCase());
    const isLeaf = !this.props.setBreadcrumb
      ? true
      : pathAsArray.length >= breadcrumbAsArray.length;

    if (!this.match(pathAsArray, breadcrumbAsArray)) return null;

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

      if (!this.props.rawSchema.props) {
        this.props.rawSchema.props = {};
      }

      const rawSchema = this.props.rawSchema;
      const rawSchemaProps = rawSchema.props;

      const collapsable = rawSchemaProps.collapsable || rawSchema.collapsable;
      const titleVar = rawSchemaProps.label || rawSchema.label || this.props.name;
      const summaryFields = this.props.readOnly
        ? []
        : rawSchemaProps.summaryFields || rawSchema.summaryFields || [];
      const showSummary =
        rawSchemaProps.showSummary || rawSchema.showSummary || summaryFields.length > 0;

      let title = '...';
      try {
        title = isFunction(titleVar) ? titleVar(this.props.value) : titleVar.replace(/_/g, ' ');
      } catch (e) {
        // console.log(e)
      }

      const noTitle = rawSchemaProps.noTitle || rawSchema.noTitle;
      const showTitle = !noTitle && (isLeaf || clickable);
      const summary = Object.entries(this.props.value || {});

      const titleComponent =
        !showChildren && showSummary ? (
          <div style={{ marginLeft: 5, marginTop: 7, marginBottom: 10 }}>
            <span style={{ color: 'rgb(249, 176, 0)', fontWeight: 'bold' }}>{title}</span>
            {summary.length > 0 && this.displaySummary(summary, summaryFields)}
          </div>
        ) : isFunction(titleVar) && React.isValidElement(title) && !showChildren ? (
          <div style={{ marginLeft: 5, marginTop: 7, marginBottom: 10 }}>{title}</div>
        ) : (
          <div
            style={{
              color: 'rgb(249, 176, 0)',
              fontWeight: 'bold',
              marginLeft: 5,
              marginTop: 7,
              marginBottom: 10,
            }}>
            {title}
          </div>
        );

      let EnabledTagComponent = null;

      if (
        rawSchema?.schema &&
        Object.keys(rawSchema?.schema).includes('enabled') &&
        this.props.value &&
        showTitle
      )
        EnabledTagComponent = (
          <span className={`badge bg-${this.props.value.enabled ? 'success' : 'danger'} me-3`}>
            {this.props.value.enabled ? 'Enabled' : 'Disabled'}
          </span>
        );

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
              ...(this.props.style || {}),
              ...(rawSchema.style || {}),
            }}
            onClick={() => {
              if (clickable) this.setBreadcrumb();
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
                {EnabledTagComponent}

                {!this.props.setBreadcrumb && !this.props.readOnly && (
                  <button
                    type="button"
                    className="btn btn-info float-end btn-sm"
                    onClick={this.setBreadcrumb}>
                    <i className={`fas fa-eye${this.state.folded ? '-slash' : ''}`} />
                  </button>
                )}
                {this.props.setBreadcrumb && clickable && !this.props.readOnly && (
                  <button
                    type="button"
                    className="btn btn-info float-end btn-sm"
                    onClick={this.setBreadcrumb}>
                    <i className="fas fa-chevron-right"></i>
                  </button>
                )}
              </div>
            </div>
            {showChildren && <div onClick={(e) => e.stopPropagation()}>{this.props.children}</div>}
          </div>
        );
      } else if (rawSchemaProps.noBorder || rawSchema.noBorder) {
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
