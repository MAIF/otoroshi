import React, { useEffect, useRef, useState } from 'react';
import { useParams, useLocation } from 'react-router';
import {
  nextClient,
  getCategories,
  getPlugins,
  getOldPlugins,
} from '../../services/BackOfficeServices';
import {
  DEFAULT_FLOW,
  EXCLUDED_PLUGINS,
  LEGACY_PLUGINS_WRAPPER,
  PLUGIN_INFORMATIONS_SCHEMA,
} from './Graph';
import Loader from './Loader';
import { toUpperCaseLabels, camelToSnake, camelToSnakeFlow } from '../../util';
import { isEqual } from 'lodash';
import { Form, type, format, validate } from '@maif/react-forms';
import { CodeInput } from '@maif/react-forms';
import { MarkdownInput } from '@maif/react-forms';
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
  onUnsavedChanges,
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
          if (onUnsavedChanges) onUnsavedChanges(() => setSelectedNode(element));
          else setSelectedNode(element);
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
  const [searched, setSearched] = useState('');
  const location = useLocation();

  const [changed, setChanged] = useState(false);

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
          selected: p.plugin_multi_inst ? false : route.plugins.find((r) => r.plugin === p.id),
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
        ].map((node, i) => ({ ...node, index: i }))
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
    setNodes(
      nodes
        .filter((node, i) => !(node.id === id && i === idx))
        .map((node, i) => ({
          ...node,
          index: i,
        }))
    );

    saveChanges({
      ...route,
      plugins: route.plugins.filter((_, i) => i + 2 !== idx),
    });

    setPlugins(
      plugins.map((plugin) => {
        if (plugin.id === id) return { ...plugin, selected: undefined };
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

      const newRoute = {
        ...route,
        plugins: [
          ...route.plugins,
          {
            plugin: either(newNode.legacy, LEGACY_PLUGINS_WRAPPER[newNode.pluginType], newNode.id),
            enabled: node.enabled || false,
            debug: node.debug || false,
            include: node.include || [],
            exclude: node.exclude || [],
            config: {
              ...newNode.config,
              plugin: either(newNode.legacy, newNode.id, undefined),
            },
          },
        ],
      };

      setNodes([...nodes, newNode]);
      setSelectedNode(newNode);
      saveChanges(newRoute);
    }
  };

  const handleSearch = (search) => {
    setSearched(search);
    setPlugins(
      plugins.map((plugin) => ({
        ...plugin,
        filtered: !plugin.id.toLowerCase().includes(search.toLowerCase()),
      }))
    );
  };

  const updatePlugin = (pluginId, index, item, updatedField) => {
    return saveChanges({
      ...route,
      frontend: either(updatedField === 'Frontend', item.plugin, route.frontend),
      backend: either(updatedField === 'Backend', item.plugin, route.backend),
      plugins: route.plugins.map((plugin, i) => {
        if ((plugin.plugin === pluginId || plugin.config.plugin === pluginId) && i + 2 === index)
          return {
            ...plugin,
            ...item.status,
            config: item.plugin,
          };

        return plugin;
      }),
    });
  };

  const saveChanges = (route) => {
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

  const onUnsavedChanges = (onConfirm) => {
    if (changed) {
      window
        .newConfirm(`Are you sure to leave this configuration without save your changes ?`)
        .then((ok) => {
          if (ok) onConfirm();
        });
    } else onConfirm();
  };

  const pluginIsEnabled = (value) =>
    route.plugins.find(
      (p, i) => (p.plugin === value.id || p.config.plugin === value.id) && i + 2 === value.index
    )?.enabled;

  return (
    <Loader loading={loading}>
      <div
        className="h-100 col-12 hide-overflow route-designer"
        onClick={(e) => {
          e.stopPropagation();
          onUnsavedChanges(() => {
            setChanged(false);
            setSelectedNode(undefined);
          });
        }}>
        <div className="plugins-stack-column">
          <div className="elements">
            <div className="plugins-background-bar" />
            <SearchBar handleSearch={handleSearch} />
            <div className="relative-container" id="plugins-stack-container">
              <PluginsStack
                forceOpen={!!searched}
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
                          onUnsavedChanges={onUnsavedChanges}
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
                      {inputNodes.slice(1).map((value, i) => {
                        return (
                          <NodeElement
                            onUnsavedChanges={onUnsavedChanges}
                            enabled={pluginIsEnabled(value)}
                            element={value}
                            key={`inNodes${i}`}
                            selectedNode={selectedNode}
                            setSelectedNode={setSelectedNode}
                            isLast={inputNodes.length - 1 === i}
                          />
                        );
                      })}
                      <VerticalLine highlighted={!selectedNode} flex={true} />
                    </div>
                  </div>
                  <div className="col-sm-6 pe-3 flex-column">
                    <div className="main-view">
                      <Dot className="arrow-flow" icon="chevron-up" selectedNode={selectedNode} />
                      <VerticalLine highlighted={!selectedNode} />
                      {outputNodes.map((value, i) => (
                        <NodeElement
                          onUnsavedChanges={onUnsavedChanges}
                          enabled={pluginIsEnabled(value)}
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
                      onUnsavedChanges={onUnsavedChanges}
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
                <UnselectedNode hideText={selectedNode} />
                {selectedNode && (
                  <EditView
                    saveChanges={saveChanges}
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
                    showUpdateRouteButton={!isEqual(route, originalRoute)}
                    setRef={setChanged}
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
        className={`fas fa-${either(
          element.plugin_multi_inst,
          'plus',
          'arrow-right'
        )} element-arrow`}
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
      {(props.forceOpen || open) && (
        <PluginsStack elements={elements} addNode={addNode} {...props} />
      )}
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
          // backgroundColor: '#fff',
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

const read = (value, path) => {
  const keys = path.split('.');
  if (keys.length === 1) return value[path];

  return read(value[keys[0]], keys.slice(1).join('.'));
};

const UnselectedNode = ({ hideText }) =>
  !hideText && (
    <div className="d-flex-between dark-background py-2 ps-2">
      <span style={{ fontStyle: 'italic' }}> Start by selecting a plugin</span>
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

function EditView({
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
  showUpdateRouteButton,
  saveChanges,
  setRef,
}) {
  const [usingExistingBackend, setUsingExistingBackend] = useState(route.backend_ref);
  const [asJsonFormat, toggleJsonFormat] = useState(selectedNode.legacy || readOnly);
  const [form, setForm] = useState({
    schema: {},
    flow: [],
    value: undefined,
  });
  const [backendConfigRef, setBackendConfigRef] = useState();
  const formRef = useRef();

  const [offset, setOffset] = useState(0);
  const [errors, setErrors] = useState([]);
  const [isDirty, setDirty] = useState(showUpdateRouteButton);

  useEffect(() => {
    setDirty(showUpdateRouteButton);
  }, [showUpdateRouteButton]);

  useEffect(() => {
    if (setRef && typeof setRef === 'function') setRef(isDirty);
  }, [isDirty]);

  useEffect(() => {
    const onScroll = () => setOffset(window.pageYOffset);
    window.removeEventListener('scroll', onScroll);
    window.addEventListener('scroll', onScroll, { passive: true });

    onScroll();

    return () => {
      window.removeEventListener('scroll', onScroll);
    };
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
    window.newConfirm(`Are you sure to delete this route ?`).then((ok) => {
      if (ok) {
        setSelectedNode(undefined);
        removeNode(id, index);
      }
    });
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
        route.plugins.find(
          (p, i) => (p.plugin === id || p.config.plugin === id) && i + 2 === index
        ) || plugins.find((p) => p.id === id);
      if (node)
        value = {
          plugin: node.config,
          status: {
            enabled: node.enabled || true,
            debug: node.debug || false,
            include: node.include || [],
            exclude: node.exclude || [],
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
    });

    toggleJsonFormat(selectedNode.legacy || readOnly);
  }, [selectedNode.id, selectedNode.index]);

  const onValidate = (item) => {
    const newValue = unstringify(item);
    return updatePlugin(
      id,
      index,
      {
        plugin: newValue.plugin,
        status: newValue.status,
      },
      selectedNode.id
    ).then(() => {
      setForm({ ...form, value: newValue, originalValue: newValue });
      setDirty(false);
    });
  };

  const onJsonInputChange = (value) => {
    validate([], form.schema, value)
      .then((v) => {
        setErrors([]);
        setForm({ ...form, value: v });
        setDirty(!isEqual(form.originalValue, v));
      })
      .catch((err) => {
        if (err.inner && Array.isArray(err.inner)) {
          setErrors(err.inner.map((r) => r.message));
          setDirty(false);
        }
      });
  };

  if (Object.keys(form.schema).length === 0 || !form.value) return null;

  // console.log("SCHEMA", form.schema)
  // console.log("VALUE", unstringify(form.value))

  return (
    <>
      <div
        id="form"
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
            <button
              className="btn btn-sm"
              style={{ minWidth: '36px' }}
              onClick={() => {
                setSelectedNode(undefined);
                hidePreview();
              }}>
              <i className="fas fa-times" style={{ color: '#fff' }} />
            </button>
          </div>
        </div>
        <div
          style={{
            backgroundColor: '#494949',
          }}>
          <Description text={selectedNode.description} steps={selectedNode.plugin_steps || []} fullText={hidePreview} />
          {!selectedNode.legacy && !readOnly && (
            <div className={`d-flex justify-content-end ${asJsonFormat ? 'mb-3' : ''}`}>
              <button
                className="btn btn-sm toggle-form-buttons mt-3"
                disabled={errors && errors.length > 0}
                onClick={() => toggleJsonFormat(false)}
                style={{ backgroundColor: either(asJsonFormat, '#373735', '#f9b000') }}>
                FORM
              </button>
              <button
                className="btn btn-sm mx-1 toggle-form-buttons mt-3"
                onClick={() => {
                  if (formRef.current)
                    formRef.current.trigger().then((res) => {
                      if (res) toggleJsonFormat(true);
                    });
                }}
                style={{ backgroundColor: either(asJsonFormat, '#f9b000', '#373735') }}>
                RAW JSON
              </button>
            </div>
          )}
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
                      onChange={onJsonInputChange}
                    />
                  )}
                  {errors && (
                    <div>
                      {(errors || []).map((error, idx) => (
                        <div
                          style={{
                            borderLeft: '2px solid #D5443F',
                          }}
                          className="mt-3 ps-3"
                          key={`errror${idx}`}>
                          {error}
                        </div>
                      ))}
                    </div>
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
                    <Actions
                      showUpdateRouteButton={isDirty}
                      valid={() => onValidate(form.value)}
                      selectedNode={selectedNode}
                      onRemove={onRemove}
                    />
                  )}
                </>
              ) : (
                <Form
                  ref={formRef}
                  options={{
                    watch: () => {
                      if (formRef.current) {
                        const formState = formRef.current.methods.formState.isDirty;
                        if (formState !== isDirty) setDirty(formState);
                      }
                    },
                  }}
                  value={unstringify(form.value)}
                  schema={form.schema}
                  flow={form.flow}
                  onSubmit={onValidate}
                  footer={({ valid }) => (
                    <Actions
                      showUpdateRouteButton={isDirty}
                      valid={valid}
                      selectedNode={selectedNode}
                      onRemove={onRemove}
                    />
                  )}
                />
              )}
            </div>
          ) : (
            <div className="p-3">
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
                onPress={saveChanges}
              />
            </div>
          )}
        </div>
        {usingExistingBackend && id === 'Backend' && !selectedNode.default && (
          <RemoveComponent onRemove={onRemove} />
        )}
      </div>
    </>
  );
}

const stringify = (item) => (typeof item === 'object' ? JSON.stringify(item, null, 2) : item);
const unstringify = (item) => {
  if (typeof item === 'object') return item;
  else {
    try {
      return JSON.parse(item);
    } catch (_) {
      return item;
    }
  }
};

const Actions = ({ selectedNode, onRemove, valid, showUpdateRouteButton }) => (
  <div className="d-flex mt-4 justify-content-end">
    {!selectedNode.default && <RemoveComponent onRemove={onRemove} />}
    <FeedbackButton
      text="Update route"
      className="ms-2"
      disabled={!showUpdateRouteButton}
      icon={() => <i className="far fa-paper-plane" />}
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

const Description = ({ text, fullText, steps }) => {
  const [showMore, setShowMore] = useState(false);

  const textLength = text ? text.length : 0;
  const overflows = textLength > 300;
  const content = text ? (overflows && !showMore ? text.slice(0, 300) + ' ...' : text) : '...';

  return (
    <>
      <MarkdownInput className="form-description" readOnly={true} preview={true} value={content} />
      {/* // style={{
        //   marginBottom: either(text, 'inherit', 0),
        //   padding: either(text, '12px', 0),
        //   paddingBottom: either(overflows || !text, 0, '12px')
        // }}> */}
      <div className="steps" style={{ paddingBottom: 10, paddingLeft: 12 }}>
        defined on {steps.map(step => <span className="badge bg-warning text-dark" style={{ marginLeft: 5 }}>{step}</span>)}
      </div>
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
  <button className="btn btn-sm btn-danger" onClick={onRemove}>
    <i className="fas fa-trash me-2"></i>
    Remove this component
  </button>
);
