import React, { useEffect, useRef, useState } from 'react';
import { useParams, useLocation } from 'react-router';
import {
  nextClient,
  getCategories,
  getPlugins,
  getOldPlugins,
} from '../../services/BackOfficeServices';
import { Form, format, type, CodeInput, SelectInput } from '@maif/react-forms';
import {
  DEFAULT_FLOW,
  EXCLUDED_PLUGINS,
  LEGACY_PLUGINS_WRAPPER,
  PLUGIN_INFORMATIONS_SCHEMA,
} from './Graph';
import { BackendForm } from '../BackendsPage';
import Loader from './Loader';
import { camelToSnake, camelToSnakeFlow, toUpperCaseLabels } from '../../util';
import { isEqual } from 'lodash';
import { FeedbackButton } from './FeedbackButton';

const either = (value, left, right) => (value ? left : right);

const Status = ({ value }) => (
  <div className="status-dot" style={{ backgroundColor: either(value, '#198754', '#D5443F') }} />
);

const Dot = ({
  className,
  icon,
  children,
  clickable,
  onClick,
  highlighted,
  selectedNode,
  enabled,
  style = {},
}) => (
  <div
    className={`dot ${className}`}
    style={{
      cursor: either(clickable, 'pointer', 'initial'),
      opacity: either(!selectedNode || highlighted, 1, 0.25),
      backgroundColor: either(highlighted, '#f9b000', '#494948'),
      ...style,
    }}
    onClick={(e) => {
      e.stopPropagation();
      if (onClick) onClick(e);
    }}>
    {enabled !== undefined && <Status value={enabled} />}
    {icon && <i className={`fas fa-${icon} dot-icon`} />}
    {children && children}
  </div>
);

const NodeElement = ({
  className,
  element,
  setSelectedNode,
  hideLink,
  selectedNode,
  bold,
  disableBorder,
  style,
  enabled,
}) => {
  const { id, name, index } = element;
  const highlighted =
    selectedNode &&
    selectedNode.id === id &&
    (selectedNode.plugin_multi_inst ? selectedNode.index === index : true);

  return (
    <>
      <Dot
        className={className}
        clickable={true}
        selectedNode={selectedNode}
        style={{
          borderWidth: either(disableBorder, 0, 1),
          fontWeight: either(bold, 'bold', 'normal'),
          ...style,
        }}
        onClick={(e) => {
          e.stopPropagation();
          setSelectedNode(element);
        }}
        highlighted={highlighted}
        enabled={enabled}>
        <span className="dot-text">{name || id}</span>
      </Dot>
      {!hideLink && <VerticalLine highlighted={highlighted} />}
    </>
  );
};

const VerticalLine = ({ highlighted = true, flex }) => (
  <div
    className="vertical-line"
    style={{
      opacity: either(highlighted, 1, 0.25),
      flex: either(flex, 1, 'initial'),
    }}
  />
);

