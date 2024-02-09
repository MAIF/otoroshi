import React, {
  forwardRef,
  Suspense,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
import { Link } from 'react-router-dom';
import { useLocation } from 'react-router';
import {
  nextClient,
  getCategories,
  getOldPlugins,
  getPlugins,
} from '../../services/BackOfficeServices';

import { Backend, Frontend, Plugins } from '../../forms/ng_plugins';

import {
  EXCLUDED_PLUGINS,
  LEGACY_PLUGINS_WRAPPER,
  PLUGIN_INFORMATIONS_SCHEMA,
} from './DesignerConfig';
import Loader from '../../components/Loader';
import { FeedbackButton } from './FeedbackButton';
import { toUpperCaseLabels, REQUEST_STEPS_FLOW, firstLetterUppercase } from '../../util';
import { NgBooleanRenderer, NgForm, NgSelectRenderer } from '../../components/nginputs';
const CodeInput = React.lazy(() => Promise.resolve(require('../../components/inputs/CodeInput')));

import snakeCase from 'lodash/snakeCase';
import camelCase from 'lodash/camelCase';
import isEqual from 'lodash/isEqual';
import isFunction from 'lodash/isFunction';
import _ from 'lodash';
import { HTTP_COLORS } from './RouteComposition';

import { getPluginsPatterns } from './patterns';
import { EurekaTargetForm } from './EurekaTargetForm';
import { ExternalEurekaTargetForm } from './ExternalEurekaTargetForm';
import { MarkdownInput } from '../../components/nginputs/MarkdownInput';
import { PillButton } from '../../components/PillButton';

const TryItComponent = React.lazy(() => import('./TryIt'));

const HeaderNode = ({ selectedNode, text, icon }) => (
  <Dot selectedNode={selectedNode} style={{ border: 'none' }}>
    <div className="flex-column p-1" style={{ color: 'var(--color_level2)' }}>
      <i className={`fas fa-arrow-${icon}`} />
      <span>{text}</span>
    </div>
  </Dot>
);

const Status = ({ value }) => (
  <div
    className="status-dot"
    title={value ? 'plugin enabled' : 'plugin disabled'}
    style={{ backgroundColor: value ? 'var(--color-green)' : 'var(--color-red)' }}
  />
);

const Legacy = ({ value }) => (
  <div
    className="legacy-dot"
    title="legacy plugin"
    style={{ display: !!value ? 'block' : 'none' }}
  />
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
  legacy,
  onUp,
  onDown,
  arrows = { up: false, down: false },
  style = {},
}) => (
  <div
    className={`dot ${className}`}
    style={{
      cursor: clickable ? 'pointer' : 'initial',
      opacity: !selectedNode || highlighted ? 1 : 0.25,
      backgroundColor: highlighted ? 'var(--color-primary)' : 'var(--bg-color_level2)',
      color: highlighted ? 'var(--color-white)' : 'var(--color_level2)',
      ...style,
    }}
    onClick={(e) => {
      e.stopPropagation();
      if (onClick) onClick(e);
    }}
  >
    <div className="d-flex status-dots">
      {enabled !== undefined && <Status value={enabled} />}
      {legacy !== undefined && <Legacy value={legacy} />}
    </div>
    {icon && <i className={`fas fa-${icon} dot-icon`} />}
    {children && children}

    {highlighted && (
      <div className="flex flex-column node-cursor">
        {arrows.up && <i className="fas fa-chevron-up" onClick={onUp} />}
        {arrows.down && <i className="fas fa-chevron-down" onClick={onDown} />}
      </div>
    )}
  </div>
);

const RemoveButton = ({ onRemove }) => {
  return (
    <div
      onClick={onRemove}
      className="delete-node-button d-flex align-items-center justify-content-center"
    >
      <i className="fas fa-times" />
    </div>
  );
};

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
  onUp,
  onDown,
  onRemove,
  arrows,
}) => {
  const { id, name, legacy, nodeId } = element;
  const highlighted = selectedNode && selectedNode.nodeId === nodeId;

  return (
    <>
      <Dot
        onUp={onUp}
        onDown={onDown}
        className={className}
        clickable={true}
        selectedNode={selectedNode}
        style={{
          borderWidth: disableBorder ? 0 : 1,
          fontWeight: bold ? 'bold' : 'normal',
          ...style,
        }}
        onClick={(e) => {
          e.stopPropagation();
          setSelectedNode();
        }}
        highlighted={highlighted}
        arrows={arrows}
        legacy={legacy}
        enabled={enabled}
      >
        <span className="dot-text">{name || id}</span>
        {highlighted && id !== 'Frontend' && id !== 'Backend' && (
          <RemoveButton onRemove={onRemove} />
        )}
      </Dot>
      {!hideLink && <Hr highlighted={!selectedNode} />}
    </>
  );
};

const Hr = ({ highlighted = true, flex }) => (
  <div
    className="vertical-line"
    style={{
      opacity: highlighted ? 1 : 0.25,
      flex: flex ? 1 : 'initial',
    }}
  />
);

const ServiceView = () => {
  return (
    <div onClick={(e) => e.stopPropagation()} className="plugins-stack editor-view">
      <p>
        You are on a route composition. You can click to the routes button on the navbar to edit the
        frontends/backends.
      </p>
    </div>
  );
};

const FormContainer = ({
  selectedNode,
  route,
  preview,
  showPreview,
  alertModal,
  serviceMode,
  nodes,
  setSelectedNode,
  ...props
}) => {
  const isOnFrontendBackend = selectedNode && ['Frontend', 'Backend'].includes(selectedNode.id);

  const selectFrontend = () => setSelectedNode(nodes.find((n) => n.id === 'Frontend'));
  const selectBackend = () => setSelectedNode(nodes.find((n) => n.id === 'Backend'));

  return (
    <div
      className="col-sm-8 relative-container flex-column flow-container p-3"
      style={{ paddingRight: 0 }}
      id="form-container"
    >
      <UnselectedNode
        hideText={selectedNode}
        route={route}
        selectFrontend={selectFrontend}
        selectBackend={selectBackend}
        {...props}
      />
      {serviceMode && isOnFrontendBackend && <ServiceView />}
      {selectedNode && (!serviceMode || (serviceMode && !isOnFrontendBackend)) && (
        <EditView
          {...props}
          route={route}
          selectedNode={selectedNode}
          setSelectedNode={setSelectedNode}
          hidePreview={() =>
            showPreview({
              ...preview,
              enabled: false,
            })
          }
        />
      )}
      {alertModal.show && <Modal {...alertModal} />}
    </div>
  );
};

const Modal = ({ question, onOk, onCancel }) => (
  <div className="designer-modal d-flex align-items-center justify-content-start flex-column p-3 pt-4">
    <h4>{question}</h4>
    <div className="d-flex ms-auto">
      <button type="button" className="btn btn-sm btn-danger me-1" onClick={onCancel}>
        <i className="fas fa-times me-1" />
        Cancel
      </button>
      <button type="button" className="btn btn-sm btn-success" onClick={onOk}>
        <i className="fas fa-check me-1" />
        Delete
      </button>
    </div>
  </div>
);

export default forwardRef(
  ({ value, setSaveButton, setTestingButton, setMenu, history, setValue, ...props }, ref) => {
    const { routeId } = props;
    const location = useLocation();

    const viewPlugins = new URLSearchParams(location.search).get('view_plugins');
    const subTab = new URLSearchParams(location.search).get('sub_tab');

    const childRef = useRef();

    useImperativeHandle(ref, () => ({
      onTestingButtonClick() {
        childRef.current.toggleTryIt();
      },
    }));

    useEffect(() => {
      if (location?.state?.showTryIt || window.location.search.includes('showTryIt')) {
        childRef.current.toggleTryIt();
        props.toggleTesterButton(true);
      } else if (location?.state?.plugin) childRef.current.selectPlugin(location?.state?.plugin);
    }, [location.state]);

    return (
      <Designer
        ref={childRef}
        toggleTesterButton={props.toggleTesterButton}
        history={history}
        viewPlugins={props.viewPlugins || viewPlugins}
        subTab={subTab}
        routeId={routeId}
        location={location}
        value={value}
        setValue={setValue}
        setSaveButton={setSaveButton}
        setTestingButton={setTestingButton}
        setMenu={setMenu}
        pathname={location.pathname}
        serviceMode={location.pathname.includes('route-compositions')}
      />
    );
  }
);

