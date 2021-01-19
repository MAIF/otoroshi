import React, { Component } from 'react';
import PropTypes from 'prop-types';
import { Form } from '.';
import _ from 'lodash';
import { createTooltip } from '../../tooltips';
import YAML from 'yaml';

import ReactTable from 'react-table';

function urlTo(url) {
  window.history.replaceState({}, '', url);
}

function LoadingComponent(props) {
  return (
    <div
      className="loadingPage"
      style={{
        display:
          props.loading && props.loadingText && props.loadingText.trim().length > 0
            ? 'flex'
            : 'none',
      }}>
      {props.loadingText}
    </div>
  );
}

export class Table extends Component {
  static propTypes = {
    itemName: PropTypes.string.isRequired,
    columns: PropTypes.array.isRequired,
    fetchItems: PropTypes.func.isRequired,
    updateItem: PropTypes.func,
    deleteItem: PropTypes.func,
    createItem: PropTypes.func,
    navigateTo: PropTypes.func,
    stayAfterSave: PropTypes.bool.isRequired,
    showActions: PropTypes.bool.isRequired,
    showLink: PropTypes.bool.isRequired,
    formSchema: PropTypes.object,
    formFlow: PropTypes.array,
    extractKey: PropTypes.func.isRequired,
    defaultValue: PropTypes.func,
    rowNavigation: PropTypes.bool.isRequired,
  };

  static defaultProps = {
    rowNavigation: false,
    stayAfterSave: false,
    pageSize: 15,
  };

  state = {
    items: [],
    showAddForm: false,
    showEditForm: false,
    loading: false,
    hasError: false,
  };

  componentDidMount() {
    this.registerSizeChanges();
    this.update().then(() => {
      if (this.props.search) {
        console.log('Todo: default search');
      }
    });
    if (this.props.injectTable) {
      this.props.injectTable(this);
    }
    this.readRoute();
  }

  registerSizeChanges = () => {
    this.sizeListener = _.debounce((e) => {
      this.forceUpdate();
    }, 400);
    window.addEventListener('resize', this.sizeListener);
  };

  componentWillUnmount() {
    window.removeEventListener('resize', this.sizeListener);
    this.unmountShortcuts();
  }

  componentDidCatch(err, info) {
    this.setState({ hasError: true });
    console.log('Table has error', err, info);
  }

  readRoute = () => {
    if (this.props.parentProps.params.taction) {
      const action = this.props.parentProps.params.taction;
      if (action === 'add') {
        this.showAddForm();
      } else if (action === 'edit') {
        const item = this.props.parentProps.params.titem;
        this.props.fetchItems().then((data) => {
          //console.log(this.props.parentProps.params);
          //console.log(data);
          const row = data.filter((d) => this.props.extractKey(d) === item)[0];
          this.showEditForm(null, row);
        });
      }
    }
  };

  mountShortcuts = () => {
    document.body.addEventListener('keydown', this.saveShortcut);
  };

  unmountShortcuts = () => {
    document.body.removeEventListener('keydown', this.saveShortcut);
  };

  saveShortcut = (e) => {
    if (e.keyCode === 83 && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      if (this.state.showEditForm) {
        this.updateItem();
      }
      if (this.state.showAddForm) {
        this.createItem();
      }
    }
  };

  update = () => {
    this.setState({ loading: true });
    return this.props.fetchItems().then(
      (rawItems) => {
        this.setState({ items: rawItems, loading: false }, () => {
          if (this.props.onUpdate) {
            this.props.onUpdate(rawItems);
          }
        });
      },
      () => this.setState({ loading: false })
    );
  };