export default ({ value }) => {
  const { routeId } = useParams();

  const [backends, setBackends] = useState([]);

  const [categories, setCategories] = useState([]);
  const [nodes, setNodes] = useState([]);
  const [plugins, setPlugins] = useState([]);

  const [selectedNode, setSelectedNode] = useState();
  const [route, setRoute] = useState(value);
  const [originalRoute, setOriginalRoute] = useState(value);

  const [preview, showPreview] = useState({
    enabled: false,
  });
  const [loading, setLoading] = useState(true);
  const location = useLocation();

  useEffect(() => {
    Promise.all([
      nextClient.find(nextClient.ENTITIES.BACKENDS),
      nextClient.fetch(nextClient.ENTITIES.ROUTES, routeId),
      getCategories(),
      getPlugins(),
      getOldPlugins(),
      nextClient.form(nextClient.ENTITIES.FRONTENDS),
      nextClient.form(nextClient.ENTITIES.BACKENDS),
    ]).then(([backends, route, categories, plugins, oldPlugins, frontendForm, backendForm]) => {
      const formatedPlugins = [
        ...plugins,
        ...oldPlugins.map((p) => ({
          ...p,
          legacy: true,
        })),
      ]
        .filter(filterSpecificPlugin)
        .map((plugin) => ({
          ...plugin,
          config_schema: toUpperCaseLabels(plugin.config_schema || plugin.configSchema || {}),
          config: plugin.default_config || plugin.defaultConfig,
        }));

      setBackends(backends);
      setCategories([
        ...categories.filter((category) => !['Tunnel', 'Job'].includes(category)),
        'Ancien plugins',
      ]);
      setRoute(route);
      setOriginalRoute(route);

      setPlugins(
        formatedPlugins.map((p) => ({
          ...p,
          selected: route.plugins.find((r) => r.plugin === p.id),
        }))
      );

      setNodes(
        [
          {
            ...DEFAULT_FLOW.Frontend,
            ...frontendForm,
            config_schema: toUpperCaseLabels({
              ...frontendForm.schema,
              ...DEFAULT_FLOW.Frontend.config_schema,
            }),
            config_flow: DEFAULT_FLOW.Frontend.config_flow,
          },
          {
            ...DEFAULT_FLOW.Backend,
            ...backendForm,
            config_schema: toUpperCaseLabels(
              DEFAULT_FLOW.Backend.config_schema(backendForm.schema)
            ),
            config_flow: DEFAULT_FLOW.Backend.config_flow,
          },
          ...route.plugins.map((ref) => {
            const plugin = formatedPlugins.find(
              (p) => p.id === ref.plugin || p.id === ref.config.plugin
            );
            const onInputStream = (plugin.plugin_steps || []).some((s) =>
              ['PreRoute', 'ValidateAccess', 'TransformRequest'].includes(s)
            );
            const onOutputStream = (plugin.plugin_steps || []).some((s) =>
              ['TransformResponse'].includes(s)
            );

            return {
              ...plugin,
              onOutputStream,
              onInputStream,
            };
          }),
        ].map((node, i) => ({ ...node, index: i - 2 }))
      );

      setLoading(false);
    });
  }, [location.pathname]);

  const filterSpecificPlugin = (plugin) =>
    !plugin.plugin_steps.includes('Sink') &&
    !plugin.plugin_steps.includes('HandlesTunnel') &&
    !['job', 'sink'].includes(plugin.pluginType) &&
    !EXCLUDED_PLUGINS.plugin_visibility.includes(plugin.plugin_visibility) &&
    !EXCLUDED_PLUGINS.ids.includes(plugin.id.replace('cp:', ''));

  const removeNode = (id, idx) => {
    const index = idx + 1; // increase of one to prevent delete the Frontend node
    setNodes(nodes.filter((node, i) => node.id !== id && i !== index));
    setRoute({
      ...route,
      plugins: route.plugins.filter((plugin) => !plugin.plugin.endsWith(id)),
    });

    setPlugins(
      plugins.map((plugin, i) => {
        if (plugin.id === id && i === index) return { ...plugin, selected: undefined };
        return plugin;
      })
    );
  };

  const addNode = (node) => {
    const newNode = {
      ...node,
      index: nodes.length,
    };
    if (
      (newNode.plugin_steps || []).some((s) => ['TransformResponse'].includes(s)) ||
      newNode.onTargetStream ||
      (newNode.plugin_steps || []).some((s) =>
        ['PreRoute', 'ValidateAccess', 'TransformRequest'].includes(s)
      )
    ) {
      setPlugins(
        plugins.map((p) => {
          if (p.id === newNode.id) p.selected = !p.plugin_multi_inst;
          return p;
        })
      );

      setRoute({
        ...route,
        plugins: [
          ...route.plugins,
          {
            plugin: either(newNode.legacy, LEGACY_PLUGINS_WRAPPER[newNode.pluginType], newNode.id),
            config: {
              ...newNode.config,
              plugin: either(newNode.legacy, newNode.id, undefined),
            },
          },
        ],
      });

      setNodes([...nodes, newNode]);
      setSelectedNode(newNode);
    }
  };

  const handleSearch = (search) => {
    setPlugins(
      plugins.map((plugin) => ({
        ...plugin,
        filtered: !plugin.id.toLowerCase().includes(search.toLowerCase()),
      }))
    );
  };

  const updatePlugin = (pluginId, index, item, updatedField) => {
    return nextClient
      .update(nextClient.ENTITIES.ROUTES, {
        ...route,
        frontend: either(updatedField === 'Frontend', item.plugin, route.frontend),
        backend: either(updatedField === 'Backend', item.plugin, route.backend),
        plugins: route.plugins.map((plugin, i) => {
          if ((plugin.plugin === pluginId || plugin.config.plugin === pluginId) && i === index)
            return {
              ...plugin,
              ...item.status,
              config: item.plugin,
            };

          return plugin;
        }),
      })
      .then((r) => {
        if (!r.error) {
          setOriginalRoute(r);
          setRoute(r);
        } else {
          // TODO - manage error
        }
      });
  };

  const saveChanges = () => {
    return nextClient.update(nextClient.ENTITIES.ROUTES, route).then((newRoute) => {
      setOriginalRoute(newRoute);
      setRoute(newRoute);
    });
  };

  const sortInputStream = (arr) =>
    Object.values(
      arr.reduce(
        (acc, node) => {
          if (node.plugin_steps.includes('PreRoute'))
            return {
              ...acc,
              PreRoute: [...acc['PreRoute'], node],
            };
          else if (node.plugin_steps.includes('ValidateAccess'))
            return {
              ...acc,
              ValidateAccess: [...acc['ValidateAccess'], node],
            };
          return {
            ...acc,
            TransformRequest: [...acc['TransformRequest'], node],
          };
        },
        {
          PreRoute: [],
          ValidateAccess: [],
          TransformRequest: [],
        }
      )
    ).flat();

  const inputNodes = sortInputStream(
    nodes.filter((node) =>
      (node.plugin_steps || []).some((s) =>
        ['PreRoute', 'ValidateAccess', 'TransformRequest'].includes(s)
      )
    )
  );
  const targetNodes = nodes.filter((node) => node.onTargetStream);
  const outputNodes = nodes.filter((node) =>
    (node.plugin_steps || []).some((s) => ['TransformResponse'].includes(s))
  );

  return (
    <Loader loading={loading}>
      <div className="h-100 col-12 hide-overflow" onClick={() => setSelectedNode(undefined)}>
        <div className="plugins-stack-column">
          <div className="elements">
            <div className="plugins-background-bar" />
            <SearchBar handleSearch={handleSearch} />
            <div className="relative-container" id="plugins-stack-container">
              <PluginsStack
                elements={plugins.reduce(
                  (acc, plugin) => {
                    if (plugin.selected || plugin.filtered) return acc;
                    return acc.map((group) => {
                      if (plugin.plugin_categories.includes(group.group))
                        return {
                          ...group,
                          elements: [...(group.elements || []), plugin],
                        };
                      return group;
                    });
                  },
                  categories.map((category) => ({
                    group: category,
                    elements: [],
                  }))
                )}
                addNode={addNode}
                showPreview={(element) =>
                  showPreview({
                    enabled: true,
                    element,
                  })
                }
                hidePreview={() => showPreview({ ...preview, enabled: false })}
              />
            </div>
          </div>
        </div>
        <div className="relative-container" style={{ flex: 9 }}>
          {preview.enabled ? (
            <EditView
              addNode={addNode}
              hidePreview={() =>
                showPreview({
                  ...preview,
                  enabled: false,
                })
              }
              readOnly={true}
              setRoute={setRoute}
              selectedNode={preview.element}
              setSelectedNode={setSelectedNode}
              updatePlugin={updatePlugin}
              removeNode={removeNode}
              route={route}
              plugins={plugins}
              backends={backends}
            />
          ) : (
            <div className="row h-100 p-2 me-1 flow-container">
              <div className="col-sm-4 pe-3 d-flex flex-column">
                <div className="row" style={{ height: '100%' }}>
                  <div className="col-sm-6 flex-column">
                    <div className="main-view relative-container">
                      <div
                        className="frontend-button"
                        style={{
                          background: either(
                            selectedNode && selectedNode.id === 'Frontend',
                            'linear-gradient(to right, rgb(249, 176, 0) 55%, transparent 1%)',
                            'linear-gradient(to right, rgb(73, 73, 72) 55%, transparent 1%)'
                          ),
                          opacity: either(
                            !selectedNode || (selectedNode && selectedNode.id === 'Frontend'),
                            1,
                            0.25
                          ),
                        }}>
                        <i className="fas fa-user frontend-button-icon" />
                      </div>
                      {inputNodes.slice(0, 1).map((value, i) => (
                        <NodeElement
                          className="frontend-container-button"
                          element={value}
                          key={`inNodes${i}`}
                          selectedNode={selectedNode}
                          setSelectedNode={setSelectedNode}
                          isLast={inputNodes.length - 1 === i}
                          bold={true}
                        />
                      ))}
                      <Dot className="arrow-flow" icon="chevron-down" selectedNode={selectedNode} />
                      <VerticalLine highlighted={!selectedNode} />
                      {inputNodes.slice(1).map((value, i) => (
                        <NodeElement
                          enabled={
                            route.plugins.find(
                              (p, i) =>
                                (p.plugin === value.id || p.config.plugin === value.id) &&
                                i === value.index
                            )?.enabled
                          }
                          element={value}
                          key={`inNodes${i}`}
                          selectedNode={selectedNode}
                          setSelectedNode={setSelectedNode}
                          isLast={inputNodes.length - 1 === i}
                        />
                      ))}
                      <VerticalLine highlighted={!selectedNode} flex={true} />
                    </div>
                  </div>
                  <div className="col-sm-6 pe-3 flex-column">
                    <div className="main-view">
                      <Dot className="arrow-flow" icon="chevron-up" selectedNode={selectedNode} />
                      <VerticalLine highlighted={!selectedNode} />
                      {outputNodes.map((value, i) => (
                        <NodeElement
                          enabled={
                            route.plugins.find(
                              (p, i) =>
                                (p.plugin === value.id || p.config.plugin === value.id) &&
                                i === value.index
                            )?.enabled
                          }
                          element={value}
                          key={`outNodes${i}`}
                          setSelectedNode={setSelectedNode}
                          selectedNode={selectedNode}
                          isLast={outputNodes.length - 1 === i}
                        />
                      ))}
                      <VerticalLine highlighted={!selectedNode} flex={true} />
                    </div>
                  </div>
                </div>
                <div
                  className="main-view backend-button"
                  style={{
                    opacity: either(
                      !selectedNode,
                      1,
                      either(!selectedNode?.onTargetStream, 0.25, 1)
                    ),
                  }}>
                  <i className="fas fa-bullseye backend-icon" />
                  {targetNodes.map((value, i, arr) => (
                    <NodeElement
                      element={value}
                      key={`targetNodes${i}`}
                      selectedNode={either(
                        selectedNode && selectedNode.onTargetStream,
                        selectedNode,
                        undefined
                      )}
                      setSelectedNode={setSelectedNode}
                      hideLink={arr.length - 1 === i}
                      disableBorder={true}
                      bold={true}
                    />
                  ))}
                </div>
              </div>
              <div className="col-sm-8 relative-container" style={{ paddingRight: 0 }}>
                {selectedNode ? (
                  <EditView
                    setRoute={setRoute}
                    selectedNode={selectedNode}
                    setSelectedNode={setSelectedNode}
                    updatePlugin={updatePlugin}
                    removeNode={removeNode}
                    route={route}
                    plugins={plugins}
                    backends={backends}
                    hidePreview={() =>
                      showPreview({
                        ...preview,
                        enabled: false,
                      })
                    }
                  />
                ) : (
                  <UnselectedNode
                    saveChanges={saveChanges}
                    disabled={isEqual(route, originalRoute)}
                  />
                )}
              </div>
            </div>
          )}
        </div>
      </div>
    </Loader>
  );
};