const FrontendNode = ({ frontend, selectedNode, setSelectedNode, removeNode }) => (
  <div className="main-view relative-container" style={{ flex: 'initial' }}>
    <NodeElement
      element={frontend}
      className="frontend-container-button"
      selectedNode={selectedNode}
      setSelectedNode={setSelectedNode}
      bold={true}
      onRemove={removeNode}
    />
    <div
      className="frontend-button"
      style={{
        opacity: !selectedNode || (selectedNode && selectedNode.id === 'Frontend') ? 1 : 0.25,
        background:
          selectedNode && selectedNode.id === 'Frontend'
            ? 'linear-gradient(to right, var(--color-primary) 55%, transparent 1%)'
            : 'linear-gradient(to right, var(--bg-color_level2) 55%, transparent 1%)',
        color:
          selectedNode && selectedNode.id === 'Frontend'
            ? 'var(--color-white)'
            : 'var(--color_level2)',
      }}
    >
      <i className="fas fa-user frontend-button-icon" />
    </div>
  </div>
);

const Container = ({ children, onClick, showTryIt }) => {
  const [propagate, setPropagate] = useState();

  return (
    <div
      className="h-100 col-12 route-designer div-overflowy"
      onMouseDown={(e) => {
        setPropagate(
          ![
            document.getElementById('form-container'),
            ...document.getElementsByClassName('delete-node-button'),
            ...document.querySelectorAll('i[class^="fas fa-chevron"]'),
          ]
            .filter((f) => f)
            .find((d) => d.contains(e.target))
        );
      }}
      onMouseUp={(e) => {
        e.stopPropagation();
        if (propagate) onClick(e);

        setPropagate(false);
      }}
    >
      {children}
    </div>
  );
};

const BackendNode = ({ selectedNode, backend, ...props }) => (
  <div
    className="main-view backend-button"
    style={{
      opacity: !selectedNode ? 1 : selectedNode.nodeId === 'Backend' ? 1 : 0.25,
    }}
  >
    <i className="fas fa-bullseye backend-icon" />
    <NodeElement
      element={backend}
      selectedNode={selectedNode}
      hideLink={true}
      disableBorder={true}
      bold={true}
      {...props}
    />
  </div>
);

const BackendCallNode = ({ selectedNode, backendCall, isPluginEnabled, ...props }) => (
  <div
    className="main-view backend-call-button"
    style={{
      opacity: !selectedNode ? 1 : selectedNode.id === backendCall.id ? 1 : 0.25,
    }}
  >
    <NodeElement
      element={backendCall}
      selectedNode={selectedNode}
      hideLink={false}
      disableBorder={false}
      bold={false}
      enabled={isPluginEnabled(backendCall)}
      {...props}
    />
  </div>
);

const InBoundFlow = (props) => (
  <div className="col-sm-6 flex-column">
    <div className="main-view">{props.children}</div>
  </div>
);

const OutBoundFlow = (props) => (
  <div className="col-sm-6 pe-3 flex-column">
    <div className="main-view">{props.children}</div>
  </div>
);

const Flow = (props) => (
  <div className="col-sm-4 pe-3 pb-1 d-flex flex-column">{props.children}</div>
);

