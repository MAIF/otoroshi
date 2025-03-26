import React from 'react';
import { Help } from './Help';
import bcrypt from 'bcryptjs';

export class ObjectInput extends React.Component {
  state = {
    data: Object.entries(this.props.value || {}).map(([key, value], i) => ({
      idx: i,
      key,
      value,
    })),
  };

  componentDidUpdate(prevProps) {
    if (prevProps.value !== this.props.value) {
      const newData = Object.entries(this.props.value || {}).map(([key, value], i) => ({
        idx: i,
        key,
        value,
      }));
      this.setState({
        data: newData,
      });
    }
  }

  changeValue = (idx, key, e) => {
    if (e && e.preventDefault) e.preventDefault();
    this.setState(
      {
        data: this.state.data.map((item) => {
          if (item.idx === idx) return { ...item, value: e.target.value };
          return item;
        }),
      },
      () => this.onChange(this.state.data)
    );
  };

  changeKey = (idx, key, e) => {
    if (e && e.preventDefault) e.preventDefault();
    this.setState(
      {
        data: this.state.data.map((item) => {
          if (item.idx === idx) return { ...item, key: e.target.value };
          return item;
        }),
      },
      () => this.onChange(this.state.data)
    );
  };

  addFirst = (e) => {
    if (e && e.preventDefault) e.preventDefault();

    this.setState(
      {
        data: [{ key: '', value: '', idx: 0 }, ...this.state.data].map((item, i) => ({
          ...item,
          idx: i,
        })),
      },
      () => this.onChange(this.state.data)
    );
  };

  addNext = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    const out = [...this.state.data, { key: '', value: '', idx: this.state.data.length }];
    this.setState(
      {
        data: out.map((item, i) => ({ ...item, idx: i })),
      },
      () => this.onChange(this.state.data)
    );
  };

  remove = (idx, key, e) => {
    if (e && e.preventDefault) e.preventDefault();

    this.setState(
      {
        data: this.state.data
          .filter((item) => item.idx !== idx)
          .map((item, i) => ({ ...item, idx: i })),
      },
      () => this.onChange(this.state.data)
    );
  };

  onChange = (out) => {
    if ([...new Set(out.map((o) => o.key))].length === out.length) {
      this.props.onChange(
        out.reduce(
          (acc, curr) => ({
            ...acc,
            [curr.key]: curr.value,
          }),
          {}
        )
      );
    } else {
      console.log('baised');
    }
  };

  disableBcrypt = (value) => {
    if (!value) return false;
    return (
      value.startsWith('$2a$') ||
      value.startsWith('$2$') ||
      value.startsWith('$2b$') ||
      value.startsWith('$2x$') ||
      value.startsWith('$2y$')
    );
  };

  render() {
    const { data } = this.state;
    const props = this.props;
    return (
      <div>
        {data.length === 0 && (
          <div className="row mb-3">
            {!props.ngOptions?.spread && (
              <label htmlFor={`input-${props.label}`} className="col-xs-12 col-sm-2 col-form-label">
                {props.label} <Help text={props.help} />
              </label>
            )}
            <div className={`${props.ngOptions?.spread ? 'col-sm-12' : 'col-sm-10'}`}>
              <button
                disabled={props.disabled}
                type="button"
                className="btn btn-primary"
                onClick={this.addFirst}
              >
                <i className="fas fa-plus-circle" />{' '}
              </button>
            </div>
          </div>
        )}
        {data.map(({ key, value, idx }, i) => (
          <div className="row mb-3" key={`keys-${idx}`}>
            {i === 0 && !props.ngOptions?.spread && (
              <label className="col-xs-12 col-sm-2 col-form-label">
                {props.label} <Help text={props.help} />
              </label>
            )}
            {i > 0 && !props.ngOptions?.spread && (
              <label className="col-xs-12 col-sm-2 col-form-label">&nbsp;</label>
            )}
            <div className={`${props.ngOptions?.spread ? 'col-sm-12' : 'col-sm-10'}`}>
              <div className="input-group justify-content-between">
                {props.itemRenderer &&
                  props.itemRenderer(
                    key,
                    value,
                    idx,
                    (e) => this.changeKey(idx, key, e),
                    (e) => this.changeValue(idx, key, e)
                  )}
                {!props.itemRenderer && (
                  <>
                    <input
                      disabled={props.disabled}
                      type="text"
                      className="form-control"
                      placeholder={props.placeholderKey}
                      value={key}
                      onChange={(e) => this.changeKey(idx, key, e)}
                    />
                    {props.valueRenderer &&
                      props.valueRenderer(key, value, idx, (e) => this.changeValue(idx, key, e))}
                    {!props.valueRenderer && (
                      <>
                        <input
                          disabled={props.disabled}
                          type="text"
                          className="form-control"
                          placeholder={props.placeholderValue}
                          value={value}
                          onChange={(e) => this.changeValue(idx, key, e)}
                        />
                        {props.bcryptable ? (
                          <button
                            className="btn btn-outline-secondary"
                            type="button"
                            disabled={this.disableBcrypt(value)}
                            onClick={(e) => {
                              this.changeValue(idx, key, {
                                target: { value: bcrypt.hashSync(value, 10) },
                              });
                            }}
                          >
                            bcrypt
                          </button>
                        ) : null}
                      </>
                    )}
                  </>
                )}
                <span className="input-group-btn">
                  <button
                    disabled={props.disabled}
                    type="button"
                    className="btn btn-danger"
                    onClick={(e) => this.remove(idx, key, e)}
                  >
                    <i className="fas fa-trash" />
                  </button>
                  {i === data.length - 1 && (
                    <button
                      disabled={props.disabled}
                      type="button"
                      className="btn btn-primary"
                      onClick={this.addNext}
                    >
                      <i className="fas fa-plus-circle" />{' '}
                    </button>
                  )}
                </span>
              </div>
            </div>
          </div>
        ))}
      </div>
    );
  }
}