const Element = ({ element, addNode, showPreview, hidePreview }) => (
  <div
    className="element"
    onClick={(e) => {
      e.stopPropagation();
      showPreview(element);
    }}>
    <div className="d-flex-between element-text">
      {element.name.charAt(0).toUpperCase() + element.name.slice(1)}
      <i
        className="fas fa-arrow-right element-arrow"
        onClick={(e) => {
          e.stopPropagation();
          hidePreview();
          addNode(element);
        }}
      />
    </div>
  </div>
);

const Group = ({ group, elements, addNode, ...props }) => {
  const [open, setOpen] = useState(false);

  return (
    <div className="group">
      <div
        className="search-group-header"
        style={{ cursor: 'pointer' }}
        onClick={(e) => {
          e.stopPropagation();
          setOpen(!open);
        }}>
        <i
          className={`fas fa-chevron-${either(open, 'down', 'right')} ms-3`}
          size={16}
          style={{ color: '#fff' }}
          onClick={(e) => {
            e.stopPropagation();
            setOpen(!open);
          }}
        />
        <span style={{ color: '#fff', padding: '10px' }}>
          {group.charAt(0).toUpperCase() + group.slice(1)}
        </span>
      </div>
      {open && <PluginsStack elements={elements} addNode={addNode} {...props} />}
    </div>
  );
};