const PluginsContainer = ({
  handleSearch,
  showLegacy,
  setShowLegacy,
  onExpandAll,
  expandAll,
  searched,
  plugins,
  categories,
  addNode,
  showPreview,
  hidePreview,
}) => (
  <div className="plugins-stack-column">
    <div className="elements">
      <div className="plugins-background-bar" />
      <SearchBar handleSearch={handleSearch} />
      <div className="plugins-action-container mb-2">
        <button
          type="button"
          className="btn btn-sm btn-warning text-light plugins-action"
          style={{ marginRight: 5 }}
          onClick={() => {
            window.localStorage.setItem(
              'io.otoroshi.next.designer.showLegacy',
              String(!showLegacy)
            );
            setShowLegacy(!showLegacy);
          }}
        >
          {showLegacy ? 'Hide legacy plugins' : 'Show legacy plugins'}
        </button>
        <button
          type="button"
          className="btn btn-sm btn-warning text-light plugins-action"
          onClick={onExpandAll}
        >
          {expandAll ? 'Collapse all' : 'Expand all'}
        </button>
      </div>
      <div className="relative-container" id="plugins-stack-container">
        <PluginsStack
          forceOpen={!!searched}
          expandAll={expandAll}
          elements={plugins
            .filter((plugin) => (showLegacy ? true : !plugin.legacy))
            .reduce(
              (acc, plugin) => {
                if (plugin.selected || plugin.filtered) return acc;
                return acc.map((group) => {
                  if (!plugin.plugin_categories) {
                    plugin.plugin_categories = [];
                  }
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
          showPreview={showPreview}
          hidePreview={hidePreview}
        />
      </div>
    </div>
  </div>
);

class Designer extends React.Component {
  state = {
    backends: [],
    categories: [],
    nodes: [],
    plugins: [],
    selectedNode: undefined,
    route: null,
    originalRoute: null,
    loading: true,
    searched: '',
    expandAll: false,
    showLegacy:
      (window.localStorage.getItem('io.otoroshi.next.designer.showLegacy') || 'false') === 'true',
    preview: {
      enabled: false,
    },
    frontend: {},
    backend: {},
    alertModal: {
      show: false,
    },
    hiddenSteps: {
      MatchRoute: true,
      PreRoute: true,
      ValidateAccess: true,
      TransformRequest: true,
      TransformResponse: true,
    },
    advancedDesignerView: null,
    showTryIt: false,
  };

  componentDidMount() {
    this.loadData();
    this.injectSaveButton();
    this.injectNavbarMenu();
    this.mountShortcuts();
  }

  componentWillUnmount() {
    this.unmountShortcuts();
  }

  saveShortcut = (e) => {
    if (e.keyCode === 83 && (e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      this.saveRoute();
    }
  };

  mountShortcuts = () => {
    document.body.addEventListener('keydown', this.saveShortcut);
  };

  unmountShortcuts = () => {
    document.body.removeEventListener('keydown', this.saveShortcut);
  };

  toggleTryIt = () => {
    this.setState({ showTryIt: true });
  };

  selectPlugin = (pluginId) => {
    this.setState({ locationPlugin: pluginId });
  };

  injectSaveButton = () => {
    const isOnRouteCompositions = this.props.location.pathname.includes('route-compositions');

    this.props.setSaveButton(
      <FeedbackButton
        type="success"
        className="ms-2 mb-1"
        onPress={this.saveRoute}
        text="Save"
        _disabled={isEqual(this.state.route, this.state.originalRoute)}
      />
    );
  };

  injectOverrideRoutePluginsForm = () => (
    <>
      <span className="me-3 mt-2">Override route plugins</span>{' '}
      {/* mt-2 to fix the form lib css ...*/}
      <NgBooleanRenderer
        value={this.state.route?.overridePlugins}
        onChange={(overridePlugins) => {
          this.setState(
            {
              route: {
                ...this.state.route,
                overridePlugins,
              },
            },
            () => {
              this.injectNavbarMenu();
              this.injectSaveButton();
            }
          );
        }}
      />
    </>
  );

  injectNavbarMenu = () => {
    if (this.props.viewPlugins && this.props.viewPlugins !== -1)
      this.props.setMenu(this.injectOverrideRoutePluginsForm());
  };

  loadHiddenStepsFromLocalStorage = (route) => {
    const data = localStorage.getItem('hidden_steps');
    if (data) {
      try {
        const hiddenSteps = JSON.parse(data);
        if (hiddenSteps[route.id]) {
          this.setState({
            hiddenSteps: hiddenSteps[route.id],
          });
        }
      } catch (_) {}
    }
  };

  storeHiddenStepsToLocalStorage = (newHiddenSteps) => {
    const data = localStorage.getItem('hidden_steps');
    if (data) {
      try {
        const hiddenSteps = JSON.parse(data);
        localStorage.setItem(
          'hidden_steps',
          JSON.stringify({
            ...hiddenSteps,
            [this.state.route.id]: newHiddenSteps,
          })
        );
      } catch (_) {}
    } else {
      localStorage.setItem(
        'hidden_steps',
        JSON.stringify({
          [this.state.route.id]: newHiddenSteps,
        })
      );
    }
  };

  loadData = () => {
    Promise.all([
      nextClient.find(nextClient.ENTITIES.BACKENDS),
      this.props.value
        ? Promise.resolve(this.props.value)
        : nextClient.fetch(
            this.props.serviceMode ? nextClient.ENTITIES.SERVICES : nextClient.ENTITIES.ROUTES,
            this.props.routeId
          ),
      getCategories(),
      Promise.resolve(
        Plugins('Designer').map((plugin) => {
          return {
            ...plugin,
            config_schema: isFunction(plugin.config_schema)
              ? plugin.config_schema({
                  showAdvancedDesignerView: (pluginName) => {
                    this.setState({ advancedDesignerView: pluginName });
                  },
                })
              : plugin.config_schema,
          };
        })
      ),
      getOldPlugins(),
      getPlugins(),
    ]).then(([backends, r, categories, plugins, oldPlugins, metadataPlugins]) => {
      let route =
        this.props.viewPlugins !== null && this.props.viewPlugins !== -1
          ? {
              ...r,
              overridePlugins: true,
              plugins: [],
              ...r.routes[~~this.props.viewPlugins],
            }
          : r;

      if (route.error) {
        this.setState({
          loading: false,
          notFound: true,
        });
        return;
      }

      const formattedPlugins = [
        ...plugins.map((p) => ({
          ...(metadataPlugins.find((metaPlugin) => metaPlugin.id === p.id) || {}),
          ...p,
        })),
        ...oldPlugins.map((p) => ({
          ...p,
          legacy: true,
        })),
      ]
        .filter(this.filterSpecificPlugin)
        .map((plugin) => ({
          ...plugin,
          config_schema: toUpperCaseLabels(plugin.config_schema || plugin.configSchema || {}),
          config: plugin.default_config || plugin.defaultConfig,
        }));

      const routePlugins = route.plugins
        .filter((ref) =>
          formattedPlugins.find((p) => p.id === ref.plugin || p.id === ref.config.plugin)
        )
        .map((ref) => ({
          ...ref,
          plugin_index: Object.fromEntries(
            Object.entries(ref.plugin_index || {}).map(([key, v]) => [
              firstLetterUppercase(camelCase(key)),
              v,
            ])
          ),
          ...formattedPlugins.find((p) => p.id === ref.plugin || p.id === ref.config.plugin),
        }));
      const pluginsWithNodeId = this.generateInternalNodeId(routePlugins);

      const routeWithNodeId = {
        ...route,
        plugins: route.plugins
          .filter((ref) =>
            formattedPlugins.find((p) => p.id === ref.plugin || p.id === ref.config.plugin)
          )
          .map((plugin, i) => ({
            ...plugin,
            nodeId: pluginsWithNodeId[i].nodeId,
          })),
      };

      this.loadHiddenStepsFromLocalStorage(routeWithNodeId);

      const nodes = pluginsWithNodeId.some((p) => Object.keys(p.plugin_index || {}).length > 0)
        ? pluginsWithNodeId
        : this.generatedPluginIndex(pluginsWithNodeId);

      this.setState(
        {
          backends,
          loading: false,
          categories: categories.filter((category) => !['Job'].includes(category)),
          route: { ...routeWithNodeId },
          originalRoute: { ...routeWithNodeId },
          plugins: formattedPlugins.map((p) => ({
            ...p,
            selected: p.plugin_multi_inst
              ? false
              : routeWithNodeId.plugins.find((r) => r.plugin === p.id),
          })),
          nodes,
          frontend: {
            ...Frontend,
            config_schema: toUpperCaseLabels(Frontend.schema),
            config_flow: Frontend.flow,
            nodeId: 'Frontend',
          },
          backend: {
            ...Backend,
            config_schema: toUpperCaseLabels(Backend.schema),
            config_flow: Backend.flow,
            nodeId: 'Backend',
          },
          selectedNode: this.getSelectedNodeFromLocation(routeWithNodeId.plugins, formattedPlugins),
        },
        this.injectNavbarMenu
      );
    });
  };

  getSelectedNodeFromLocation = (routePlugins, plugins) => {
    const id = this.state.locationPlugin;
    const routePlugin = routePlugins.find((p) => p.plugin === id);

    const plugin = plugins.find((p) => p.id === id);

    if (routePlugin && plugin)
      return {
        ...routePlugin,
        ...plugin,
      };

    return null;
  };

  generateInternalNodeId = (nodes) =>
    nodes.reduce(
      (acc, node) => [
        ...acc,
        {
          ...node,
          nodeId: acc.find((n) => n.nodeId == node.id)
            ? `${node.id}-${acc.reduce((a, c) => a + (c.id.startsWith(node.id) ? 1 : 0), 0)}`
            : node.id,
        },
      ],
      []
    );

  generateNewInternalNodeId = (nodeId) =>
    `${nodeId}-${this.state.nodes.reduce((a, c) => a + (c.id?.startsWith(nodeId) ? 1 : 0), 0)}`;

  generatedPluginIndex = (plugins) => {
    const getStep = (step, elements, element, pluginSteps) =>
      [...elements[step], pluginSteps.includes(step) ? element : undefined].filter((f) => f);

    const pluginsIndexes = plugins.reduce(
      (acc, curr) => {
        const pluginSteps = curr.plugin_steps || [];
        return {
          MatchRoute: getStep('MatchRoute', acc, curr, pluginSteps),
          PreRoute: getStep('PreRoute', acc, curr, pluginSteps),
          ValidateAccess: getStep('ValidateAccess', acc, curr, pluginSteps),
          TransformRequest: getStep('TransformRequest', acc, curr, pluginSteps),
          TransformResponse: getStep('TransformResponse', acc, curr, pluginSteps),
        };
      },
      {
        MatchRoute: [],
        PreRoute: [],
        ValidateAccess: [],
        TransformRequest: [],
        TransformResponse: [],
      }
    );

    const pluginsWithIndex = Object.values(
      Object.fromEntries(
        Object.entries(pluginsIndexes).map(([step, plugins]) => {
          return [
            step,
            plugins.map((plugin, idx) => ({
              ...plugin,
              plugin_index: {
                ...(plugin.plugin_index || {}),
                [step]: idx,
              },
            })),
          ];
        })
      )
    ).flatMap((f) => f);

    return _.chain(pluginsWithIndex)
      .groupBy('nodeId')
      .map((values, nodeId) => ({
        nodeId,
        ...values.reduce((acc, curr) => ({
          ...acc,
          plugin_index: {
            ...acc.plugin_index,
            ...curr.plugin_index,
          },
        })),
      }))
      .value();
  };

  calculateIndexFor = (node) => {
    const { nodes } = this.state;
    return Object.entries({
      MatchRoute: nodes
        .filter((n) => n.plugin_index.MatchRoute !== undefined)
        .sort((a, b) => a.plugin_index.MatchRoute - b.plugin_index.MatchRoute),
      PreRoute: nodes
        .filter((n) => n.plugin_index.PreRoute !== undefined)
        .sort((a, b) => a.plugin_index.PreRoute - b.plugin_index.PreRoute),
      ValidateAccess: nodes
        .filter((n) => n.plugin_index.ValidateAccess !== undefined)
        .sort((a, b) => a.plugin_index.ValidateAccess - b.plugin_index.ValidateAccess),
      TransformRequest: nodes
        .filter((n) => n.plugin_index.TransformRequest !== undefined)
        .sort((a, b) => a.plugin_index.TransformRequest - b.plugin_index.TransformRequest),
      TransformResponse: nodes
        .filter((n) => n.plugin_index.TransformResponse !== undefined)
        .sort((a, b) => a.plugin_index.TransformResponse - b.plugin_index.TransformResponse),
    })
      .filter(([step, _]) => node.plugin_steps.includes(step))
      .reduce((pluginIndex, curr) => {
        const [step, indexes] = curr;
        return {
          ...pluginIndex,
          [step]: indexes.length === 0 ? 0 : indexes[indexes.length - 1].plugin_index[step] + 1,
        };
      }, {});
  };

  filterSpecificPlugin = (plugin) => {
    if (!plugin.plugin_steps) {
      plugin.plugin_steps = [];
    }
    // plugin.plugin_steps &&
    return (
      !plugin.plugin_steps.includes('Sink') &&
      // !plugin.plugin_steps.includes('HandlesTunnel') &&
      !['job', 'sink'].includes(plugin.pluginType) &&
      !EXCLUDED_PLUGINS.plugin_visibility.includes(plugin.plugin_visibility) &&
      !EXCLUDED_PLUGINS.ids.includes(plugin.id.replace('cp:', ''))
    );
  };
  removeNode = (e) => {
    if (e && typeof e.stopPropagation === 'function') e.stopPropagation();

    this.setState({
      alertModal: {
        show: true,
        question: `Delete this plugin ?`,
        onCancel: (e) => {
          e.stopPropagation();
          this.setState({
            alertModal: {
              show: false,
            },
          });
        },
        onOk: (e) => {
          e.stopPropagation();
          const { selectedNode, nodes, plugins, route } = this.state;
          const { nodeId, id } = selectedNode;

          this.setState(
            {
              nodes: nodes.filter((node) => node.nodeId !== nodeId),
              plugins: plugins.map((plugin) => ({
                ...plugin,
                selected: plugin.id === id ? undefined : plugin.selected,
              })),
              selectedNode: undefined,
              alertModal: {
                show: false,
              },
            },
            () => {
              this.updateRoute({
                ...route,
                plugins: route.plugins.filter((plugin) => plugin.nodeId !== nodeId),
              });
            }
          );
        },
      },
    });
  };

  addNode = (node) => {
    const nodeId = this.generateNewInternalNodeId(node.id);

    const newNode = {
      ...node,
      nodeId,
      plugin_index: this.calculateIndexFor({ ...node, nodeId }),
    };

    const { nodes, plugins, route } = this.state;

    this.setState(
      {
        nodes: [...nodes, newNode],
        plugins: plugins.map((p) => ({
          ...p,
          selected: p.id === newNode.id ? !p.plugin_multi_inst : p.selected,
        })),
      },
      () => {
        this.updateRoute(
          {
            ...route,
            plugins: [
              ...route.plugins,
              {
                plugin_index: newNode.plugin_index,
                nodeId: newNode.nodeId,
                plugin: newNode.legacy ? LEGACY_PLUGINS_WRAPPER[newNode.pluginType] : newNode.id,
                enabled: node.enabled || true,
                debug: node.debug || false,
                include: node.include || [],
                exclude: node.exclude || [],
                config: newNode.legacy
                  ? {
                      plugin: newNode.id,
                      // [newNode.configRoot]: {
                      ...newNode.config,
                      // },
                    }
                  : {
                      ...newNode.config,
                    },
              },
            ],
          },
          () => {
            this.setState({
              selectedNode: newNode,
            });
          }
        );
      }
    );
  };

  clearPlugins = () => {
    window.newConfirm('Are you sure you want to delete all current plugins ?').then((ok) => {
      if (ok) {
        const newRoute = this.state.route;
        newRoute.plugins = [];
        this.setState({
          route: newRoute,
          nodes: [],
          plugins: this.state.plugins.map((p) => ({
            ...p,
            selected: false,
          })),
        });
        this.updateRoute({ ...newRoute });
      }
    });
  };

  deleteRoute = () => {
    window.newConfirm('are you sure you want to delete this route ?', (ok) => {
      if (ok) {
        nextClient.deleteById(nextClient.ENTITIES.ROUTES, this.state.route.id).then(() => {
          if (history) {
            history.push('/routes');
          } else {
            window.location = '/bo/dashboard/routes';
          }
        });
      }
    });
  };

  addNodes = (new_nodes) => {
    const { plugins, route } = this.state;

    let newNodes = [];
    let newRoute = { ...route, plugins: [] };

    new_nodes
      .filter((node) => !!node)
      .map((node) => {
        const nodeId = this.generateNewInternalNodeId(node.id);
        const newNode = {
          ...node,
          nodeId,
          plugin_index: node.plugin_index,
        };
        newNodes = [...newNodes, newNode];
        newRoute = {
          ...newRoute,
          plugins: [
            ...newRoute.plugins,
            {
              plugin_index: newNode.plugin_index,
              nodeId: newNode.nodeId,
              plugin: newNode.legacy ? LEGACY_PLUGINS_WRAPPER[newNode.pluginType] : newNode.id,
              enabled: node.enabled === false ? false : true,
              debug: node.debug || false,
              include: node.include || [],
              exclude: node.exclude || [],
              config: {
                ...newNode.config,
                plugin: newNode.legacy ? newNode.id : undefined,
              },
            },
          ],
        };
      });
    this.setState(
      {
        selectedNode: null,
        nodes: newNodes,
        route: newRoute,
        plugins: plugins.map((p) => ({
          ...p,
          selected: route.plugins.find((plugin) => plugin.id === p.id)
            ? p.plugin_multi_inst
              ? false
              : true
            : false,
        })),
      },
      () => {
        this.updateRoute({ ...newRoute });
      }
    );
  };

  setNodes = (new_nodes) => {
    const { nodes, plugins, route } = this.state;
    const newPlugins = [...plugins];
    let newNodes = [];
    let newRoute = { ...route, plugins: [] };
    new_nodes
      .filter((node) => !!node)
      .map((node) => {
        const nodeId = this.generateNewInternalNodeId(node.id);
        const newNode = {
          ...node,
          nodeId,
          plugin_index: node.plugin_index,
        };
        newNodes = [...newNodes, newNode];
        newRoute = {
          ...newRoute,
          plugins: [
            ...newRoute.plugins,
            {
              plugin_index: newNode.plugin_index,
              nodeId: newNode.nodeId,
              plugin: newNode.legacy ? LEGACY_PLUGINS_WRAPPER[newNode.pluginType] : newNode.id,
              enabled: node.enabled === false ? false : true,
              debug: node.debug || false,
              include: node.include || [],
              exclude: node.exclude || [],
              config: {
                ...newNode.config,
                plugin: newNode.legacy ? newNode.id : undefined,
              },
            },
          ],
        };
      });
    this.setState(
      {
        selectedNode: null,
        nodes: newNodes,
        route: newRoute,
        plugins: newPlugins.map((p) => ({
          ...p,
          selected: false,
        })),
      },
      () => {
        this.updateRoute({ ...newRoute });
      }
    );
  };

  swap = (node, step, offset) => {
    const { nodeId } = node;

    const nodes = this.state.nodes
      .filter((n) => n.plugin_index[step] !== undefined)
      .sort((a, b) => a.plugin_index[step] - b.plugin_index[step]);

    const swapIndexNode = nodes.findIndex((n) => n.nodeId === nodeId);

    const newNodes = this.state.nodes.map((n) => {
      if (n.nodeId === nodeId)
        return {
          ...n,
          plugin_index: {
            ...n.plugin_index,
            [step]: nodes[swapIndexNode + offset].plugin_index[step],
          },
        };
      else if (n.nodeId === nodes[swapIndexNode + offset].nodeId)
        return {
          ...n,
          plugin_index: {
            ...n.plugin_index,
            [step]: node.plugin_index[step],
          },
        };
      return n;
    });

    this.setState({ nodes: newNodes });

    this.updateRoute({
      ...this.state.route,
      plugins: this.state.route.plugins.map((plugin) => ({
        ...plugin,
        plugin_index: newNodes.find((n) => n.nodeId === plugin.nodeId)?.plugin_index,
      })),
    });
  };

  onUp = (e, node, step) => {
    e.stopPropagation();
    this.swap(node, step, -1);
  };

  onDown = (e, node, step) => {
    e.stopPropagation();
    this.swap(node, step, 1);
  };

  handleSearch = (searched) => {
    this.setState({
      searched,
      plugins: this.state.plugins.map((plugin) => ({
        ...plugin,
        filtered: !(
          (plugin.id ? plugin.id.toLowerCase().includes(searched.toLowerCase()) : false) ||
          (plugin.name ? plugin.name.toLowerCase().includes(searched.toLowerCase()) : false)
        ),
      })),
    });
  };

  updatePlugin = (nodeId, pluginId, item) => {
    const { route } = this.state;
    return this.updateRoute({
      ...route,
      frontend: pluginId === 'Frontend' ? item.plugin : route.frontend,
      backend: pluginId === 'Backend' ? item.plugin : route.backend,
      plugins: route.plugins.map((plugin) => {
        if (plugin.nodeId === nodeId)
          return {
            ...plugin,
            ...item.status,
            config: item.plugin,
          };

        return plugin;
      }),
    });
  };

  saveRoute = () => {
    const { route, originalRoute } = this.state;

    let newRoute;

    if (this.props.viewPlugins !== null && this.props.viewPlugins !== -1) {
      newRoute = {
        ...originalRoute,
        routes: originalRoute.routes.map((r, i) => {
          if (String(i) === String(this.props.viewPlugins))
            return {
              ...r,
              plugins: route.plugins,
              overridePlugins: route.overridePlugins,
            };
          else return r;
        }),
      };
    } else {
      newRoute = {
        ...route,
        plugins: route.plugins.map((plugin) => ({
          ...plugin,
          plugin_index: Object.fromEntries(
            Object.entries(
              plugin.plugin_index ||
                this.state.nodes.find((n) => n.nodeId === plugin.nodeId)?.plugin_index ||
                {}
            ).map(([key, v]) => [snakeCase(key), v])
          ),
        })),
      };
    }

    if (this.props.setValue) this.props.setValue(newRoute);

    return nextClient
      .update(
        this.props.serviceMode ? nextClient.ENTITIES.SERVICES : nextClient.ENTITIES.ROUTES,
        newRoute
      )
      .then((r) => {
        if (r.error) throw r.error;
        else {
          this.setState({
            originalRoute: { ...route },
          });
          this.injectSaveButton();
        }
      });
  };

  updateRoute = (r, callback) =>
    new Promise((resolve) => {
      this.setState({ route: r }, () => {
        this.injectSaveButton();

        if (this.props.setValue) this.props.setValue(r);

        if (callback) callback();

        resolve();
      });
    });

  isPluginEnabled = (value) =>
    this.state.route.plugins.find((plugin) => plugin.nodeId === value.nodeId)?.enabled;

  renderInBound = () => {
    let steps = [...REQUEST_STEPS_FLOW];

    const { selectedNode, nodes, hiddenSteps } = this.state;

    const matchRoute = nodes
      .filter((n) => n.plugin_index.MatchRoute !== undefined || this.state.plugins.find(p => p.id === n.plugin)?.plugin_steps.indexOf('MatchRoute') > -1)
      .map((n, idx) => {
        if (!n.plugin_index) {
          n.plugin_index = {};
        }
        if (n.plugin_index.MatchRoute === undefined) {
          n.plugin_index.MatchRoute = idx;
        }
        return n;
      })
      .sort((a, b) => a.plugin_index.MatchRoute - b.plugin_index.MatchRoute);
    const preRoute = nodes
      .filter((n) => n.plugin_index.PreRoute !== undefined || this.state.plugins.find(p => p.id === n.plugin)?.plugin_steps.indexOf('PreRoute') > -1)
      .map((n, idx) => {
        if (!n.plugin_index) {
          n.plugin_index = {};
        }
        if (n.plugin_index.PreRoute === undefined) {
          n.plugin_index.PreRoute = idx;
        }
        return n;
      })
      .sort((a, b) => a.plugin_index.PreRoute - b.plugin_index.PreRoute);
    const validateAccess = nodes
      .filter((n) => n.plugin_index.ValidateAccess !== undefined || this.state.plugins.find(p => p.id === n.plugin)?.plugin_steps.indexOf('ValidateAccess') > -1)
      .map((n, idx) => {
        if (!n.plugin_index) {
          n.plugin_index = {};
        }
        if (n.plugin_index.ValidateAccess === undefined) {
          n.plugin_index.ValidateAccess = idx;
        }
        return n;
      })
      .sort((a, b) => a.plugin_index.ValidateAccess - b.plugin_index.ValidateAccess);
    const transformRequest = nodes
      .filter((n) => n.plugin_index.TransformRequest !== undefined || this.state.plugins.find(p => p.id === n.plugin)?.plugin_steps.indexOf('TransformRequest') > -1)
      .map((n, idx) => {
        if (!n.plugin_index) {
          n.plugin_index = {};
        }
        if (n.plugin_index.TransformRequest === undefined) {
          n.plugin_index.TransformRequest = idx;
        }
        return n;
      })
      .sort((a, b) => a.plugin_index.TransformRequest - b.plugin_index.TransformRequest);

    return [matchRoute, preRoute, validateAccess, transformRequest].map((nodes, i) => {
      if (nodes.length === 0) return null;

      return (
        <React.Fragment key={`inbound-${i}`}>
          <span
            className="badge bg-warning text-dark"
            style={{
              cursor: 'pointer',
            }}
            onClick={() => {
              const hidden_steps = {
                ...hiddenSteps,
                [steps[i]]: !hiddenSteps[steps[i]],
              };
              this.storeHiddenStepsToLocalStorage(hidden_steps);
              this.setState({
                hiddenSteps: hidden_steps,
              });
            }}
          >
            <i
              className={`me-1 fas fa-chevron-${hiddenSteps[steps[i]] ? 'down' : 'right'}`}
              style={{
                minWidth: '10px',
              }}
            />
            {steps[i]} {!hiddenSteps[steps[i]] && `(${nodes.length})`}
          </span>
          <Hr highlighted={!selectedNode} />
          {hiddenSteps[steps[i]] &&
            nodes.map((node) => (
              <NodeElement
                onUp={(e) => this.onUp(e, node, steps[i])}
                onDown={(e) => this.onDown(e, node, steps[i])}
                enabled={this.isPluginEnabled(node)}
                element={node}
                key={`${node.nodeId}-inbound-${i}`}
                selectedNode={selectedNode}
                setSelectedNode={() => {
                  if (!this.state.alertModal.show) this.setState({ selectedNode: node });
                }}
                onRemove={this.removeNode}
                arrows={this.showArrows(node, steps[i])}
              />
            ))}
        </React.Fragment>
      );
    });
  };

  renderOutBound = () => {
    const responseNodes = this.state.nodes
      .filter((n) => n.plugin_index.TransformResponse !== undefined || this.state.plugins.find(p => p.id === n.plugin)?.plugin_steps.indexOf('TransformResponse') > -1)
      .map((n, idx) => {
        if (!n.plugin_index) {
          n.plugin_index = {};
        }
        if (n.plugin_index.TransformResponse === undefined) {
          n.plugin_index.TransformResponse = idx;
        }
        return n;
      })
      .sort((a, b) => b.plugin_index.TransformResponse - a.plugin_index.TransformResponse);
    return (
      this.state.hiddenSteps.TransformResponse &&
      responseNodes.map((node, i) => (
        <NodeElement
          onUp={(e) => this.onDown(e, node, 'TransformResponse')}
          onDown={(e) => this.onUp(e, node, 'TransformResponse')}
          enabled={this.isPluginEnabled(node)}
          element={node}
          key={`${node.nodeId}-outbound-${i}`}
          setSelectedNode={() => {
            if (!this.state.alertModal.show) this.setState({ selectedNode: node });
          }}
          selectedNode={this.state.selectedNode}
          onRemove={this.removeNode}
          arrows={this.showArrows(node, 'TransformResponse')}
        />
      ))
    );
  };

  transformResponseBadge = () => {
    const { nodes, hiddenSteps } = this.state;
    return (
      nodes.find((n) => n.plugin_index.TransformResponse !== undefined) && (
        <span
          className="badge bg-warning text-dark"
          style={{ cursor: 'pointer' }}
          onClick={() => {
            const hidden_steps = {
              ...hiddenSteps,
              TransformResponse: !hiddenSteps.TransformResponse,
            };
            this.storeHiddenStepsToLocalStorage(hidden_steps);
            this.setState({
              hiddenSteps: hidden_steps,
            });
          }}
        >
          <i
            className={`me-1 fas fa-chevron-${hiddenSteps.TransformResponse ? 'up' : 'right'}`}
            style={{
              minWidth: '10px',
            }}
          />
          TransformResponse
        </span>
      )
    );
  };

  showArrows = (node, step) => {
    const nodes = this.state.nodes
      .filter((n) => n.plugin_index[step] !== undefined)
      .sort((a, b) => a.plugin_index[step] - b.plugin_index[step]);

    if (nodes.length === 1) return undefined;
    else {
      const arrows = {
        up: node.plugin_index[step] === 0 ? false : true,
        down: nodes[nodes.length - 1].nodeId !== node.nodeId ? true : false,
      };

      if (step === 'TransformResponse')
        return {
          up: arrows.down,
          down: arrows.up,
        };

      return arrows;
    }
  };

  render() {
    const {
      loading,
      preview,
      route,
      plugins,
      backends,
      selectedNode,
      originalRoute,
      frontend,
      categories,
      alertModal,
      showLegacy,
      expandAll,
      searched,
      backend,
      advancedDesignerView,
      showTryIt,
    } = this.state;

    const { serviceMode } = this.props;

    const backendCallNodes =
      route && route.plugins
        ? route.plugins
            .map((p) => {
              const id = p.plugin;
              const pluginDef = plugins.filter((pl) => pl.id === id)[0];
              if (pluginDef) {
                if (pluginDef.plugin_steps.indexOf('CallBackend') > -1) {
                  return { ...p, ...pluginDef };
                }
              }
              return null;
            })
            .filter((p) => !!p)
        : [];

    const patterns = getPluginsPatterns(plugins, this.setNodes, this.addNodes, this.clearPlugins);
    plugins.map((p) => {
      if (p.legacy) {
        if (!p.plugin_categories) {
          p.plugin_categories = [];
        }
        p.plugin_categories.push('Legacy');
      }
    });

    // TODO - better error display
    if (!loading && this.state.notFound) return <h1>Route not found</h1>;

    const FullForm = showTryIt ? TryItComponent : advancedDesignerView;

    return (
      <Loader loading={loading}>
        <Container
          showTryIt={showTryIt}
          onClick={() => {
            this.setState({
              selectedNode: undefined,
            });
          }}
        >
          {FullForm && (
            <Suspense fallback={null}>
              <FullForm
                route={route}
                saveRoute={(route) => {
                  this.setState({ route });
                }}
                hide={(e) => {
                  e.stopPropagation();

                  if (this.props.toggleTesterButton) this.props.toggleTesterButton(false);

                  this.setState({
                    selectedNode: backendCallNodes.find((node) => {
                      return node.id.includes(
                        FullForm.name !== 'GraphQLForm'
                          ? FullForm.name === 'MocksDesigner'
                            ? 'otoroshi.next.plugins.MockResponses'
                            : this.state.selectedNode
                          : 'otoroshi.next.plugins.GraphQLBackend'
                      );
                    }),
                    advancedDesignerView: false,
                  });

                  this.setState({
                    showTryIt: false,
                  });
                }}
              />
            </Suspense>
          )}
          <PluginsContainer
            handleSearch={this.handleSearch}
            showLegacy={showLegacy}
            setShowLegacy={(l) => this.setState({ showLegacy: l })}
            onExpandAll={() => this.setState({ expandAll: !expandAll })}
            expandAll={expandAll}
            searched={searched}
            plugins={[...plugins, ...patterns]}
            categories={[...categories, 'Legacy', 'Patterns']}
            addNode={this.addNode}
            showPreview={(element) =>
              this.setState({
                preview: {
                  enabled: true,
                  element,
                },
              })
            }
            hidePreview={() =>
              this.setState({
                preview: {
                  ...preview,
                  enabled: false,
                },
              })
            }
          />
          <div className="relative-container" style={{ flex: 9 }}>
            {preview.enabled ? (
              <EditView
                addNode={this.addNode}
                hidePreview={() =>
                  this.setState({
                    preview: {
                      ...preview,
                      enabled: false,
                    },
                  })
                }
                readOnly={true}
                setRoute={(r) => this.setState({ route: r })}
                selectedNode={preview.element}
                setSelectedNode={(n) => {
                  if (!this.state.alertModal.show) this.setState({ selectedNode: n });
                }}
                route={route}
                plugins={plugins}
                backends={backends}
              />
            ) : (
              <div className="row h-100 mx-1">
                <Flow>
                  <div className="row" style={{ height: '100%' }}>
                    <InBoundFlow>
                      <HeaderNode text="Request" icon="down" selectedNode={selectedNode} />
                      <Hr highlighted={!selectedNode} />
                      <FrontendNode
                        frontend={frontend}
                        selectedNode={selectedNode}
                        removeNode={this.removeNode}
                        setSelectedNode={() => {
                          if (!this.state.alertModal.show)
                            this.setState({ selectedNode: frontend });
                        }}
                      />
                      {this.renderInBound()}
                      <Hr highlighted={!selectedNode} flex={true} />
                    </InBoundFlow>
                    <OutBoundFlow>
                      <HeaderNode text="Response" icon="up" selectedNode={selectedNode} />
                      <Hr highlighted={!selectedNode} flex={true} />
                      {this.renderOutBound()}
                      {this.transformResponseBadge()}
                      <Hr highlighted={!selectedNode} />
                    </OutBoundFlow>
                  </div>
                  {backendCallNodes.length > 0 && (
                    <>
                      <div
                        style={{
                          display: 'flex',
                          flexDirection: 'column',
                          alignItems: 'center',
                          width: '100%',
                        }}
                      >
                        <span
                          className="badge bg-warning text-dark"
                          style={{
                            width: '100%',
                            opacity: !selectedNode ? 1 : 0.25,
                            cursor: 'pointer',
                          }}
                        >
                          CallBackend
                        </span>
                        <Hr highlighted={!selectedNode} />
                      </div>
                      {backendCallNodes.map((node) => (
                        <BackendCallNode
                          key={node.id}
                          isPluginEnabled={this.isPluginEnabled}
                          backendCall={node}
                          selectedNode={selectedNode}
                          hideLink={!node.plugin_backend_call_delegates}
                          setSelectedNode={() => {
                            if (!this.state.alertModal.show) {
                              this.setState({ selectedNode: node });
                            }
                          }}
                          onRemove={this.removeNode}
                        />
                      ))}
                      {false && !backendCall.plugin_backend_call_delegates && (
                        <div style={{ height: 10 }}></div>
                      )}
                    </>
                  )}
                  <BackendNode
                    backend={backend}
                    selectedNode={selectedNode}
                    setSelectedNode={() => {
                      if (!this.state.alertModal.show) this.setState({ selectedNode: backend });
                    }}
                    onRemove={this.removeNode}
                  />
                </Flow>
                <FormContainer
                  showAdvancedDesignerView={(pluginName) =>
                    this.setState({ advancedDesignerView: pluginName })
                  }
                  nodes={[...this.state.nodes, this.state.backend, this.state.frontend]}
                  serviceMode={serviceMode}
                  clearPlugins={this.clearPlugins}
                  deleteRoute={this.deleteRoute}
                  updateRoute={this.updateRoute}
                  saveRoute={this.saveRoute}
                  selectedNode={selectedNode}
                  route={route}
                  setRoute={(n) => this.setState({ route: n })}
                  setSelectedNode={(n) => {
                    if (!this.state.alertModal.show) this.setState({ selectedNode: n });
                  }}
                  updatePlugin={this.updatePlugin}
                  onRemove={this.removeNode}
                  plugins={plugins}
                  backends={backends}
                  preview={preview}
                  showPreview={(element) =>
                    this.setState({
                      preview: {
                        ...this.state.preview,
                        element,
                      },
                    })
                  }
                  originalRoute={originalRoute}
                  alertModal={alertModal}
                  disabledSaveButton={isEqual(route, originalRoute)}
                />
              </div>
            )}
          </div>
        </Container>
      </Loader>
    );
  }
}

const Element = ({ element, addNode, showPreview, hidePreview }) => (
  <div
    className="element"
    onClick={(e) => {
      e.stopPropagation();
      showPreview(element);
    }}
  >
    <div className="d-flex-between element-text">
      <div>
        {element.legacy ? (
          <span className="badge bg-info text-dark" style={{ marginRight: 5 }}>
            legacy
          </span>
        ) : (
          ''
        )}
        {element.name.charAt(0).toUpperCase() + element.name.slice(1)}
      </div>
      <i
        className={`fas fa-${element.plugin_multi_inst ? 'plus' : 'arrow-right'} element-arrow`}
        onClick={(e) => {
          e.stopPropagation();
          if (element.shortcut) {
            element.shortcut();
          } else {
            hidePreview();
            addNode(element);
          }
        }}
      />
    </div>
  </div>
);

const Group = ({ group, elements, addNode, ...props }) => {
  const [open, setOpen] = useState(props.forceOpen);

  useEffect(() => {
    setOpen(props.forceOpen);
  }, [props.forceOpen]);

  useEffect(() => {
    setOpen(props.expandAll);
  }, [props.expandAll]);

  return (
    <div className="group">
      <div
        className="search-group-header"
        style={{ cursor: 'pointer' }}
        onClick={(e) => {
          e.stopPropagation();
          setOpen(!open);
        }}
      >
        <i
          className={`fas fa-chevron-${open ? 'down' : 'right'} ms-3`}
          size={16}
          onClick={(e) => {
            e.stopPropagation();
            setOpen(!open);
          }}
        />
        <span style={{ padding: '10px' }}>{group.charAt(0).toUpperCase() + group.slice(1)}</span>
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
          width: '100%',
          display: 'flex',
          alignItems: 'center',
        }}
      >
        <input
          type="text"
          className="form-control"
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

const UnselectedNode = ({
  hideText,
  route,
  clearPlugins,
  deleteRoute,
  selectFrontend,
  selectBackend,
}) => {
  if (route && route.frontend && route.backend && !hideText) {
    const frontend = route.frontend;
    const backend = route.backend;

    const rawMethods = (frontend.methods || []).filter((m) => m.length);

    const allMethods =
      rawMethods && rawMethods.length > 0
        ? rawMethods.map((m, i) => (
            <span
              key={`frontendmethod-${i}`}
              className={`badge me-1`}
              style={{ backgroundColor: HTTP_COLORS[m] }}
            >
              {m}
            </span>
          ))
        : [<span className="badge bg-success">ALL</span>];

    return (
      <>
        <div className="d-flex-center justify-content-start dark-background py-2 ps-2">
          <span style={{ fontStyle: 'italic' }}> Start by selecting a</span>
          <Dot style={{ width: 'initial' }} className="mx-1">
            <div className="flex-column p-1">
              <span style={{ color: 'var(--color_level2)' }}>plugin</span>
            </div>
          </Dot>
          <span>to configure it</span>
          <button
            type="button"
            className="btn btn-sm btn-danger d-flex align-items-center justify-content-center ms-auto me-2"
            onClick={clearPlugins}
          >
            Remove plugins
          </button>
        </div>
        <div style={{ marginTop: 20 }}>
          <h3 style={{ fontSize: '1.25rem' }}>Frontend</h3>
          <span>this route is exposed on</span>
          <div
            className="dark-background"
            onDoubleClick={selectFrontend}
            onClick={selectFrontend}
            style={{
              display: 'flex',
              flexDirection: 'column',
              marginBottom: 10,
              marginTop: 10,
              paddingTop: 10,
              paddingBottom: 10,
              borderRadius: 3,
            }}
          >
            {frontend.domains.map((domain) => {
              const exact = frontend.exact;
              const end = exact ? '' : domain.indexOf('/') < 0 ? '/*' : '*';
              const start = 'http://';
              return allMethods.map((method, i) => {
                return (
                  <div
                    style={{
                      paddingLeft: 10,
                      paddingRight: 10,
                      display: 'flex',
                      flexDirection: 'row',
                    }}
                    key={`allmethods-${i}`}
                  >
                    <div style={{ width: 80 }}>{method}</div>
                    <span style={{ fontFamily: 'monospace' }}>
                      {start}
                      {domain}
                      {end}
                    </span>
                  </div>
                );
              });
            })}
          </div>
          {frontend.query && Object.keys(frontend.query).length > 0 && (
            <div className="">
              <span>this route will match only if the following query params are present</span>
              <pre
                className="dark-background"
                onDoubleClick={selectFrontend}
                style={{
                  padding: 10,
                  marginTop: 10,
                  backgroundColor: '#555',
                  fontFamily: 'monospace',
                  borderRadius: 3,
                }}
              >
                <code>
                  {Object.keys(frontend.query)
                    .map((key) => `${key}: ${frontend.query[key]}`)
                    .join('\n')}
                </code>
              </pre>
            </div>
          )}
          {frontend.headers && Object.keys(frontend.headers).length > 0 && (
            <div className="">
              <span>this route will match only if the following headers are present</span>
              <pre
                onDoubleClick={selectFrontend}
                style={{
                  padding: 10,
                  marginTop: 10,
                  backgroundColor: '#555',
                  fontFamily: 'monospace',
                  borderRadius: 3,
                }}
              >
                <code>
                  {Object.keys(frontend.headers)
                    .map((key) => `${key}: ${frontend.headers[key]}`)
                    .join('\n')}
                </code>
              </pre>
            </div>
          )}
        </div>
        <div style={{ marginTop: 20 }}>
          <h3 style={{ fontSize: '1.25rem' }}>Backend</h3>
          <span>this route will forward requests to</span>
          <div
            className="dark-background"
            onDoubleClick={selectBackend}
            style={{
              display: 'flex',
              flexDirection: 'column',
              marginBottom: 10,
              marginTop: 10,
              paddingTop: 10,
              paddingBottom: 10,
              borderRadius: 3,
            }}
          >
            {backend.targets
              .filter((f) => f)
              .map((target, i) => {
                const path = backend.root;
                const rewrite = backend.rewrite;
                const hostname = target.ip_address
                  ? `${target.hostname}@${target.ip_address}`
                  : target.hostname;
                const end = rewrite || frontend.strip_path ? path : `/<request_path>${path}`;
                const start = target.tls ? 'https://' : 'http://';
                const mtls =
                  target.tls_config &&
                  target.tls_config.enabled &&
                  [...(target.tls_config.certs || []), ...(target.tls_config.trusted_certs || [])]
                    .length > 0 ? (
                    <span className="badge bg-warning text-dark" style={{ marginRight: 10 }}>
                      mTLS
                    </span>
                  ) : (
                    <span></span>
                  );
                return (
                  <div
                    style={{
                      paddingLeft: 10,
                      paddingRight: 10,
                      display: 'flex',
                      flexDirection: 'row',
                    }}
                    key={`backend-targets${i}`}
                  >
                    <span style={{ fontFamily: 'monospace' }}>
                      {mtls}
                      {start}
                      {hostname}:{target.port}
                      {end}
                    </span>
                  </div>
                );
              })}
          </div>
        </div>
      </>
    );
  } else {
    return null;
  }
};

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

const EditViewHeader = ({ icon, name, id, onCloseForm }) => (
  <div className="group-header d-flex-between editor-view-informations">
    <div className="d-flex-between">
      <i
        className={`fas fa-${
          icon || 'bars'
        } group-icon designer-group-header-icon editor-view-icon`}
      />
      <span className="editor-view-text">{name || id}</span>
    </div>
    <div className="d-flex me-1">
      <button
        className="btn btn-sm"
        type="button"
        style={{ minWidth: '36px', color: 'var(--text)' }}
        onClick={onCloseForm}
      >
        <i className="fas fa-times" />
      </button>
    </div>
  </div>
);

const EditViewFormatActions = ({ asJsonFormat, errors, onFormClick, onRawJsonClick }) => (
  <div className="d-flex justify-content-end mb-2 me-2 dark-background">
    <PillButton
      className="mt-3"
      rightEnabled={!asJsonFormat}
      leftText="FORM"
      rightText="RAW JSON"
      onLeftClick={onFormClick}
      onRightClick={onRawJsonClick}
    />
    {/* <Button
      type='dark'
      className="btn-sm mt-3"
      disabled={errors && errors.length > 0}
      onClick={onFormClick}
      style={{ backgroundColor: asJsonFormat ? '#373735' : "var(--color-primary)" }}>
      FORM
    </Button>
    <Button
      type='dark'
      className="btn-sm mx-1 mt-3"
      onClick={onRawJsonClick}
      style={{ backgroundColor: asJsonFormat ? "var(--color-primary)" : '#373735' }}>
      RAW JSON
    </Button> */}
  </div>
);

const EditViewJsonEditor = ({ readOnly, value, onChange, errors }) => (
  <>
    {value && value.toString().length > 0 && (
      <Suspense fallback={<div>Loading ...</div>}>
        <CodeInput
          mode="json"
          editorOnly={true}
          themeStyle={{
            maxHeight: readOnly ? '300px' : '-1',
            minHeight: '100px',
            width: '100%',
          }}
          value={value}
          onChange={(e) => {
            if (!readOnly) onChange(e);
          }}
        />
      </Suspense>
    )}
    {errors && (
      <div>
        {(errors || []).map((error, idx) => (
          <div
            className="mt-3 ps-3"
            style={{ borderLeft: '2px solid #D5443F' }}
            key={`errror${idx}`}
          >
            {error}
          </div>
        ))}
      </div>
    )}
  </>
);

const EditViewReadOnlyActions = ({ onCancel, onOk }) => (
  <div className="d-flex justify-content-end mt-3">
    <button className="btn btn-sm btn-danger me-1" onClick={onCancel}>
      Cancel
    </button>
    <button className="btn btn-sm btn-save" onClick={onOk}>
      Add to flow
    </button>
  </div>
);

class EditView extends React.Component {
  state = {
    usingExistingBackend: this.props.route.backend_ref,
    asJsonFormat: this.props.selectedNode.legacy || this.props.readOnly,
    form: {
      schema: {},
      flow: [],
      value: undefined,
    },
    offset: 0,
    errors: [],
  };

  formRef = React.createRef();

  componentDidMount() {
    this.manageScrolling();
    this.loadForm();
  }

  componentDidUpdate(prevProps) {
    if (this.props.selectedNode.id !== prevProps.selectedNode.id) this.loadForm();
  }

  manageScrolling = () => {
    window.removeEventListener('scroll', this.onScroll);
    window.addEventListener('scroll', this.onScroll, { passive: true });

    this.onScroll();
  };

  onScroll = () => {
    this.setState({
      offset: window.pageYOffset,
    });
  };

  componentWillUnmount() {
    window.removeEventListener('scroll', this.onScroll);
  }

  loadForm = () => {
    const { selectedNode, plugins, route, readOnly } = this.props;

    const { id, flow, config_flow, schema, nodeId } = selectedNode;
    let { config_schema } = selectedNode;

    const isFrontendOrBackend = ['Backend', 'Frontend'].includes(id);
    const isPluginWithConfiguration = Object.keys(config_schema).length > 0;

    let formSchema = schema || config_schema;
    let formFlow = [
      isFrontendOrBackend ? undefined : 'status',
      isPluginWithConfiguration ? 'plugin' : undefined,
    ].filter((f) => f);

    if (config_schema) {
      formSchema = {
        status: {
          type: 'form',
          collapsable: isPluginWithConfiguration ? true : false,
          collapsed: false,
          label: 'Informations',
          schema: PLUGIN_INFORMATIONS_SCHEMA,
        },
      };
      if (isPluginWithConfiguration)
        formSchema = {
          ...formSchema,
          plugin: {
            type: 'form',
            label: 'Plugin configuration',
            schema: { ...convertTransformer(config_schema) },
            flow: Array.isArray(config_flow) ? [...(config_flow || flow)] : config_flow,
          },
        };
    }

    let value = route[selectedNode.field]; // matching Frontend and Backend case

    if (!value) {
      const node =
        route.plugins.find((p) => p.nodeId === nodeId) || plugins.find((p) => p.id === id);
      if (node)
        value = {
          plugin: node.config,
          status: {
            enabled: node.enabled !== undefined ? node.enabled : true,
            debug: node.debug !== undefined ? node.debug : false,
            include: node.include !== undefined ? node.include : [],
            exclude: node.exclude !== undefined ? node.exclude : [],
          },
        };
    } else {
      value = {
        plugin: { ...value },
      };
    }

    this.setState({
      form: {
        schema: formSchema,
        flow: formFlow,
        value,
        originalValue: value,
      },
      asJsonFormat: selectedNode.legacy || readOnly,
    });
  };

  onValidate = (newValue) => {
    const { selectedNode } = this.props;
    const { nodeId } = selectedNode;

    this.setState({
      form: {
        ...this.state.form,
        value: {
          plugin: newValue.plugin,
          status: newValue.status,
        },
      },
    });

    const oldConfig = this.state.form.value;

    return this.props.updatePlugin(nodeId, selectedNode.id, {
      plugin: newValue.plugin || oldConfig?.plugin,
      status: newValue.status,
    });
  };

  onJsonInputChange = (value) => {
    const { form } = this.state;
    this.onValidate(JSON.parse(value));
    // validate([], form.schema, value)
    //   .then(() => {
    //     this.setState({
    //       errors: [],
    //     });
    //     this.onValidate(JSON.parse(value));
    //   })
    //   .catch((err) => {
    //     if (err.inner && Array.isArray(err.inner)) {
    //       this.setState({
    //         errors: err.inner.map((r) => r.message),
    //       });
    //     }
    //   });
  };

  toggleJsonFormat = (value) => {
    this.setState({
      asJsonFormat: value,
    });
  };

  render() {
    const {
      selectedNode,
      setSelectedNode,
      route,
      setRoute,
      onRemove,
      backends,
      readOnly,
      addNode,
      hidePreview,
      disabledSaveButton,
      saveRoute,
    } = this.props;

    const { id, name, icon } = selectedNode;
    const { usingExistingBackend, form, offset, asJsonFormat, errors } = this.state;

    const showActions = !selectedNode.legacy && !readOnly && !usingExistingBackend; // && 'Backend' !== id;
    const notOnBackendNode = !usingExistingBackend || id !== 'Backend';

    if (form.flow.length === 0 && Object.keys(form.schema).length === 0) return null;

    const EurekaForm =
      id === 'cp:otoroshi.next.plugins.EurekaTarget' ? EurekaTargetForm : ExternalEurekaTargetForm;
    const hasCustomPluginForm = [
      'cp:otoroshi.next.plugins.EurekaTarget',
      'cp:otoroshi.next.plugins.ExternalEurekaTarget',
    ].includes(id);

    return (
      <div
        id="form"
        onClick={(e) => e.stopPropagation()}
        className="plugins-stack editor-view"
        style={{ top: offset, left: 0 }}
      >
        <EditViewHeader
          icon={icon}
          name={name}
          id={id}
          onCloseForm={() => {
            setSelectedNode(undefined);
            hidePreview();
          }}
        />
        <div className="dark-background">
          {selectedNode.description && (
            <Description
              text={selectedNode.description}
              legacy={selectedNode.legacy}
              steps={selectedNode.plugin_steps || []}
            />
          )}
          {showActions && (
            <EditViewFormatActions
              asJsonFormat={asJsonFormat}
              errors={errors}
              onFormClick={() => this.toggleJsonFormat(false)}
              onRawJsonClick={() => {
                if (
                  this.formRef.current &&
                  this.formRef.current.isValid(this.formRef.current.state.validation)
                ) {
                  this.toggleJsonFormat(true);
                } else this.toggleJsonFormat(true);
              }}
            />
          )}
          <BackendSelector
            enabled={id === 'Backend'}
            backends={backends}
            setUsingExistingBackend={(e) => {
              this.setState({
                usingExistingBackend: e,
              });
            }}
            setRoute={setRoute}
            usingExistingBackend={usingExistingBackend}
            route={route}
          />
          {notOnBackendNode && (
            <div className="editor-view-form">
              {asJsonFormat && (
                <>
                  <EditViewJsonEditor
                    readOnly={readOnly}
                    value={form.value}
                    onChange={this.onJsonInputChange}
                    errors={errors}
                  />
                  {readOnly ? (
                    <EditViewReadOnlyActions
                      onCancel={() => {
                        setSelectedNode(undefined);
                        hidePreview();
                      }}
                      onOk={() => {
                        hidePreview();
                        if (selectedNode.shortcut) selectedNode.shortcut(selectedNode);
                        else addNode(selectedNode);
                      }}
                    />
                  ) : (
                    <Actions
                      disabledSaveButton={disabledSaveButton}
                      valid={saveRoute}
                      selectedNode={selectedNode}
                      onRemove={onRemove}
                    />
                  )}
                </>
              )}
              {!asJsonFormat && (
                <>
                  <NgForm
                    ref={this.formRef}
                    value={form.value}
                    schema={form.schema}
                    flow={hasCustomPluginForm ? ['status'] : form.flow}
                    onChange={this.onValidate}
                    useBreadcrumb={true}
                  />
                  {!['Frontend', 'Backend'].includes(id) && (
                    <div className="d-flex">
                      <button className="btn btn-sm btn-danger ms-auto mt-3" onClick={onRemove}>
                        <i className="fas fa-times me-2" />
                        Remove plugin
                      </button>
                    </div>
                  )}
                  {hasCustomPluginForm && (
                    <EurekaForm
                      route={route}
                      update={(plugin) => {
                        const { selectedNode } = this.props;
                        const { nodeId } = selectedNode;

                        return this.props.updatePlugin(nodeId, selectedNode.id, {
                          plugin,
                          status: form.schema.status,
                        });
                      }}
                    />
                  )}
                </>
              )}
            </div>
          )}
          {!notOnBackendNode && (
            <div className="d-flex justify-content-end p-3">
              <FeedbackButton
                text="Save"
                icon={() => <i className="fas fa-paper-plane" />}
                onPress={saveRoute}
              />
              {route.backend_ref && (
                <Link className="btn btn-primary ms-2" to={`/backends/edit/${route.backend_ref}/`}>
                  <i className="fas fa-microchip me-1" />
                  Edit this backend
                </Link>
              )}
            </div>
          )}
        </div>
      </div>
    );
  }
}

const Actions = ({ selectedNode, onRemove, valid, disabledSaveButton }) => (
  <div className="d-flex mt-4 justify-content-end">
    {!['Frontend', 'Backend'].includes(selectedNode.id) && <RemoveComponent onRemove={onRemove} />}
    <FeedbackButton
      text="Save"
      className="ms-2"
      // disabled={disabledSaveButton}
      icon={() => <i className="far fa-paper-plane" />}
      onPress={valid}
    />
  </div>
);

const BackendSelector = ({
  enabled,
  setUsingExistingBackend,
  setRoute,
  usingExistingBackend,
  route,
  backends,
}) => {
  return (
    enabled && (
      <div className="dark-background backend-selector">
        <PillButton
          pillButtonStyle={{ width: 'auto', flex: 1 }}
          style={{ display: 'flex', width: '100%' }}
          rightEnabled={usingExistingBackend}
          leftText="Select an existing backend"
          rightText="Create a new backend"
          onChange={setUsingExistingBackend}
        />
        {usingExistingBackend && (
          <div className="mt-3">
            <NgSelectRenderer
              id="backend_select"
              value={route.backend_ref}
              placeholder="Select an existing backend"
              label={' '}
              ngOptions={{
                spread: true,
              }}
              onChange={(backend_ref) =>
                setRoute({
                  ...route,
                  backend_ref,
                })
              }
              options={backends}
              optionsTransformer={(arr) =>
                arr.map((item) => ({ label: item.name, value: item.id }))
              }
            />
          </div>
        )}
      </div>
    )
  );
};

export const Description = ({ text, steps, legacy }) => {
  const [showMore, setShowMore] = useState(false);

  const textLength = text ? text.length : 0;
  const overflows = textLength > 300;
  let content = text ? (overflows && !showMore ? text.slice(0, 300) + ' ...' : text) : '...';

  if (content.split('```').length % 2 === 0) {
    content = content + '\n```\n';
  }

  return (
    <>
      {content && content.toLowerCase() !== '...' && (
        <MarkdownInput
          className="form-description"
          readOnly={true}
          preview={true}
          value={content}
        />
      )}
      {steps.length > 0 && (
        <div className="steps" style={{ paddingLeft: 12 }}>
          active on{' '}
          {steps.map((step, i) => (
            <span
              className="badge bg-warning text-dark"
              style={{ marginLeft: 5 }}
              key={`steps-${i}`}
            >
              {step}
            </span>
          ))}
        </div>
      )}
      {legacy && (
        <div className="steps" style={{ paddingBottom: 10, paddingLeft: 12 }}>
          this plugin is a{' '}
          <span className="badge bg-info text-dark" style={{ marginLeft: 5 }}>
            legacy plugin
          </span>
        </div>
      )}
      {overflows && (
        <button
          type="button"
          className="btn btn-sm btn-success me-3 mb-3"
          onClick={() => {
            setShowMore(!showMore);
          }}
          style={{ marginLeft: 'auto', display: 'block' }}
        >
          {showMore ? 'Show less' : 'Show more description'}
        </button>
      )}
    </>
  );
};

const RemoveComponent = ({ onRemove }) => (
  <button className="btn btn-sm btn-danger" type="button" onClick={onRemove}>
    <i className="fas fa-trash me-2"></i>
    Remove this plugin
  </button>
);