export class VerticalObjectInput extends React.Component {
  changeValue = (e, name) => {
    if (e && e.preventDefault) e.preventDefault();
    const newValues = { ...this.props.value, [name]: e.target.value };
    this.props.onChange(newValues);
  };

  changeKey = (e, oldName) => {
    if (e && e.preventDefault) e.preventDefault();
    const newValues = { ...this.props.value };
    const oldValue = newValues[oldName];
    delete newValues[oldName];
    newValues[e.target.value] = oldValue;
    this.props.onChange(newValues);
  };

  addFirst = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    if (!this.props.value || Object.keys(this.props.value).length === 0) {
      this.props.onChange(this.props.defaultValue || { '': '' });
    }
  };

  addNext = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    const newItem = this.props.defaultValue || { '': '' };
    const newValues = { ...this.props.value, ...newItem };
    this.props.onChange(newValues);
  };

  remove = (e, name) => {
    if (e && e.preventDefault) e.preventDefault();
    const newValues = { ...this.props.value };
    delete newValues[name];
    this.props.onChange(newValues);
  };

  render() {
    const values = Object.keys(this.props.value || {}).map((k) => [k, this.props.value[k]]);
    return (
      <div>
        {values.length === 0 && (
          <div className="row mb-3">
            <div className="col-xs-12">
              <label htmlFor={`input-${this.props.label}`} className="col-form-label">
                {this.props.label} <Help text={this.props.help} />
              </label>
              <div>
                <button
                  disabled={this.props.disabled}
                  type="button"
                  className="btn btn-primary"
                  onClick={this.addFirst}
                >
                  <i className="fas fa-plus-circle" />{' '}
                </button>
              </div>
            </div>
          </div>
        )}
        {values.map((value, idx) => (
          <div className="mb-3" style={{ marginBottom: 5 }}>
            <div className="col-xs-12">
              {idx === 0 && (
                <label className="col-form-label">
                  {this.props.label} <Help text={this.props.help} />
                </label>
              )}
              {idx > 0 && false && <label className="col-form-label">&nbsp;</label>}
              <div className="input-group align-items-center">
                <input
                  disabled={this.props.disabled}
                  type="text"
                  className="form-control"
                  placeholder={this.props.placeholderKey}
                  value={value[0]}
                  onChange={(e) => this.changeKey(e, value[0])}
                />
                <input
                  disabled={this.props.disabled}
                  type="text"
                  className="form-control"
                  placeholder={this.props.placeholderValue}
                  value={value[1]}
                  onChange={(e) => this.changeValue(e, value[0])}
                />
                <span className="input-group-btn">
                  <button
                    disabled={this.props.disabled}
                    type="button"
                    className="btn btn-sm btn-danger"
                    style={{ marginRight: 0 }}
                    onClick={(e) => this.remove(e, value[0])}
                  >
                    <i className="fas fa-trash" />
                  </button>
                </span>
              </div>
              {idx === values.length - 1 && (
                <div
                  style={{
                    display: 'flex',
                    width: '100%',
                    justifyContent: 'center',
                    alignItems: 'center',
                    marginTop: 5,
                  }}
                >
                  <button
                    disabled={this.props.disabled}
                    type="button"
                    className="btn btn-sm btn-block btn-primary"
                    style={{ marginRight: 0 }}
                    onClick={this.addNext}
                  >
                    <i className="fas fa-plus-circle" />{' '}
                  </button>
                </div>
              )}
            </div>
          </div>
        ))}
      </div>
    );
  }
}