const PluginsStack = ({ elements, ...props }) => (
  <div className="plugins-stack">
    {elements.map((element, i) => {
      if (element.group) {
        if (element.elements?.find((e) => !e.default))
          return <Group {...element} key={element.group} {...props} />;
        return null;
      } else return <Element key={`${element.id}${i}`} n={i + 1} element={element} {...props} />;
    })}
  </div>
);

const SearchBar = ({ handleSearch }) => (
  <div className="group">
    <div className="group-header" style={{ alignItems: 'initial' }}>
      <i className="fas fa-search group-icon designer-group-header-icon" />
      <div
        style={{
          paddingLeft: '6px',
          width: '100%',
          backgroundColor: '#fff',
          display: 'flex',
          alignItems: 'center',
        }}>
        <input
          type="text"
          style={{
            borderWidth: 0,
            padding: '6px 0px 6px 6px',
            width: '100%',
            outline: 'none',
            borderRadius: '4px',
          }}
          onChange={(e) => handleSearch(e.target.value)}
          placeholder="Search the plugin"
        />
      </div>
    </div>
  </div>
);

const convertTransformer = (obj) => {
  return Object.entries(obj).reduce((acc, [key, value]) => {
    let newValue = value;
    if (key === 'transformer' && typeof value === 'object')
      newValue = (item) => ({ label: item[value.label], value: item[value.value] });
    else if (typeof value === 'object' && value !== null && !Array.isArray(value))
      newValue = convertTransformer(value);

    return {
      ...acc,
      [key]: newValue,
    };
  }, {});
};

