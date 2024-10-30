import React, { Component, useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { Form, OffSwitch, OnSwitch } from '.';
import { NgForm } from '../nginputs/form';
import debounce from 'lodash/debounce';
import { createTooltip } from '../../tooltips';
import ReactTable from 'react-table';
import { LabelAndInput, NgCodeRenderer, NgSelectRenderer, NgStringRenderer, NgTextRenderer } from '../nginputs';
import _ from 'lodash';
import { Button } from '../Button';
import { firstLetterUppercase } from '../../util'
import { DraftEditorContainer, DraftStateDaemon } from '../Drafts/DraftEditor';
import { updateEntityURLSignal } from '../Drafts/DraftEditorSignal';

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
      }}
    >
      {props.loadingText}
    </div>
  );
}

function ColumnsSelector({ fields, onChange, fetchTemplate, addField, removeField, coreFields }) {
  const [open, setOpen] = useState(false)
  const [isCustomFieldView, showCustomField] = useState(false)

  const [template, setTemplate] = useState()

  const [fieldPath, setFieldPath] = useState("")
  const [fieldExampleValue, setFieldExampleValue] = useState("")

  useEffect(() => {
    if (!template)
      fetchTemplate()
        .then(setTemplate)
  }, [])

  useEffect(() => {
    if (template)
      setFieldExampleValue(fieldPath.split('.').reduce((r, k) => r ? r[k] : {}, template))
  }, [fieldPath])

  const closeTab = () => {
    setOpen(false)
    showCustomField(false)
  }

  const stringifiedExampleValue = JSON.stringify(fieldExampleValue !== undefined ? fieldExampleValue : {}, null, 2)

  return <>
    <div className={`wizard ${!open ? 'wizard--hidden' : ''}`}
      style={{
        background: 'none'
      }} onClick={closeTab}>
      <div className={`wizard-container ${!open ? 'wizard--hidden' : ''}`} style={{
        maxWidth: isCustomFieldView ? '50vw' : '30vw',
        minWidth: '360px',
        zIndex: 1000,
        border: 'var(--bg-color_level2) solid 1px'
      }} onClick={e => e.stopPropagation()}>
        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', padding: '2.5rem' }}>
          <div className='d-flex items-center'>
            <i className='fa fa-chevron-left me-3'
              style={{ cursor: 'pointer' }}
              onClick={() => {
                if (isCustomFieldView)
                  showCustomField(false)
                else
                  closeTab()
              }} />
            <h3 style={{ fontSize: '1.5rem' }} className='text-center'>{isCustomFieldView ? 'New custom field' : 'Columns'}</h3>
          </div>

          <div className="wizard-content">
            {isCustomFieldView && <>
              <NgStringRenderer label="Field path" onChange={setFieldPath} value={fieldPath} />
              <Button
                type="save"
                text="Add custom field"
                onClick={() => addField(fieldPath)}
                className='my-2'
                disabled={stringifiedExampleValue.length <= 0} />

              <div style={{
                border: 'var(--bg-color_level2) solid 1px',
                borderRadius: '4px',
              }} className='p-2 mb-2'>
                <NgCodeRenderer
                  label="Field value (example)"
                  value={stringifiedExampleValue}
                  readOnly
                  rawSchema={{
                    props: {
                      editorOnly: true,
                      height: '100%',
                      ace_config: {
                        fontSize: 14
                      }
                    }
                  }} />
              </div>
              <div style={{
                border: 'var(--bg-color_level2) solid 1px',
                borderRadius: '4px',
              }} className='p-2'>
                <NgCodeRenderer
                  label="Content"
                  readOnly
                  rawSchema={{
                    props: {
                      editorOnly: true,
                      height: '100%',
                      ace_config: {
                        fontSize: 14
                      }
                    }
                  }}
                  value={JSON.stringify(template, null, 2)}
                />
              </div>
            </>}
            {!isCustomFieldView && <>
              <div className='d-flex flex-column hidden-scrollbar' style={{ overflowY: 'scroll' }}>
                {Object.entries(fields)
                  .map(([column, enabled]) => {
                    const columnParts = column.split(".");

                    return <div className="mb-1 p-1 px-3 d-flex" style={{
                      border: 'var(--bg-color_level2) solid 1px',
                      width: '100%',
                      borderRadius: '4px',
                      cursor: 'pointer',
                      justifyContent: 'space-between'
                    }} onClick={() => onChange(column, !enabled)} key={column}>
                      <div className='d-flex items-center'>
                        {coreFields && !coreFields.includes(column) &&
                          <Button className='btn-sm me-2' type='danger' onClick={e => {
                            e.stopPropagation()
                            removeField(column)
                          }}>
                            <i className='fa fa-trash' />
                          </Button>}
                        <label className={`col-xs-12 col-form-label`}>
                          {firstLetterUppercase(columnParts.slice(-1)[0]).replace(/_/g, ' ')}{' '}
                        </label>
                      </div>
                      <div className="">
                        {enabled && <OnSwitch onChange={() => onChange(column, false)} />}
                        {!enabled && <OffSwitch onChange={() => onChange(column, true)} />}
                      </div>
                    </div>
                  })}
              </div>
              <Button
                className='d-flex items-center mt-1 py-2'
                style={{
                  justifyContent: 'space-between'
                }}
                onClick={() => showCustomField(true)}>
                Add custom field
                <i className='fa fa-chevron-right ms-1' />
              </Button>
            </>}
          </div>


          <Button text="Close" onClick={closeTab} className='mt-2' />
        </div>
      </div>
    </div>
    <Button type="info" className='ms-auto btn-sm d-flex align-items-center mb-1'
      onClick={() => setOpen(true)}>
      <i className='fas fa-filter me-1' />
      Filter columns
    </Button>
  </>
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
    pages: 1,
    showAddForm: false,
    showEditForm: false,
    loading: false,
    hasError: false,
    rowsPerPage: this.props.pageSize || 15,
    page: 0,
  };

  tableRef = React.createRef();

  componentDidMount() {
    this.registerSizeChanges();

    this.setPlaceholders();

    if (this.props.injectTable) {
      this.props.injectTable(this);
    }

    this.readRoute();
  }

  registerSizeChanges = () => {
    this.sizeListener = debounce((e) => {
      this.forceUpdate();
      this.setPlaceholders();
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

  setPlaceholders = () => {
    [...document.querySelectorAll('.rt-table input[type=text]')].map((r) =>
      r.setAttribute('placeholder', 'Search ...')
    );
  };

  readRoute = () => {
    const { parentProps } = this.props;
    const action = parentProps.params?.taction || parentProps.match?.params.taction
    const item = parentProps.params?.titem || parentProps.match?.params.titem

    if (action) {
      if (action === 'add') {
        this.showAddForm();
      } else if (action === 'edit') {
        this.props.fetchItems().then((res) => {
          //console.log(this.props.parentProps.params);
          // console.log(res)
          // console.log('here')

          let row = [];
          if (typeof res === 'object' && res !== null && !Array.isArray(res) && res.data)
            row = res.data.filter((d) => this.props.extractKey(d) === item)[0];
          else row = res.filter((d) => this.props.extractKey(d) === item)[0];
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

  update = debounce((paginationState = {}) => {
    this.setState({ loading: true });

    const page = paginationState.page !== undefined ? paginationState.page : this.state.page;

    return (
      this.state.showAddForm || this.state.showEditForm
        ? this.props.fetchItems()
        : this.props.fetchItems({
          ...paginationState,
          pageSize: this.state.rowsPerPage,
          page: page + 1,
        })
    ).then((rawItems) => {
      if (Array.isArray(rawItems)) {
        this.setState({
          items: rawItems,
          loading: false,
          page,
        });
      } else {
        this.setState({
          items: rawItems.data,
          pages: this.calculateMaximumPages(rawItems),
          loading: false,
          page,
        });
      }
    });
  }, 200);

  calculateMaximumPages = (response) => {
    const { pages } = this.state;

    if (!isNaN(response.pages)) {
      return response.pages
    }

    if (response.ngPages !== -1)
      return response.ngPages

    return pages
  }

  gotoItem = (e, item) => {
    if (e && e.preventDefault) e.preventDefault();
    this.props.navigateTo(item)
  };

  closeAddForm = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    this.unmountShortcuts();
    if (this.props.parentProps.setTitle) {
      this.props.parentProps.setTitle(
        this.props.defaultTitle,
        this.updateItemAndStay,
        this.state.currentItem
      );
    }
    this.setState({ currentItem: null, showAddForm: false });
    this.update();
    urlTo(`/bo/dashboard/${this.props.selfUrl}`);
  };

  showAddForm = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    this.mountShortcuts();

    urlTo(`/bo/dashboard/${this.props.selfUrl}/add`);
    const defVal = this.props.defaultValue();

    if (this.props.parentProps.setTitle) {
      this.props.parentProps.setTitle(
        `Create a new ${this.props.itemName}`,
        this.updateItemAndStay,
        defVal
      );
    }

    if (defVal.then) {
      defVal.then((v) => this.setState({ currentItem: v, showAddForm: true }));
    } else {
      this.setState({ currentItem: defVal, showAddForm: true });
    }
  };

  closeEditForm = (e) => {
    if (e && e.preventDefault) {
      e.preventDefault();
    }
    this.unmountShortcuts();

    if (this.props.navigateTo) {
      this.props.parentProps.history.replace(`/${this.props.selfUrl}`);
    } else {
      if (this.props.parentProps.setTitle) {
        this.props.parentProps.setTitle(this.props.defaultTitle);
      }

      this.setState({ currentItem: null, showEditForm: false });
      this.update();
      urlTo(`/bo/dashboard/${this.props.selfUrl}`);
    }
  };

  showEditForm = (e, item) => {
    if (e && e.preventDefault) e.preventDefault();
    this.mountShortcuts();

    let routeTo = `/bo/dashboard/${this.props.selfUrl}/edit/${this.props.extractKey(item)}`;

    if (this.props.rawEditUrl) {
      routeTo = `/bo/dashboard/${this.props.selfUrl}/${this.props.extractKey(item)}`;
    }

    // if (window.location.pathname !== routeTo) {
    //   window.location.href = routeTo;
    // } else {
    if (this.props.parentProps.setTitle) {
      this.props.parentProps.setTitle(
        `Update a ${this.props.itemName}`,
        this.updateItemAndStay,
        item
      );
    }
    this.setState({ currentItem: item, showEditForm: true });
    // }
  };

  deleteItem = (e, item) => {
    if (e && e.preventDefault) e.preventDefault();
    window.newConfirm('Are you sure you want to delete that item ?').then((ok) => {
      if (ok) {
        this.props
          .deleteItem(item)
          .then(() => {
            const state = this.tableRef?.current?.state || {};
            const page = state.page || 0;

            return this.props.fetchItems({
              filtered: state.filtered,
              sorted: state.sorted,
              pageSize: this.state.rowsPerPage,
              page: page + 1,
            });
          })
          .then((res) => {
            const isPaginate =
              typeof res === 'object' && res !== null && !Array.isArray(res) && res.data;
            urlTo(`/bo/dashboard/${this.props.selfUrl}`);
            this.setState({
              items: isPaginate ? res.data : res,
              showEditForm: false,
              showAddForm: false,
            });
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
      .then((res) => {
        const isPaginate =
          typeof res === 'object' && res !== null && !Array.isArray(res) && res.data;
        urlTo(`/bo/dashboard/${this.props.selfUrl}`);
        this.setState({ items: isPaginate ? res.data : res, showAddForm: false });
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
      .then((res) => {
        const isPaginate =
          typeof res === 'object' && res !== null && !Array.isArray(res) && res.data;
        this.setState({ items: isPaginate ? res.data : res, showEditForm: false });
      });
  };

  updateItemAndStay = (e) => {
    if (e && e.preventDefault) {
      e.preventDefault();
    }
    return this.props.updateItem(this.state.currentItem);
  };

  exportJson = (e) => {
    if (e && e.preventDefault) e.preventDefault();
    const name = (this.state.currentItem.name || this.state.currentItem.clientName)
      .replace(/ /g, '-')
      .replace(/\(/g, '')
      .replace(/\)/g, '')
      .replace(/\./g, '')
      .toLowerCase();
    const itemName = this.props.itemName
      .replace(/ /g, '-')
      .replace(/\(/g, '')
      .replace(/\)/g, '')
      .replace(/\./g, '')
      .toLowerCase();
    const json = JSON.stringify(
      { ...this.state.currentItem, kind: this.props.kubernetesKind },
      null,
      2
    );
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

  isAnObject = (variable) =>
    typeof variable === 'object' && !Array.isArray(variable) && variable !== null;

  actualFlow = () => {
    if (_.isFunction(this.props.formFlow)) {
      return this.props.formFlow(this.state.currentItem);
    } else {
      return this.props.formFlow;
    }
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
    /*
    // const json = YAML.stringify({
      apiVersion: 'proxy.otoroshi.io/v1',
      kind: this.props.kubernetesKind,
      metadata: {
        name,
      },
      spec: this.state.currentItem,
    });
    */

    fetch('/bo/api/json_to_yaml', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        apiVersion: 'proxy.otoroshi.io/v1',
        kind: this.props.kubernetesKind,
        metadata: {
          name,
        },
        spec: this.state.currentItem,
      }),
    })
      .then((r) => r.text())
      .then((yaml) => {
        const blob = new Blob([yaml], { type: 'application/yaml' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.id = String(Date.now());
        a.style.display = 'none';
        a.download = `${itemName}-${name}-${Date.now()}.yaml`;
        a.href = url;
        document.body.appendChild(a);
        a.click();
        setTimeout(() => document.body.removeChild(a), 300);
      });
  };

  render() {
    if (this.state.hasError) {
      return <h3>Something went wrong !!!</h3>;
    }
    const windowWidth = window.innerWidth;
    const columns = this.props.columns.map((c) => {
      return {
        Header: c.title,
        id: c.filterId || c.title,
        headerStyle: c.style,
        width: c.style && c.style.width ? c.style.width : undefined,
        style: { ...c.style, height: 30 },
        sortable: !c.notSortable,
        filterable: !c.notFilterable,
        sortMethod: c.sortMethod,
        accessor: (d) => (c.content ? c.content(d) : d),
        // Filter: (d) => {
        //   return <input
        //     type="text"
        //     id={`input-${c.title}`}
        //     className="form-control input-sm"
        //     value={d.filter ? d.filter.value : ''}
        //     onChange={(e) => {
        //       d.onChange(e.target.value)
        //     }}
        //     placeholder="Search ..."
        //   />
        // },
        Cell:
          c.Cell ||
          ((r) => {
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
                style={{ cursor: 'pointer', width: '100%' }}
              >
                {c.wrappedCell ? c.wrappedCell(value, original, this) : value}
              </div>
            );
          }),
      };
    });

    if (this.props.showActions) {
      columns.push({
        Header: 'Actions',
        id: 'actions',
        minWidth: 160,
        maxWidth: 160,
        style: { textAlign: 'left' },
        filterable: false,
        sortable: false,
        accessor: (item) => (
          <div style={{ textAlign: 'left' }}>
            <div>
              {!this.props.hideEditButton && <button
                type="button"
                className="btn btn-sm btn-success me-2"
                {...createTooltip(`Edit this ${this.props.itemName}`, 'top', true)}
                onClick={(e) => {
                  this.props.navigateOnEdit
                    ? this.props.navigateOnEdit(item)
                    : this.showEditForm(e, item)
                }}
              >
                <i className="fas fa-pencil-alt" />
              </button>}
              {this.props.showLink && (
                <a
                  className="btn btn-sm btn-primary me-2"
                  {...createTooltip(`Open this ${this.props.itemName}`, 'top', true)}
                  href={`${this.props.itemUrl(item)}`}
                  onClick={(e) => this.gotoItem(e, item)}
                >
                  <i className="fas fa-link" />
                </a>
              )}
              {this.props.displayTrash && this.props.displayTrash(item) && (
                <button
                  type="button"
                  className="btn btn-sm btn-danger me-2"
                  disabled
                  {...createTooltip(`Delete this ${this.props.itemName}`, 'top', true)}
                >
                  <i className="fas fa-trash" />
                </button>
              )}
              {this.props.displayTrash && !this.props.displayTrash(item) && (
                <button
                  type="button"
                  className="btn btn-sm btn-danger me-2"
                  onClick={(e) => this.deleteItem(e, item)}
                  {...createTooltip(`Delete this ${this.props.itemName}`, 'top', true)}
                >
                  <i className="fas fa-trash" />
                </button>
              )}
              {!this.props.displayTrash && (
                <button
                  type="button"
                  className="btn btn-sm btn-danger me-2"
                  {...createTooltip(`Delete this ${this.props.itemName}`, 'top', true)}
                  onClick={(e) => this.deleteItem(e, item)}
                >
                  <i className="fas fa-trash" />
                </button>
              )}
            </div>
          </div>
        ),
      });
    }

    return (
      <div>
        {(this.state.currentItem && !this.state.showAddForm) && <>
          <DraftEditorContainer
            className="mb-3"
            entityId={this.props.extractKey(this.state.currentItem)}
            value={this.state.currentItem} />

          <DraftStateDaemon
            value={this.state.currentItem}
            setValue={currentItem => this.setState({ currentItem })}
            updateEntityURL={() => {
              updateEntityURLSignal.value = this.updateItemAndStay
            }}
          />
        </>}

        {!this.state.showEditForm && !this.state.showAddForm && (
          <div>
            <div className="row">
              <div
                className=""
                style={{ position: 'absolute', right: 0, top: 34, width: 'fit-content' }}
              >
                <button
                  type="button"
                  className="btn btn-primary btn-sm"
                  {...createTooltip('Reload the current table')}
                  onClick={this.update}
                >
                  <span className="fas fa-sync" />
                </button>
                {this.props.showActions && !this.props.hideAddItemAction && (
                  <button
                    type="button"
                    className="btn btn-primary btn-sm"
                    style={{ marginLeft: 10 }}
                    onClick={this.showAddForm}
                    {...createTooltip(`Create a new ${this.props.itemName}`)}
                  >
                    <span className="fas fa-plus-circle" /> Add item
                  </button>
                )}
                {this.props.injectTopBar && this.props.injectTopBar()}
              </div>
            </div>
            <div className="rrow me-1" style={{ position: 'relative' }}>
              {this.props.fields && <ColumnsSelector
                fetchTemplate={this.props.fetchTemplate}
                onChange={this.props.onToggleField}
                addField={this.props.addField}
                removeField={this.props.removeField}
                coreFields={this.props.coreFields}
                fields={Object.keys(this.props.fields)
                  .sort()
                  .reduce((r, k) => (r[k] = this.props.fields[k], r), {})}
              />}
              <ReactTable
                ref={this.tableRef}
                className="fulltable -striped -highlight"
                manual
                page={this.state.page}
                pages={this.state.pages}
                data={this.state.items}
                loading={this.state.loading}
                defaultPageSize={this.state.rowsPerPage}
                filterable={true}
                filterAll={true}
                defaultSorted={[
                  {
                    id: this.props.defaultSort || this.props.columns[0]?.title,
                    desc: this.props.defaultSortDesc || false,
                  },
                ]}
                defaultFiltered={
                  this.props.search
                    ? [{ id: this.props.columns[0]?.title, value: this.props.search }]
                    : []
                }
                onFetchData={(state, instance) => {
                  // console.log('onFetchData')
                  this.update(state);
                }}
                onFilteredChange={(column, value) => {
                  // console.log('onFilteredChange')
                  if (this.state.lastFocus) document.getElementById(this.state.lastFocus)?.focus();
                }}
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
              {!!!window.location.pathname.match(/routes\/([^\s]+)\/events/) && (
                <div
                  className="d-flex align-items-center"
                  style={{
                    position: 'absolute',
                    bottom: 0,
                    left: 0,
                    padding: '6px',
                  }}
                >
                  <p className="m-0 me-2">Rows per page</p>
                  <div style={{ minWidth: '80px' }}>
                    <NgSelectRenderer
                      id="rows-per-page"
                      value={this.state.rowsPerPage}
                      label={' '}
                      ngOptions={{ spread: true }}
                      onChange={(rowsPerPage) => this.setState({
                        rowsPerPage,
                        page: 0
                      }, this.update)}
                      options={[5, 15, 20, 50, 100]}
                    />
                  </div>
                </div>
              )}
            </div>
          </div>
        )}
        {this.state.showAddForm && (
          <div className="" role="dialog">
            {this.props.formComponent && (
              <>
                {this.props.injectToolbar
                  ? this.props.injectToolbar(this.state, (s) => this.setState(s))
                  : null}
                <form
                  className="form-horizontal"
                  style={{ paddingTop: '30px', ...this.props.style }}
                >
                  {React.createElement(this.props.formComponent, {
                    onStateChange: this.props.onStateChange,
                    showAdvancedForm: true,
                    onChange: (currentItem) => {
                      this.setState({ currentItem });

                      if (this.props.parentProps.setTitle)
                        this.props.parentProps.setTitle(
                          `Create a new ${this.props.itemName}`,
                          this.updateItemAndStay,
                          this.state.currentItem
                        );
                    },
                    value: this.state.currentItem,
                    ...(this.props.formPassProps || {}),
                  })}
                </form>
                {this.props.hideAllActions && (
                  <div className="mt-3">
                    {this.props.injectBottomBar &&
                      this.props.injectBottomBar({
                        buttons: (
                          <button
                            type="button"
                            className="btn btn-sm btn-success me-1"
                            onClick={this.createItem}
                          >
                            Create {this.props.itemName}
                          </button>
                        ),
                        closeEditForm: this.closeAddForm,
                        state: this.state,
                        setState: (v) => this.setState(v),
                      })}
                  </div>
                )}
              </>
            )}
            {this.props.formFunction && [
              this.props.injectToolbar
                ? this.props.injectToolbar(this.state, (s) => this.setState(s))
                : null,
              this.props.formFunction({
                value: this.state.currentItem,
                onChange: (currentItem) => this.setState({ currentItem }),
                flow: this.props.formFlow,
                schema: this.props.formSchema,
              }),
            ]}
            {!this.props.formComponent &&
              !this.props.formFunction &&
              (this.actualFlow().find((item) => this.isAnObject(item)) ? (
                <NgForm
                  value={this.state.currentItem}
                  onChange={(currentItem) => this.setState({ currentItem })}
                  flow={this.props.formFlow}
                  schema={this.props.formSchema}
                />
              ) : (
                <Form
                  onStateChange={this.props.onStateChange}
                  value={this.state.currentItem}
                  onChange={(currentItem) => this.setState({ currentItem })}
                  flow={this.props.formFlow}
                  schema={this.props.formSchema}
                />
              ))}
            <hr />
            {!this.props.hideAllActions && (
              <>
                <div className="displayGroupBtn float-end">
                  <button type="button" className="btn btn-danger" onClick={this.closeAddForm}>
                    Cancel
                  </button>
                  {this.props.stayAfterSave && (
                    <button
                      type="button"
                      className="btn btn-success"
                      onClick={this.createItemAndStay}
                    >
                      Create and stay on this {this.props.itemName}
                    </button>
                  )}
                  <button type="button" className="btn btn-success" onClick={this.createItem}>
                    Create {this.props.itemName}
                  </button>
                </div>
              </>
            )}
          </div>
        )}
        {this.state.showEditForm && (
          <div className="" role="dialog">
            {this.props.formComponent && [
              this.props.injectToolbar
                ? this.props.injectToolbar(this.state, (s) => this.setState(s))
                : null,
              <form className="form-horizontal" style={{ paddingTop: '30px', ...this.props.style }}>
                {React.createElement(this.props.formComponent, {
                  onStateChange: this.props.onStateChange,
                  onChange: (currentItem) => {
                    this.setState({ currentItem });

                    if (this.props.parentProps.setTitle)
                      this.props.parentProps.setTitle(
                        `Update a ${this.props.itemName}`,
                        this.updateItemAndStay,
                        this.state.currentItem
                      );
                  },
                  value: this.state.currentItem,
                  showAdvancedForm:
                    this.props.selfUrl === 'jwt-verifiers' ? true : this.state.showAdvancedForm,
                  ...(this.props.formPassProps || {}),
                })}
              </form>,
            ]}
            {this.props.formFunction && [
              this.props.injectToolbar
                ? this.props.injectToolbar(this.state, (s) => this.setState(s))
                : null,
              this.props.formFunction({
                value: this.state.currentItem,
                onChange: (currentItem) => this.setState({ currentItem }),
                flow: this.props.formFlow,
                schema: this.props.formSchema,
              }),
            ]}
            {!this.props.formComponent &&
              !this.props.formFunction &&
              (this.actualFlow().find((item) => this.isAnObject(item)) ? (
                <NgForm
                  value={this.state.currentItem}
                  onChange={(currentItem) => this.setState({ currentItem })}
                  flow={this.props.formFlow}
                  schema={this.props.formSchema}
                />
              ) : (
                <Form
                  onStateChange={this.props.onStateChange}
                  value={this.state.currentItem}
                  onChange={(currentItem) => this.setState({ currentItem })}
                  flow={this.props.formFlow}
                  schema={this.props.formSchema}
                />
              ))}
            <hr />
            <div className="displayGroupBtn float-end">
              {this.props.displayTrash && this.props.displayTrash(this.state.currentItem) && (
                <button
                  type="button"
                  className="btn btn-danger"
                  title="Delete current item"
                  disabled
                >
                  <i className="fas fa-trash" /> Delete
                </button>
              )}
              {this.props.displayTrash && !this.props.displayTrash(this.state.currentItem) && (
                <button
                  type="button"
                  className="btn btn-danger"
                  title="Delete current item"
                  onClick={(e) => this.deleteItem(e, this.state.currentItem)}
                >
                  <i className="fas fa-trash" /> Delete
                </button>
              )}
              {this.props.export && (
                <>
                  <button
                    onClick={this.exportJson}
                    type="button"
                    className="btn btn-primary"
                    title="Export as json"
                  >
                    <i className="fas fa-file-export me-2" />
                    Export JSON
                  </button>
                  <button
                    onClick={this.exportYaml}
                    type="button"
                    className="btn btn-primary"
                    title="Export as yaml"
                  >
                    <i className="fas fa-file-export me-2" />
                    Export YAML
                  </button>
                </>
              )}
              {!this.props.displayTrash && !this.props.newForm && (
                <button
                  type="button"
                  className="btn btn-danger"
                  title="Delete current item"
                  onClick={(e) => this.deleteItem(e, this.state.currentItem)}
                >
                  <i className="fas fa-trash" /> Delete
                </button>
              )}
              {!this.props.newForm && (
                <button type="button" className="btn btn-danger" onClick={this.closeEditForm}>
                  <i className="fas fa-times" /> Cancel
                </button>
              )}
              {this.props.stayAfterSave && !this.props.newForm && (
                <button type="button" className="btn btn-success" onClick={this.updateItemAndStay}>
                  Update and stay on this {this.props.itemName}
                </button>
              )}
              {!this.props.newForm && (
                <button type="button" className="btn btn-success" onClick={this.updateItem}>
                  Update {this.props.itemName}
                </button>
              )}

              {this.props.injectBottomBar &&
                this.props.injectBottomBar({
                  closeEditForm: this.closeEditForm,
                  state: this.state,
                  setState: (v) => this.setState(v),
                })}
            </div>
          </div>
        )}
      </div>
    );
  }
}