  gotoItem = (e, item) => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.navigateTo(item);
  };

  closeAddForm = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    this.unmountShortcuts();
    this.props.parentProps.setTitle(this.props.defaultTitle);
    this.setState({ currentItem: null, showAddForm: false });
    this.update();
    urlTo(`/bo/dashboard/${this.props.selfUrl}`);
  };

  showAddForm = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    this.mountShortcuts();
    this.props.parentProps.setTitle(`Create a new ${this.props.itemName}`);
    urlTo(`/bo/dashboard/${this.props.selfUrl}/add`);
    const defVal = this.props.defaultValue();
    if (defVal.then) {
      defVal.then((v) => this.setState({ currentItem: v, showAddForm: true }));
    } else {
      this.setState({ currentItem: defVal, showAddForm: true });
    }
  };

  closeEditForm = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    this.unmountShortcuts();
    this.props.parentProps.setTitle(this.props.defaultTitle);
    this.setState({ currentItem: null, showEditForm: false });
    this.update();
    urlTo(`/bo/dashboard/${this.props.selfUrl}`);
  };

  showEditForm = (e, item) => {
    if (e && e.preventDefault) e.preventDefault();
    this.mountShortcuts();
    urlTo(`/bo/dashboard/${this.props.selfUrl}/edit/${this.props.extractKey(item)}`);
    this.props.parentProps.setTitle(`Update a ${this.props.itemName}`);
    this.setState({ currentItem: item, showEditForm: true });
  };

  deleteItem = (e, item) => {
    if (e && e.preventDefault) e.preventDefault();
    window.newConfirm('Are you sure you want to delete that item ?').then((ok) => {
      if (ok) {
        this.props
          .deleteItem(item)
          .then(() => {
            return this.props.fetchItems();
          })
          .then((items) => {
            urlTo(`/bo/dashboard/${this.props.selfUrl}`);
            this.setState({ items, showEditForm: false, showAddForm: false });
          });
      }
    });
  };

  createItem = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    this.props
      .createItem(this.state.currentItem)
      .then(() => {
        return this.props.fetchItems();
      })
      .then((items) => {
        urlTo(`/bo/dashboard/${this.props.selfUrl}`);
        this.setState({ items, showAddForm: false });
      });
  };

  createItemAndStay = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.createItem(this.state.currentItem).then(() => {
      urlTo(
        `/bo/dashboard/${this.props.selfUrl}/edit/${this.props.extractKey(this.state.currentItem)}`
      );
      this.setState({ showAddForm: false, showEditForm: true });
    });
  };

  updateItem = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    this.props
      .updateItem(this.state.currentItem)
      .then(() => {
        return this.props.fetchItems();
      })
      .then((items) => {
        this.setState({ items, showEditForm: false });
      });
  };

  updateItemAndStay = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.updateItem(this.state.currentItem);
  };

  exportJson = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    const name = (this.state.currentItem.name || this.state.currentItem.clientName)
      .replace(/ /g, '-')
      .replace(/\(/g, '')
      .replace(/\)/g, '')
      .toLowerCase();
    const itemName = this.props.itemName
      .replace(/ /g, '-')
      .replace(/\(/g, '')
      .replace(/\)/g, '')
      .toLowerCase();
    const json = JSON.stringify(this.state.currentItem, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.id = String(Date.now());
    a.style.display = 'none';
    a.download = `${itemName}-${name}-${Date.now()}.json`;
    a.href = url;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => document.body.removeChild(a), 300);
  };

  exportYaml = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    const name = (this.state.currentItem.name || this.state.currentItem.clientName)
      .replace(/ /g, '-')
      .replace(/\(/g, '')
      .replace(/\)/g, '')
      .toLowerCase();
    const itemName = this.props.itemName
      .replace(/ /g, '-')
      .replace(/\(/g, '')
      .replace(/\)/g, '')
      .toLowerCase();
    const json = YAML.stringify({
      apiVersion: 'proxy.otoroshi.io/v1alpha1',
      kind: this.props.kubernetesKind,
      metadata: {
        name,
      },
      spec: this.state.currentItem,
    });
    const blob = new Blob([json], { type: 'application/yaml' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.id = String(Date.now());
    a.style.display = 'none';
    a.download = `${itemName}-${name}-${Date.now()}.yaml`;
    a.href = url;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => document.body.removeChild(a), 300);
  };

  render() {
    if (this.state.hasError) {
      return <h3>Something went wrong !!!</h3>;
    }
    const windowWidth = window.innerWidth;
    const columns = this.props.columns
      .map((c) => {
        return {
          Header: c.title,
          id: c.title,
          headerStyle: c.style,
          width: c.style && c.style.width ? c.style.width : undefined,
          style: { ...c.style, height: 30 },
          sortable: !c.notSortable,
          filterable: !c.notFilterable,
          accessor: (d) => (c.content ? c.content(d) : d),
          Filter: (d) => (
            <input
              type="text"
              className="input-sm"
              value={d.filter ? d.filter.value : ''}
              onChange={(e) => d.onChange(e.target.value)}
              placeholder="Search ..."
            />
          ),
          Cell: (r) => {
            const value = r.value;
            const original = r.original;
            return c.cell ? (
              c.cell(value, original, this)
            ) : (
              <div
                onClick={(e) => {
                  if (this.props.rowNavigation) {
                    if (e.metaKey) {
                      if (this.props.itemUrl) {
                        const a = document.createElement('a');
                        a.setAttribute('target', '_blank');
                        a.setAttribute('href', this.props.itemUrl(original));
                        a.click();
                      }
                    } else {
                      this.gotoItem(e, original);
                  }
                }
              }}
              style={{ cursor: 'pointer', width: '100%' }}>
              {c.wrappedCell ? c.wrappedCell(value, original, this) : value}
            </div>
          );
        },
      };
    });
    if (this.props.showActions) {
      columns.push({
        Header: 'Actions',
        id: 'actions',
        width: 140,
        style: { textAlign: 'center' },
        filterable: false,
        accessor: (item) => (
          <td style={{ width: 140, textAlign: 'center' }}>
            <div className="displayGroupBtn">
              <button
                type="button"
                className="btn-sm btn-success"
                {...createTooltip(`Edit this ${this.props.itemName}`, 'top', true)}
                onClick={(e) => this.showEditForm(e, item)}>
                <i className="fas fa-pencil-alt" />
              </button>
              {this.props.showLink && (
                <a
                  className="button btn-sm btn-info ml-5"
                  {...createTooltip(`Open this ${this.props.itemName}`, 'top', true)}
                  href={`${this.props.itemUrl(item)}`}
                  _onClick={(e) => this.gotoItem(e, item)}>
                  <i className="fas fa-link" />
                </a>
              )}
              {this.props.displayTrash && this.props.displayTrash(item) && (
                <button
                  type="button"
                  className="btn-sm btn-danger ml-5"
                  disabled
                  {...createTooltip(`Delete this ${this.props.itemName}`, 'top', true)}>
                  <i className="fas fa-trash" />
                </button>
              )}
              {this.props.displayTrash && !this.props.displayTrash(item) && (
                <button
                  type="button"
                  className="btn-sm btn-danger ml-5"
                  onClick={(e) => this.deleteItem(e, item)}
                  {...createTooltip(`Delete this ${this.props.itemName}`, 'top', true)}>
                  <i className="fas fa-trash" />
                </button>
              )}
              {!this.props.displayTrash && (
                <button
                  type="button"
                  className="btn-sm btn-danger ml-5"
                  {...createTooltip(`Delete this ${this.props.itemName}`, 'top', true)}
                  onClick={(e) => this.deleteItem(e, item)}>
                  <i className="fas fa-trash" />
                </button>
              )}
            </div>
          </td>
        ),
      });
    }
    return (
      <div>
        {!this.state.showEditForm && !this.state.showAddForm && (
          <div>
            <div style={{ marginBottom: 10, marginTop: 2 }}>
              <div className="">
                <button
                  type="button"
                  className="btn-info"
                  {...createTooltip('Reload the current table')}
                  onClick={this.update}>
                  <span className="fas fa-sync" />
                </button>
                {this.props.showActions && (
                  <button
                    type="button"
                    className="btn-info"
                    style={{ marginLeft: 10 }}
                    onClick={this.showAddForm}
                    {...createTooltip(`Create a new ${this.props.itemName}`)}>
                    <span className="fas fa-plus-circle" /> Add item
                  </button>
                )}
                {this.props.injectTopBar && this.props.injectTopBar()}
              </div>
            </div>
            <div className="rrow">
              <ReactTable
                className="fulltable -striped -highlight"
                data={this.state.items}
                loading={this.state.loading}
                filterable={true}
                filterAll={true}
                defaultSorted={[
                  {
                    id: this.props.defaultSort || this.props.columns[0].title,
                    desc: this.props.defaultSortDesc || false,
                  },
                ]}
                defaultFiltered={
                  this.props.search
                    ? [{ id: this.props.columns[0].title, value: this.props.search }]
                    : []
                }
                defaultPageSize={this.props.pageSize}
                columns={columns}
                LoadingComponent={LoadingComponent}
                defaultFilterMethod={(filter, row, column) => {
                  const id = filter.pivotId || filter.id;
                  if (row[id] !== undefined) {
                    const value = String(row[id]);
                    return value.toLowerCase().indexOf(filter.value.toLowerCase()) > -1;
                  } else {
                    return true;
                  }
                }}
              />
            </div>
          </div>
        )}
        {this.state.showAddForm && (
          <div className="" role="dialog">
            {this.props.formComponent && [
              this.props.injectToolbar
                ? this.props.injectToolbar(this.state, (s) => this.setState(s))
                : null,
              <form style={this.props.style}>
                {React.createElement(this.props.formComponent, {
                  onChange: (currentItem) => this.setState({ currentItem }),
                  value: this.state.currentItem,
                  ...(this.props.formPassProps || {}),
                })}
              </form>,
            ]}
            {!this.props.formComponent && (
              <Form
                value={this.state.currentItem}
                onChange={(currentItem) => this.setState({ currentItem })}
                flow={this.props.formFlow}
                schema={this.props.formSchema}
              />
            )}
            <hr />
            <div className="btn__group--right">
              <button type="button" className="btn-danger" onClick={this.closeAddForm}>
                Cancel
              </button>
              {this.props.stayAfterSave && (
                <button type="button" className="btn-info ml-5" onClick={this.createItemAndStay}>
                  <i className="fas fa-hdd" /> Create and stay on this {this.props.itemName}
                </button>
              )}
              <button type="button" className="btn-info ml-5" onClick={this.createItem}>
                <i className="fas fa-hdd" /> Create {this.props.itemName}
              </button>
            </div>
          </div>
        )}
        {this.state.showEditForm && (
          <div className="" role="dialog">
            {this.props.formComponent && [
              this.props.injectToolbar
                ? this.props.injectToolbar(this.state, (s) => this.setState(s))
                : null,
              <form style={this.props.style}>
                {React.createElement(this.props.formComponent, {
                  onChange: (currentItem) => {
                    this.setState({ currentItem });
                  },
                  value: this.state.currentItem,
                  ...(this.props.formPassProps || {}),
                })}
              </form>,
            ]}
            {!this.props.formComponent && (
              <Form
                value={this.state.currentItem}
                onChange={(currentItem) => this.setState({ currentItem })}
                flow={this.props.formFlow}
                schema={this.props.formSchema}
              />
            )}
            <hr />
            <div className="btn__group--right">
              {this.props.export && (
                <>
                  <button
                    onClick={this.exportJson}
                    type="button"
                    className="btn-info"
                    title="Export as json">
                    <i className="fas fa-file-export" /> JSON
                  </button>
                  <button
                    onClick={this.exportYaml}
                    type="button"
                    className="btn-info ml-5"
                    title="Export as yaml">
                    <i className="fas fa-file-export" /> YAML
                  </button>
                </>
              )}
              {this.props.displayTrash && this.props.displayTrash(this.state.currentItem) && (
                <button
                  type="button"
                  className="btn-danger ml-5"
                  title="Delete current item"
                  disabled>
                  <i className="fas fa-trash" /> Delete
                </button>
              )}
              {this.props.displayTrash && !this.props.displayTrash(this.state.currentItem) && (
                <button
                  type="button"
                  className="btn-danger ml-5"
                  title="Delete current item"
                  onClick={(e) => this.deleteItem(e, this.state.currentItem)}>
                  <i className="fas fa-trash" /> Delete
                </button>
              )}
              {!this.props.displayTrash && (
                <button
                  type="button"
                  className="btn-danger ml-5"
                  title="Delete current item"
                  onClick={(e) => this.deleteItem(e, this.state.currentItem)}>
                  <i className="fas fa-trash" /> Delete
                </button>
              )}
              <button type="button" className="btn-danger ml-5" onClick={this.closeEditForm}>
                <i className="fas fa-times" /> Cancel
              </button>
              {this.props.stayAfterSave && (
                <button type="button" className="btn-success ml-5" onClick={this.updateItemAndStay}>
                  <i className="fas fa-hdd" /> Update and stay on this {this.props.itemName}
                </button>
              )}
              <button type="button" className="btn-success ml-5" onClick={this.updateItem}>
                <i className="fas fa-hdd" /> Update {this.props.itemName}
              </button>
            </div>
          </div>
        )}
      </div>
    );
  }
}