const read = (value, path) => {
  const keys = path.split('.');
  if (keys.length === 1) return value[path];

  return read(value[keys[0]], keys.slice(1).join('.'));
};

const UnselectedNode = ({ saveChanges, disabled }) => (
  <div className="d-flex-between dark-background p-1 ps-2">
    <span
      style={{
        textAlign: 'center',
        fontStyle: 'italic',
      }}>
      Start by selecting a plugin
    </span>

    <FeedbackButton
      text="Update route"
      disabled={disabled}
      icon={() => <i className="far fa-paper-plane" />}
      onPress={saveChanges}
    />
  </div>
);

const EditView = ({
  selectedNode,
  setSelectedNode,
  route,
  removeNode,
  plugins,
  updatePlugin,
  setRoute,
  backends,
  readOnly,
  addNode,
  hidePreview,
}) => {
  const [usingExistingBackend, setUsingExistingBackend] = useState(route.backend_ref);
  const [asJsonFormat, toggleJsonFormat] = useState(selectedNode.legacy || readOnly);
  const [form, setForm] = useState({
    schema: {},
    flow: [],
    value: undefined,
    originalValue: {},
  });

  const ref = useRef();
  const [saveable, setSaveable] = useState(false);
  const [backendConfigRef, setBackendConfigRef] = useState();

  const [offset, setOffset] = useState(0);

  useEffect(() => {
    const onScroll = () => setOffset(window.pageYOffset);
    window.removeEventListener('scroll', onScroll);
    window.addEventListener('scroll', onScroll, { passive: true });

    onScroll();

    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    if (route.backend_ref)
      nextClient.fetch(nextClient.ENTITIES.BACKENDS, route.backend_ref).then(setBackendConfigRef);
  }, [route.backend_ref]);

  const { id, flow, config_flow, config_schema, schema, name, index } = selectedNode;

  const isFrontendOrBackend = ['Backend', 'Frontend'].includes(id);
  const isPluginWithConfiguration = Object.keys(config_schema).length > 0;

  const plugin = either(
    isFrontendOrBackend,
    DEFAULT_FLOW[id],
    plugins.find((element) => element.id === id || element.id.endsWith(id))
  );

  const onRemove = (e) => {
    e.stopPropagation();
    setSelectedNode(undefined);
    removeNode(id, index);
  };

  useEffect(() => {
    let formSchema = schema || config_schema;
    let formFlow = [
      either(isFrontendOrBackend, undefined, 'status'),
      either(
        isPluginWithConfiguration,
        {
          label: either(isFrontendOrBackend, null, 'Plugin'),
          flow: ['plugin'],
          collapsed: false,
          collapsable: false,
        },
        undefined
      ),
    ].filter((f) => f);

    if (config_schema) {
      formSchema = {
        status: {
          type: type.object,
          format: format.form,
          collapsable: true,
          collapsed: isPluginWithConfiguration,
          label: 'Informations',
          schema: PLUGIN_INFORMATIONS_SCHEMA,
        },
      };
      if (isPluginWithConfiguration)
        formSchema = {
          ...formSchema,
          plugin: {
            type: type.object,
            format: format.form,
            label: null,
            schema: { ...convertTransformer(config_schema) },
            flow: [...(config_flow || flow)].map((step) => camelToSnakeFlow(step)),
          },
        };
    }

    formSchema = camelToSnake(formSchema);
    formFlow = formFlow.map((step) => camelToSnakeFlow(step));

    let value = route[selectedNode.field];

    if (!value) {
      const node =
        route.plugins.find((p, i) => (p.plugin === id || p.config.plugin === id) && i === index) ||
        plugins.find((p) => p.id === id);
      if (node)
        value = {
          plugin: node.config,
          status: {
            enabled: node.enabled,
            debug: node.debug,
            include: node.include,
            exclude: node.exclude,
          },
        };
    } else {
      value = {
        plugin: { ...value },
      };
    }

    setForm({
      schema: formSchema,
      flow: formFlow,
      value,
      originalValue: value,
      unsavedForm: value,
    });
    setSaveable(false);

    toggleJsonFormat(selectedNode.legacy || readOnly);
  }, [selectedNode]);

  const onValidate = (item) => {
    return updatePlugin(
      id,
      index,
      unstringify({
        plugin: item.plugin,
        status: item.status,
      }),
      selectedNode.id
    ).then(() => {
      setForm({ ...form, originalValue: item });
      setSaveable(false);
    });
  };

  // console.log("SCHEMA", form.schema.plugin)
  console.log('VALUE', form.value);

  return (
    <div
      onClick={(e) => e.stopPropagation()}
      className="plugins-stack editor-view"
      style={{ top: offset }}>
      <div className="group-header d-flex-between editor-view-informations">
        <div className="d-flex-between">
          <i
            className={`fas fa-${
              plugin.icon || 'bars'
            } group-icon designer-group-header-icon editor-view-icon`}
          />
          <span className="editor-view-text">{name || id}</span>
        </div>
        <div className="d-flex me-1">
          {!selectedNode.legacy && !readOnly && (
            <>
              <button
                className="btn btn-sm toggle-form-buttons"
                onClick={() => toggleJsonFormat(false)}
                style={{ backgroundColor: either(asJsonFormat, '#373735', '#f9b000') }}>
                FORM
              </button>
              <button
                className="btn btn-sm mx-1 toggle-form-buttons"
                onClick={() => {
                  if (!isEqual(ref.current.rawData(), form.value))
                    setForm({ ...form, value: ref.current.rawData() });
                  toggleJsonFormat(true);
                }}
                style={{ backgroundColor: either(asJsonFormat, '#f9b000', '#373735') }}>
                RAW JSON
              </button>
            </>
          )}
          <button
            className="btn btn-sm btn-danger"
            style={{ minWidth: '36px' }}
            onClick={() => {
              setSelectedNode(undefined);
              hidePreview();
            }}>
            <i className="fas fa-times designer-times-button" />
          </button>
        </div>
      </div>
      <div
        style={{
          backgroundColor: '#494949',
        }}>
        <Description text={selectedNode.description} />
        {id === 'Backend' && (
          <BackendSelector
            backends={backends}
            setBackendConfigRef={setBackendConfigRef}
            setUsingExistingBackend={setUsingExistingBackend}
            setRoute={setRoute}
            usingExistingBackend={usingExistingBackend}
            route={route}
          />
        )}
        {!usingExistingBackend || id !== 'Backend' ? (
          <div className="editor-view-form">
            {asJsonFormat ? (
              <>
                {form.value && (
                  <CodeInput
                    showGutter={false}
                    mode="json"
                    themeStyle={{
                      maxHeight: either(readOnly, '300px', '-1'),
                      width: '100%',
                    }}
                    value={stringify(form.value)}
                    onChange={(value) => {
                      try {
                        const v = either(typeof value === 'string', JSON.parse(value), value);
                        setSaveable(!isEqual(form.originalValue, v));
                      } catch (err) {
                        setSaveable(true);
                      }
                      setForm({ ...form, value });
                    }}
                  />
                )}
                {readOnly ? (
                  <div className="d-flex justify-content-end mt-3">
                    <button
                      className="btn btn-sm btn-danger me-1"
                      onClick={() => {
                        setSelectedNode(undefined);
                        hidePreview();
                      }}>
                      Cancel
                    </button>
                    <button
                      className="btn btn-sm btn-save"
                      onClick={() => {
                        hidePreview();
                        addNode(selectedNode);
                      }}>
                      Add to flow
                    </button>
                  </div>
                ) : (
                  <EditViewActions
                    valid={() => onValidate(form.value)}
                    selectedNode={selectedNode}
                    onRemove={onRemove}
                    saveable={saveable}
                  />
                )}
              </>
            ) : form.value ? (
              <Form
                ref={ref}
                value={unstringify(form.value)}
                schema={form.schema}
                options={{
                  watch: () => {
                    if (ref.current) {
                      const data = ref.current.rawData();
                      const unsaved = data.status
                        ? {
                            ...data,
                            status: {
                              ...data.status,
                              enabled: !!data.status.enabled,
                              debug: !!data.status.debug,
                            },
                          }
                        : data;
                      if (unsaved && Object.keys(unsaved).length > 0) {
                        const configurationChanged = !isEqual(
                          unsaved.plugin,
                          form.originalValue.plugin
                        );
                        // console.log(unsaved.plugin, form.originalValue.plugin, configurationChanged)
                        // console.log(unsaved.status, unsaved.status, form.originalValue.status, !isEqual(unsaved.status, form.originalValue.status))
                        if (configurationChanged) setSaveable(true);
                        else if (
                          unsaved.status &&
                          !isEqual(unsaved.status, form.originalValue.status)
                        )
                          setSaveable(true);
                      }
                    }
                  },
                }}
                flow={form.flow}
                onSubmit={onValidate}
                footer={({ valid }) => (
                  <EditViewActions
                    valid={valid}
                    selectedNode={selectedNode}
                    onRemove={onRemove}
                    saveable={saveable}
                  />
                )}
              />
            ) : null}
          </div>
        ) : (
          <>
            {backendConfigRef && (
              <BackendForm
                isCreation={false}
                value={backendConfigRef}
                style={{ maxWidth: '100%' }}
                foldable={true}
              />
            )}
            <FeedbackButton
              text="Update the plugin configuration"
              icon={() => <i className="fas fa-paper-plane" />}
              onPress={() =>
                nextClient.update(nextClient.ENTITIES.ROUTES, route).then((newRoute) => {
                  setOriginalRoute(newRoute);
                  setRoute(newRoute);
                })
              }
            />
          </>
        )}
      </div>
      {usingExistingBackend && id === 'Backend' && !selectedNode.default && (
        <RemoveComponent onRemove={onRemove} />
      )}
    </div>
  );
};

