import React, { Component } from 'react';

export class Collapse extends Component {
  state = {
    collapsed: this.props.initCollapsed || this.props.collapsed,
  };

  toggle = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    this.setState({ collapsed: !this.state.collapsed });
  };

  componentWillReceiveProps(nextProps) {
    if (nextProps.collapsed !== this.props.collapsed) {
      this.setState({ collapsed: nextProps.collapsed });
    }
  }

  render() {
    if (this.props.notVisible) {
      return null;
    }
    if (this.state.collapsed) {
      return (
        <div>
          <hr />
          <div className="form__group grid-template-col-xs-up__1fr-5fr mb-10 mt-10">
            <label />
            <div className="display--flex justify-content--between cursor--pointer mb-10" onClick={this.toggle}>
              <h3>
                {this.props.label}
              </h3>
              <button
                type="button"
                className="btn-info btn-xs"
                onClick={this.toggle}>
                <i className="fas fa-eye" />
              </button>
            </div>
          </div>
          {this.props.lineEnd && <hr />}
        </div>
      );
    } else {
      return (
        <div>
          <hr />
          <div className="form__group grid-template-col-xs-up__1fr-5fr mb-10 mt-10">
            <label />
            <div className="display--flex justify-content--between cursor--pointer mb-10" onClick={this.toggle}>
              <h3>
                {this.props.label}
              </h3>
              <button
                type="button"
                className="btn-info btn-xs"
                onClick={this.toggle}>
                <i className="fas fa-eye-slash" />
              </button>
            </div>
          </div>
          {this.props.children}
          {this.props.lineEnd && <hr />}
        </div>
      );
    }
  }
}

export class Panel extends Component {
  state = {
    collapsed: this.props.initCollapsed || this.props.collapsed,
  };

  toggle = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    this.setState({ collapsed: !this.state.collapsed });
  };

  componentWillReceiveProps(nextProps) {
    if (nextProps.collapsed !== this.props.collapsed) {
      this.setState({ collapsed: nextProps.collapsed });
    }
  }

  render() {
    return (
      <div className="ml-5 mb-5 w-25 w-xs-100">
        <div className="panel panel-primary" style={{ marginBottom: 0 }}>
          <div className="panel-heading" style={{ cursor: 'pointer' }} onClick={this.toggle}>
            {this.props.title}
          </div>
          {!this.state.collapsed && <div className="panel-body">{this.props.children}</div>}
        </div>
      </div>
    );
  }
}
