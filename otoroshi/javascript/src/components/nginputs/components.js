import React, { Component } from 'react';
import isEqual from 'lodash/isEqual';

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

  match = (test, breadcrumb) => test.join('-').startsWith(breadcrumb.join('-')) ||
    breadcrumb.join('-').startsWith(test.join('-'));

  getChildrenVisibility = (pathAsArray, breadcrumbAsArray) => {
    if (!this.props.setBreadcrumb)
      return !this.state.folded;

    if (this.props.breadcrumb === undefined)
      return false;

    console.log(pathAsArray, breadcrumbAsArray,
      pathAsArray.length <= breadcrumbAsArray.length && this.match(pathAsArray, breadcrumbAsArray))

    return pathAsArray.length <= breadcrumbAsArray.length && this.match(pathAsArray, breadcrumbAsArray);
  }

  render() {
    const breadcrumbAsArray = this.props.breadcrumb || [];
    const pathAsArray = this.props.path || [];

    const showChildren = this.getChildrenVisibility(pathAsArray, breadcrumbAsArray)
    const clickable = !this.props.setBreadcrumb ? true : !breadcrumbAsArray.join('-').startsWith(pathAsArray.join('-'));
    const isLeaf = !this.props.setBreadcrumb ? true : pathAsArray.length >= breadcrumbAsArray.length;

    if (!this.match(pathAsArray, breadcrumbAsArray))
      return null

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
      const collapsable =
        this.props.rawSchema.props.collapsable || this.props.rawSchema.collapsable;
      const noBorder = this.props.rawSchema.props.noBorder || this.props.rawSchema.noBorder;
      const noTitle = this.props.rawSchema.props.noTitle || this.props.rawSchema.noTitle;
      const title = (
        this.props.rawSchema.props.label ||
        this.props.rawSchema.label ||
        this.props.name ||
        '...'
      ).replace(/_/g, ' ');
      const showTitle = !noTitle && (isLeaf || clickable);


      const titleComponent = (
        <span
          style={{
            color: 'rgb(249, 176, 0)',
            fontWeight: 'bold',
            marginLeft: 5,
            marginTop: 7,
            marginBottom: 10,
          }}>
          {title}
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
              {!this.props.setBreadcrumb && (
                <button
                  type="button"
                  className="btn btn-info float-end btn-sm"
                  onClick={this.setBreadcrumb}>
                  <i className={`fas fa-eye${this.state.folded ? '-slash' : ''}`} />
                </button>
              )}
              {(this.props.setBreadcrumb && clickable) && <button
                type="button"
                className="btn btn-info float-end btn-sm"
                onClick={this.setBreadcrumb}>
                <i className="fas fa-chevron-right"></i>
              </button>}
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