const stringify = (item) => (typeof item === 'object' ? JSON.stringify(item, null, 2) : item);
const unstringify = (item) => {
  console.log(item);
  if (typeof item === 'object') return item;
  else {
    try {
      return JSON.parse(item);
    } catch (_) {
      return item;
    }
  }
};

const Description = ({ text }) => {
  const [showMore, setShowMore] = useState(false);

  const textLength = text ? text.length : 0;
  const maxLength = 120;
  const overflows = textLength > maxLength;

  return (
    <>
      <p
        className="form-description"
        style={{
          marginBottom: either(text, 'inherit', 0),
          padding: either(text, '12px', 0),
          paddingBottom: either(overflows || !text, 0, '12px'),
        }}>
        {text ? text?.slice(0, either(showMore, textLength, maxLength)) : ''}{' '}
        {overflows && either(!showMore, '...', '')}
      </p>
      {overflows && (
        <button
          className="btn btn-sm btn-success me-3 mb-3"
          onClick={() => setShowMore(!showMore)}
          style={{ marginLeft: 'auto', display: 'block' }}>
          {either(showMore, 'Show less', 'Show more description')}
        </button>
      )}
    </>
  );
};

const RemoveComponent = ({ onRemove }) => (
  <button className="btn btn-sm btn-danger me-2" onClick={onRemove}>
    <i className="fas fa-trash me-2"></i>
    Remove this component
  </button>
);

const EditViewActions = ({ valid, selectedNode, onRemove, saveable }) => (
  <div className="d-flex mt-4 justify-content-end">
    {!selectedNode.default && <RemoveComponent onRemove={onRemove} />}
    <FeedbackButton
      disabled={!saveable}
      text="Update the plugin configuration"
      icon={() => <i className="fas fa-paper-plane" />}
      onPress={valid}
    />
  </div>
);

const BackendSelector = ({
  setBackendConfigRef,
  setUsingExistingBackend,
  setRoute,
  usingExistingBackend,
  route,
  backends,
}) => (
  <div className="backend-selector">
    <div className={`d-flex ${either(usingExistingBackend, 'mb-3', '')}`}>
      <button
        className="btn btn-sm new-backend-button"
        onClick={() => {
          setBackendConfigRef(undefined);
          setUsingExistingBackend(false);
          setRoute({
            ...route,
            backend_ref: undefined,
          });
        }}
        style={{ backgroundColor: either(usingExistingBackend, '#494849', '#f9b000') }}>
        Create a new backend
      </button>
      <button
        className="btn btn-sm new-backend-button"
        onClick={() => setUsingExistingBackend(true)}
        style={{ backgroundColor: either(usingExistingBackend, '#f9b000', '#494849') }}>
        Select an existing backend
      </button>
    </div>
    {usingExistingBackend && (
      <SelectInput
        id="backend_select"
        value={route.backend_ref}
        placeholder="Select an existing backend"
        label=""
        onChange={(backend_ref) =>
          setRoute({
            ...route,
            backend_ref,
          })
        }
        possibleValues={backends}
        transformer={(item) => ({ label: item.name, value: item.id })}
      />
    )}
  </div>
);
