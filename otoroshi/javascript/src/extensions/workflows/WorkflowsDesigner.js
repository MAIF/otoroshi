import React, { useCallback, useEffect, useState } from 'react';
import { Flow } from './Flow';
import { Navbar } from './Navbar';
import { NodesExplorer } from './NodesExplorer';
import { v4 as uuid } from 'uuid';

import {
  addEdge,
  useReactFlow,
  useUpdateNodeInternals,
  useNodesState,
  useEdgesState,
} from '@xyflow/react';
import { NewTask } from './flow/NewTask';
import { nodesCatalogSignal } from './models/Functions';

import { applyLayout } from './ElkOptions';
import { TagsModal } from './TagsModal';
import { useSignalValue } from 'signals-react-safe';
import debounce from 'lodash/debounce';
import Terminal from './Terminal';
import { useSuppressResizeObserverError } from './useSuppressionResizeObserverError';

export const INFORMATION_FIELDS = [
  'description',
  'kind',
  'enabled',
  'result',
  'name',
  'breakpoint',
];

export function splitInformationAndContent(obj) {
  return Object.entries(obj).reduce(
    (acc, [key, value]) => {
      if (INFORMATION_FIELDS.includes(key)) {
        acc.information[key] = value;
      } else {
        acc.content[key] = value;
      }
      return acc;
    },
    {
      information: {},
      content: {},
    }
  );
}

const emptyWorkflow = {
  kind: 'workflow',
  steps: [],
};

const LOCAL_STORAGE_TERMINAL_KEY = 'io.otoroshi.next.workflow.designer.terminal_size';
const LOCAL_STORAGE_TERMINAL_TAB_KEY = 'io.otoroshi.next.workflow.designer.terminal_tab';

export function WorkflowsDesigner(props) {
  const updateNodeInternals = useUpdateNodeInternals();
  const { screenToFlowPosition, setCenter, ...reactFlow } = useReactFlow();

  const [activeNode, setActiveNode] = useState(false);
  const [showTagModal, openTagModal] = useState(false);

  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);

  const [terminalSize, _changeTerminalSize] = useState(0);
  const [resizingTerminal, toggleResizingTerminal] = useState(false);
  const [initialTerminalTab, setInitialTerminalTab] = useState();

  const [workflow, setWorkflow] = useState(props.workflow);

  const catalog = useSignalValue(nodesCatalogSignal);

  useSuppressResizeObserverError();

  const saveTerminalSize = debounce((newSize) => {
    localStorage.setItem(LOCAL_STORAGE_TERMINAL_KEY, newSize);
  }, 200);

  const saveTerminalTab = (newTab) => localStorage.setItem(LOCAL_STORAGE_TERMINAL_TAB_KEY, newTab);

  const changeTerminalSize = (newSize) => {
    _changeTerminalSize(newSize);
    saveTerminalSize(newSize);
  };

  const loadTerminalSettings = () => {
    const savedSize = localStorage.getItem(LOCAL_STORAGE_TERMINAL_KEY);

    if (savedSize) _changeTerminalSize(Number(savedSize));

    const savedTab = localStorage.getItem(LOCAL_STORAGE_TERMINAL_TAB_KEY);

    if (savedTab) setInitialTerminalTab(savedTab);
  };

  function createNodeFromUI(node) {
    // console.log('createNodeFromUI', node);

    const { information } = splitInformationAndContent(node);

    const data = catalog.nodes[(information.kind || information.name).toLowerCase()];

    let functionData = {};
    if (node.category === 'functions') {
      functionData = {
        function: node.name,
      };
    } else if (node.category === 'udfs') {
      functionData = {
        function: `self.${node.name}`,
      };
    }

    return {
      id: uuid(),
      type: data.type || 'simple',
      position: node.position || { x: 0, y: 0 },
      data: {
        ...data,
        information: {
          ...information,
          description: information.description || data.description,
        },
        content: {
          ...functionData,
        },
      },
    };
  }

  function createNode(id, child, addInformationsToNode) {
    // console.log('createNode', id, child)
    const { information, content } = splitInformationAndContent(child);

    const template = catalog.nodes[(information.kind || information.name).toLowerCase()];

    const node = {
      id,
      position: child.position || { x: 0, y: 0 },
      type: template?.type || 'simple',
      data: {
        ...template,
        display_name: child.customDisplayName ? child.customDisplayName : template.display_name,
        information,
        content,
      },
    };

    const newNode = addInformationsToNode(node);

    return {
      ...newNode,
      id,
      data: {
        sources: ['output'],
        ...newNode.data,
        targetHandles: [],
        sourceHandles: [],
      },
    };
  }

  const buildGraph = (workflows, addInformationsToNode, targetId, handleId) => {
    // console.log('buildGraph', workflows, targetId, handleId)

    if (workflows.filter((f) => f).length === 0) {
      return { edges: [], nodes: [] };
    }

    const [workflow, ...rest] = workflows;

    if (!workflow || Object.keys(workflow).length === 0) return { edges: [], nodes: [] };

    let edges = [];
    let nodes = [];

    let useCurrent = workflow.kind !== 'workflow';

    const me = workflow.id ? workflow.id : uuid();
    let current = useCurrent ? createNode(me, workflow, addInformationsToNode) : undefined;

    if (useCurrent) nodes.push(current);

    if (workflow.kind === 'workflow') {
      if (workflow.returned) {
        let returnedNode = createNode(
          `${me}-returned-node`,
          {
            returned: {
              ...(workflow.returned || {}),
            },
            kind: 'returned',
          },
          addInformationsToNode
        );

        returnedNode = {
          ...returnedNode,
          data: {
            ...returnedNode.data,
            targetHandles: [{ id: `input-${returnedNode.id}` }],
            sourceHandles: [],
          },
        };

        const child = buildGraph(
          workflow.steps.slice().reverse(),
          addInformationsToNode,
          returnedNode.id
        );
        nodes = [...child.nodes, returnedNode];

        edges = edges.concat(child.edges);

        if (targetId && handleId)
          edges.push({
            id: `${targetId}-returned-node`,
            source: returnedNode.id,
            sourceHandle: `output-${returnedNode.id}`,
            target: targetId,
            targetHandle: handleId,
            type: 'customEdge',
            animated: true,
          });
      } else {
        const child = buildGraph(
          workflow.steps.slice().reverse(),
          addInformationsToNode,
          targetId,
          handleId
        );
        nodes = [...child.nodes];
        edges = edges.concat(child.edges);
      }
    } else if (workflow.kind === 'jump') {
      if (workflow.to)
        edges.push({
          id: `${me}-jump`,
          source: me,
          sourceHandle: `to-${me}`,
          target: workflow.to,
          targetHandle: `input-${workflow.to}`,
          type: 'customEdge',
          animated: true,
        });
    } else if (workflow.kind === 'if') {
      const thensubGraph = workflow.then
        ? buildGraph([workflow.then], addInformationsToNode, targetId, handleId)
        : undefined;
      const elseGraph = workflow.else
        ? buildGraph([workflow.else], addInformationsToNode, targetId, handleId)
        : undefined;

      const hasThenSubGraph = thensubGraph && thensubGraph.nodes.length > 0;
      const hasElseGraph = elseGraph && elseGraph.nodes.length > 0;

      if (hasThenSubGraph) {
        nodes = nodes.concat(thensubGraph.nodes);
        edges = edges.concat(thensubGraph.edges);
      }

      if (hasElseGraph) {
        nodes = nodes.concat(elseGraph.nodes);
        edges = edges.concat(elseGraph.edges);
      }

      if (hasThenSubGraph)
        edges.push({
          id: `${me}-then`,
          source: me,
          sourceHandle: `then-${me}`,
          target: thensubGraph.nodes[0].id,
          targetHandle: `input-${thensubGraph.nodes[0].id}`,
          type: 'customEdge',
          animated: true,
        });
      else if (targetId)
        edges.push({
          id: `${me}-then`,
          source: me,
          sourceHandle: `then-${me}`,
          target: targetId,
          targetHandle: handleId || `input-${targetId}`,
          type: 'customEdge',
          animated: true,
        });

      if (hasElseGraph)
        edges.push({
          id: `${me}-else`,
          source: me,
          sourceHandle: `else-${me}`,
          target: elseGraph.nodes[0].id,
          targetHandle: `input-${elseGraph.nodes[0].id}`,
          type: 'customEdge',
          animated: true,
        });
      else if (targetId) {
        edges.push({
          id: `${me}-else`,
          source: me,
          sourceHandle: `else-${me}`,
          target: targetId,
          targetHandle: handleId || `input-${targetId}`,
          type: 'customEdge',
          animated: true,
        });
      }
    } else if (
      workflow.kind === 'foreach' ||
      workflow.kind === 'flatmap' ||
      workflow.kind === 'map' ||
      workflow.kind === 'async' ||
      workflow.kind === 'while'
    ) {
      if (workflow.node) {
        const subGraph = buildGraph([workflow.node], addInformationsToNode);

        if (subGraph.nodes.length > 0) {
          nodes = nodes.concat(subGraph.nodes);
          edges = edges.concat(subGraph.edges);

          const handle = current.data.sources[0];

          edges.push({
            id: `${me}-${handle}`,
            source: me,
            sourceHandle: `${handle}-${me}`,
            target: subGraph.nodes[0].id,
            targetHandle: `input-${subGraph.nodes[0].id}`,
            type: 'customEdge',
            animated: true,
          });
        }
      }
    } else if (workflow.kind === 'try') {
      function loadSubNode(field, handle) {
        if (workflow[field]) {
          const subGraph = buildGraph([workflow[field]], addInformationsToNode, targetId);

          if (subGraph.nodes.length > 0) {
            nodes = nodes.concat(subGraph.nodes);
            edges = edges.concat(subGraph.edges);

            const handleName = handle ? handle : field;

            edges.push({
              id: `${me}-${handleName}`,
              source: me,
              sourceHandle: `${handleName}-${me}`,
              target: subGraph.nodes[0].id,
              targetHandle: `input-${subGraph.nodes[0].id}`,
              type: 'customEdge',
              animated: true,
            });
          }
        }
      }

      loadSubNode('node', 'try');
      loadSubNode('catch');
      loadSubNode('finally');
    } else if (workflow.kind === 'switch' || workflow.kind === 'parallel') {
      let paths = [];

      if (workflow.paths) {
        for (let i = 0; i < workflow.paths.length; i++) {
          const subflow = workflow.paths[i].node;

          if (subflow) {
            const nestedPath = buildGraph([subflow], addInformationsToNode, targetId, handleId);

            paths.push({
              idx: i,
              nestedPath,
            });
          }
        }

        current.customSourceHandles = [...Array(workflow.paths.length)].map((_, i) => ({
          id: `path-${i}`,
        }));

        paths.forEach((path) => {
          if (path.nestedPath.nodes.length > 0)
            edges.push({
              id: `${me}-path-${path.idx}`,
              source: me,
              sourceHandle: `path-${path.idx}`,
              target: path.nestedPath.nodes[0].id,
              targetHandle: `input-${path.nestedPath.nodes[0].id}`,
              type: 'customEdge',
              animated: true,
            });

          nodes = nodes.concat(path.nestedPath.nodes);
          edges = edges.concat(path.nestedPath.edges);
        });
      }
    } else if (workflow.kind === 'filter') {
      let predicate = workflow.predicate;

      if (predicate) {
        if (
          typeof workflow.predicate === 'object' &&
          workflow.predicate !== null &&
          Object.keys(workflow.predicate).find((key) => key.startsWith('$'))
        ) {
          const kind = Object.keys(workflow.predicate).find((key) => key.startsWith('$'));
          predicate = {
            kind,
            ...workflow.predicate[kind],
          };
        }

        predicate = buildGraph([predicate], addInformationsToNode);

        nodes = nodes.concat(predicate.nodes);
        edges = edges.concat(predicate.edges);

        edges.push({
          id: `${me}-predicate`,
          source: predicate.nodes[predicate.nodes.length - 1].id,
          sourceHandle: `output-${predicate.nodes[predicate.nodes.length - 1].id}`,
          target: me,
          targetHandle: `predicate-${me}`,
          type: 'customEdge',
          animated: true,
        });
      }
    } else if (nodesCatalogSignal.value.extensionOverloads[workflow.kind] && nodesCatalogSignal.value.extensionOverloads[workflow.kind].buildGraph) {
      const subGraph = nodesCatalogSignal.value.extensionOverloads[workflow.kind].buildGraph({
        workflow,
        addInformationsToNode,
        targetId,
        handleId,
        buildGraph,
        current,
        me,
      });

      nodes = nodes.concat(subGraph?.nodes || []);
      edges = edges.concat(subGraph?.edges || []);

      if (workflow?.paths) useCurrent = false;
    }

    for (let i = 0; i < nodes.length; i++) {
      nodes[i] = setupTargetsAndSources(nodes[i]);
    }

    if (targetId && useCurrent) {
      // nodes except parallel and switch
      if (current.data.sources.includes('output')) {
        edges.push({
          id: `${me}-${targetId}`,
          source: me,
          sourceHandle: `${handleId ? handleId : 'output'}-${me}`,
          target: targetId,
          targetHandle: `input-${targetId}`,
          type: 'customEdge',
          animated: true,
        });
      } else {
        (current.data.content.paths || []).forEach((path, idx) => {
          if (!path.node) {
            edges.push({
              id: `${me}-${targetId}`,
              source: me,
              sourceHandle: `path-${idx}`,
              target: targetId,
              targetHandle: `input-${targetId}`,
              type: 'customEdge',
              animated: true,
            });
          }
        });
      }
    }

    if (useCurrent) {
      const subGraph = buildGraph(rest, addInformationsToNode, me);

      return {
        edges: [...subGraph.edges, ...edges],
        nodes: [...subGraph.nodes, ...nodes],
      };
    }

    return { nodes, edges };
  };

  const setupTargetsAndSources = (node) => {
    const { targets = [], sources = [] } = node.data;

    node = {
      ...node,
      data: {
        ...node.data,
        targetHandles: [...targets, 'input'].map((target) => {
          return { id: `${target}-${node.id}` };
        }),
        sourceHandles: [
          ...(node.customSourceHandles || []),
          ...sources.map((source) => ({ id: `${source}-${node.id}` })),
        ],
      },
    };

    // delete node.customSourceHandles

    return node;
  };

  const initializeGraph = (config, orphans, notes, addInformationsToNode) => {
    let startingNode = createNode(
      'start',
      {
        kind: 'start',
        description: config.description,
        position: config.position || { x: 0, y: 0 },
        breakpoint: config.breakpoint || false,
      },
      addInformationsToNode
    );

    startingNode = {
      ...startingNode,
      data: {
        ...startingNode.data,
        targetHandles: [],
        sourceHandles: [{ id: `output-${startingNode.id}` }],
      },
    };

    let returnedNode = createNode(
      'returned-node',
      {
        returned: config.returned,
        description: config?.returnedInformation?.description,
        position: config?.returnedInformation?.position,
        kind: 'returned',
      },
      addInformationsToNode
    );

    returnedNode = {
      ...returnedNode,
      position: config.returnedInformation?.position || returnedNode.position,
      data: {
        ...returnedNode.data,
        targetHandles: [{ id: `input-returned-node` }],
        sourceHandles: [],
      },
    };

    const subGraph = buildGraph(
      config.steps.slice().reverse(),
      addInformationsToNode,
      returnedNode.id
    );

    let startingEdge = {
      id: 'start-edge',
      source: startingNode.id,
      sourceHandle: `output-${startingNode.id}`,
      target: returnedNode.id,
      targetHandle: `input-${returnedNode.id}`,
      type: 'customEdge',
      animated: true,
    };

    if (subGraph.nodes.length > 0) {
      startingEdge = {
        id: 'start-edge',
        source: startingNode.id,
        sourceHandle: `output-${startingNode.id}`,
        target: subGraph.nodes[0].id,
        targetHandle: `input-${subGraph.nodes[0].id}`,
        type: 'customEdge',
        animated: true,
      };
    }

    let notesNodes = (notes || []).map((note) => {
      const measured = note.measured || { width: 200, height: 200 };
      const node = createNode(note.id, note, addInformationsToNode);
      return {
        ...node,
        position: note.position,
        style: measured,
      };
    });

    const orphansNodes = orphans.nodes
      .filter((f) => f.kind)
      .map((orphan) => {
        const node = createNode(orphan.id, orphan, addInformationsToNode);
        return {
          ...setupTargetsAndSources(node),
          position: orphan.position,
        };
      });

    return {
      nodes: [startingNode, ...subGraph.nodes, ...orphansNodes, returnedNode, ...notesNodes],
      edges: [...subGraph.edges, ...orphans.edges, startingEdge],
    };
  };

  useEffect(() => {
    const initialState = initializeGraph(
      workflow?.config,
      workflow.orphans,
      workflow.notes,
      addInformationsToNode
    );

    if (initialState.nodes.every((node) => node.position.x === 0 && node.position.y === 0)) {
      applyLayout({
        nodes: initialState.nodes,
        edges: initialState.edges,
      }).then(({ nodes, edges }) => {
        setNodes(nodes);
        setEdges(edges);
        loadTerminalSettings();
      });
    } else {
      setNodes(initialState.nodes);
      setEdges(initialState.edges);
      loadTerminalSettings();
    }
  }, []);

  const graphToJson = () => {
    const lastNode = nodes.find((node) => node.id === 'returned-node');

    const startNode = nodes.find((node) => node.id === 'start');
    const startPosition = startNode.position;

    const start = {
      kind: 'workflow',
      steps: [],
      returned: lastNode.data.content?.returned,
      id: 'start',
      description: startNode.data.information.description,
      breakpoint: startNode.data.information.breakpoint,
      position: startPosition,
    };

    const startOutput = edges.find((edge) => edge.source === 'start');
    if (edges.length > 0 && startOutput) {
      const firstNode = nodes.find((node) => node.id === startOutput.target);

      const graph = nodeToJson(firstNode, start, false, [], true);
      return [
        {
          ...{
            ...graph[0],
            position: startPosition,
          },
          returned: graph[0].returned,
          returnedInformation: {
            position: lastNode.position,
            description: lastNode.data.information.description,
          },
        },
        graph[1],
      ];
    }

    return [start, []];
  };

  function getNode(item, alreadySeen) {
    const [node, seen] = removeReturnedFromWorkflow(
      nodeToJson(
        nodes.find((n) => n.id === item.target),
        emptyWorkflow,
        false,
        alreadySeen
      )
    );
    alreadySeen.push(...seen);
    return node;
  }

  const nodeToJson = (node, currentWorkflow, disableRecursion, alreadySeen, isStart) => {
    const connections = edges.filter((edge) => edge.source === node.id);

    const { kind } = node.data;

    let subflow = undefined;
    let nextNode = undefined;

    alreadySeen = alreadySeen.concat([node.id]);

    if (node.id.endsWith('returned-node')) {
      return [
        {
          ...currentWorkflow,
          returned: node.data.content?.returned,
          position: node.position,
        },
        alreadySeen,
      ];
    }

    if (kind === 'jump') {
      const ifFlow = node.data.content;

      const to = connections.find((conn) => conn.sourceHandle.startsWith('to'));

      subflow = {
        ...ifFlow,
        to: to?.target,
        kind,
      };
    } else if (kind === 'if') {
      const ifFlow = node.data.content;

      const then = connections.find((conn) => conn.sourceHandle.startsWith('then'));
      const elseTarget = connections.find((conn) => conn.sourceHandle.startsWith('else'));

      let thenNode, elseNode;

      if (then) {
        const res = removeReturnedFromWorkflow(
          nodeToJson(
            nodes.find((n) => n.id === then.target),
            emptyWorkflow,
            false,
            alreadySeen
          )
        );
        thenNode = res[0];
        alreadySeen = alreadySeen.concat([res[1]]);
      }

      if (elseTarget) {
        const res = removeReturnedFromWorkflow(
          nodeToJson(
            nodes.find((n) => n.id === elseTarget.target),
            emptyWorkflow,
            false,
            alreadySeen
          )
        );
        elseNode = res[0];
        alreadySeen = alreadySeen.concat([res[1]]);
      }

      const elseNodeIds = elseNode ? elseNode.steps.map((n) => n.id) : [];
      const thenNodeIds = thenNode ? thenNode.steps.map((n) => n.id) : [];
      const commonEnd = thenNode
        ? thenNode.steps.findIndex((node) => elseNodeIds.includes(node.id))
        : -1;

      if (commonEnd !== -1) {
        nextNode = nodes.find((n) => n.id === thenNode.steps[commonEnd].id);

        thenNode.steps = thenNode.steps.slice(0, commonEnd);
        elseNode.steps = elseNode.steps.slice(
          0,
          elseNode.steps.findIndex((node) => thenNodeIds.includes(node.id))
        );
      }

      subflow = {
        ...ifFlow,
        then: thenNode,
        else: elseNode,
        kind,
      };
    } else if (kind === 'try') {
      const tryFlow = node.data.content;
      const tryItem = connections.find((conn) => conn.sourceHandle.startsWith('try'));
      const catchItem = connections.find((conn) => conn.sourceHandle.startsWith('catch'));
      const finallyItem = connections.find((conn) => conn.sourceHandle.startsWith('finally'));

      let tryNode, catchNode, finallyNode;

      if (tryItem) {
        tryNode = getNode(tryItem, alreadySeen);
      }

      if (catchItem) {
        catchNode = getNode(catchItem, alreadySeen);
      }

      if (finallyItem) {
        finallyNode = getNode(finallyItem, alreadySeen);
      }

      subflow = {
        ...tryFlow,
        kind,
        node: tryNode,
        catch: catchNode,
        finally: finallyNode,
      };
    } else if (kind === 'foreach' || kind === 'async' || kind === 'while') {
      const handles = {
        foreach: 'ForEachLoop',
        async: 'Async Task',
        while: 'Loop Body',
      };

      const handleName = handles[kind];
      const flow = node.data.content;
      const item = connections.find((conn) => conn.sourceHandle.startsWith(handleName));

      subflow = {
        ...flow,
        kind,
      };

      if (item) {
        subflow = {
          ...subflow,
          node: getNode(item, alreadySeen),
        };
      }
    } else if (kind === 'map' || kind === 'flatmap') {
      const flow = node.data.content;
      const nodeLoop = connections.find((conn) => conn.sourceHandle.startsWith('Item'));

      if (nodeLoop) {
        let [node, seen] = removeReturnedFromWorkflow(
          nodeToJson(
            nodes.find((n) => n.id === nodeLoop.target),
            emptyWorkflow,
            false,
            alreadySeen
          )
        );
        alreadySeen = alreadySeen.concat([seen]);

        if (node.steps.length === 1) node = node.steps[0];

        subflow = {
          ...flow,
          node,
          kind,
        };
      } else {
        subflow = {
          ...flow,
          kind,
        };
      }
    } else if (kind === 'filter') {
      const targets = edges.filter((edge) => edge.target === node.id);
      const predicateFlow = node.data.content;
      const predicate = targets.find((conn) => conn.targetHandle.startsWith('predicate'));

      if (predicate) {
        const [predicateNode, seen] = removeReturnedFromWorkflow(
          nodeToJson(
            nodes.find((n) => n.id === predicate.source),
            undefined,
            true,
            alreadySeen
          )
        );
        alreadySeen = alreadySeen.concat([seen]);
        subflow = {
          ...predicateFlow,
          predicate: predicateNode,
          kind,
        };
      } else {
        subflow = {
          ...predicateFlow,
          kind,
        };
      }
    } else if (kind === 'parallel' || kind === 'switch') {
      let subGraph = node.data.sourceHandles.reduce(
        (acc, source, idx) => {
          const connection = connections.find((conn) => conn.sourceHandle === source.id);

          if (!connection) {
            // keep all fields except previous node
            const rest = Object.fromEntries(
              Object.entries(node.data.content.paths[idx]).filter(([key]) => key !== 'node')
            );
            return {
              ...acc,
              paths: [...acc.paths, rest],
            };
          }

          const target = nodes.find((n) => n.id === connection.target);
          const [pathNode, seen] = removeReturnedFromWorkflow(
            nodeToJson(target, emptyWorkflow, false, alreadySeen)
          );

          alreadySeen = alreadySeen.concat([seen]);

          const isSubFlowEmpty = pathNode.kind === 'workflow' && pathNode.steps.length === 0;
          const isOneNodeSubFlow = pathNode.kind === 'workflow' && pathNode.steps.length === 1;

          return {
            ...acc,
            paths: [
              ...acc.paths,
              {
                ...node.data.content.paths[idx],
                node: isSubFlowEmpty ? undefined : isOneNodeSubFlow ? pathNode.steps[0] : pathNode,
              },
            ],
          };
        },
        {
          ...node.data.content,
          paths: [],
          kind,
        }
      );

      function findCommonIds(ids) {
        if (!ids || ids.length === 0) return [];

        let common = new Set(ids[0]);

        for (let i = 1; i < ids.length; i++) {
          const currentSet = new Set(ids[i]);
          common = new Set([...common].filter((id) => currentSet.has(id)));
        }

        return [...common];
      }

      const ids = subGraph.paths
        .filter((path) => path.node)
        .map((path) => path.node)
        .map((node) => {
          if (node.kind === 'workflow') {
            return node.steps?.map((n) => n.id) || [];
          }
          return [node.id];
        });

      const commonIds = findCommonIds(ids);

      if (commonIds.length > 0) {
        const commonId = commonIds[0];

        nextNode = nodes.find((n) => n.id === commonId);

        subGraph = {
          ...subGraph,
          paths: subGraph.paths.map((path) => {
            if (path.node.kind === 'workflow') {
              const commonEnd = path.node.steps.findIndex((step) => step.id === commonId);
              return {
                node: {
                  ...path.node,
                  steps: path.node.steps.slice(0, commonEnd),
                },
              };
            } else {
              return emptyWorkflow;
            }
          }),
        };
      }

      subflow = subGraph;
    } else if (nodesCatalogSignal.value.extensionOverloads[kind] && nodesCatalogSignal.value.extensionOverloads[kind].nodeToJson) {
      const [flow, seen] = nodesCatalogSignal.value.extensionOverloads[kind].nodeToJson({
        edges,
        nodes,
        node,
        alreadySeen,
        connections,
        emptyWorkflow,
        nodeToJson: (newNode) => nodeToJson(newNode, emptyWorkflow, false, alreadySeen),
        removeReturnedFromWorkflow,
      });

      alreadySeen = seen

      if (!flow) console.log(`nothing was returned from ${kind}`);

      subflow = flow;
    } else {
      subflow = {
        ...node.data.content,
        id: node.id,
        kind,
      };
    }

    let outputWorkflow = subflow
      ? {
        ...node.data.content,
        ...node.data.information,
        ...subflow,
        id: node.id,
        kind,
        position: node.position,
      }
      : undefined;

    if (currentWorkflow && currentWorkflow.kind === 'workflow') {
      outputWorkflow = {
        ...currentWorkflow,
        steps: [...currentWorkflow.steps, outputWorkflow],
      };
    }

    if (nextNode)
      return removeReturnedFromWorkflow(nodeToJson(nextNode, outputWorkflow, false, alreadySeen));

    const output = connections.find((conn) => conn.sourceHandle.startsWith('output'));

    if (output && !disableRecursion)
      return removeReturnedFromWorkflow(
        nodeToJson(
          nodes.find((n) => n.id === output.target),
          outputWorkflow,
          false,
          alreadySeen
        )
      );

    // if (outputWorkflow && !isStart && outputWorkflow.kind === 'workflow' && outputWorkflow.steps.length === 1)
    //     return removeReturnedFromWorkflow([outputWorkflow.steps[0], alreadySeen])

    if (isStart) return [outputWorkflow, alreadySeen];

    return removeReturnedFromWorkflow([outputWorkflow, alreadySeen]);
  };

  const removeReturnedFromWorkflow = (nodes) => {
    if (nodes[0].kind === 'workflow' && nodes[0].id !== 'start') {
      return [{ ...nodes[0], returned: undefined }, nodes[1]];
    }
    return nodes;
  };

  const handleSave = () => {
    const graph = graphToJson();

    const [config, seen] = graph;
    const alreadySeen = seen.flat();

    const orphans = nodes.filter(
      (node) =>
        node.id !== 'start' &&
        node.kind !== 'note' &&
        node.id !== 'returned-node' &&
        !alreadySeen.includes(node.id)
    );

    const notes = nodes.filter((node) => node.type === 'note' || node.data.kind === 'note');

    const orphansEdges = orphans
      .filter((orphan) => edges.find((edge) => edge.source === orphan.id))
      .flatMap((orphan) =>
        edges.filter((edge) => edge.target === orphan.id || edge.source === orphan.id)
      )
      .reduce((edges, edge) => {
        if (!edges.find((e) => e.id === edge.id) && edge.id !== 'start-edge') {
          return [...edges, edge];
        }
        return edges;
      }, []);

    return props.handleSave(
      config,
      {
        nodes: orphans
          .filter((orphan) => edges.find((edge) => edge.source === orphan.id))
          .map((r) => ({
            id: r.id,
            ...r.data.content,
            ...r.data.information,
            position: r.position,
            kind: r.data.kind,
          })),
        edges: orphansEdges,
      },
      notes.map((note) => ({
        id: note.id,
        ...note.data.content,
        position: note.position,
        measured: note.measured,
        kind: note.data.kind,
      }))
    );
  };

  function updateData(props, changes) {
    setNodes(
      nodes.map((node) => {
        if (node.id === props.id) {
          return {
            ...node,
            data: {
              ...node.data,
              ...changes,
            },
          };
        }
        return node;
      })
    );
  }

  function addInformationsToNode(node) {
    return {
      ...node,
      data: {
        ...(node.data || {}),
        functions: {
          onDoubleClick: setActiveNode,
          onNodeDelete: onNodeDelete,
          updateData: updateData,
          appendSourceHandle: appendSourceHandle,
          handleDataChange: handleDataChange,
          deleteHandle: deleteHandle,
          toggleBreakPoint: toggleBreakPoint,
        },
      },
    };
  }

  const toggleBreakPoint = (nodeId) => {
    setNodes((eds) =>
      eds.map((node) => {
        if (node.id === nodeId) {
          return {
            ...node,
            data: {
              ...node.data,
              information: {
                ...node.data.information,
                breakpoint: !node.data.information.breakpoint,
              },
            },
          };
        }
        return node;
      })
    );
  };

  function handleDataChange(nodeId, newData) {
    setNodes((eds) =>
      eds.map((node) => {
        if (node.id === nodeId) {
          return {
            ...node,
            data: {
              ...node.data,
              ...newData,
            },
          };
        }
        return node;
      })
    );
  }

  function appendSourceHandle(nodeId, handlePrefix, sourcesField = 'paths') {
    setNodes((eds) =>
      eds.map((node) => {
        if (node.id === nodeId) {
          return {
            ...node,
            data: {
              ...node.data,
              sourceHandles: [
                ...node.data.sourceHandles,
                { id: `${handlePrefix ? handlePrefix : path}-${node.data.sourceHandles.length}` },
              ],
              content: {
                ...node.data.content,
                [sourcesField]: [...(node.data.content[sourcesField] || []), {}],
              },
            },
          };
        }
        return node;
      })
    );

    const sourceEl = document.querySelector(`[data-id="${nodeId}"]`);
    sourceEl.style.height = `${Number(sourceEl.style.height.split('px')[0]) + 20}px`;

    setTimeout(() => {
      updateNodeInternals(nodeId);
    }, 250);
  }

  function deleteHandle(nodeId, handleId, sourcesField = 'paths') {
    setNodes((eds) =>
      eds.map((node) => {
        if (node.id === nodeId) {
          const index = node.data.sourceHandles.findIndex((ha) => ha.id === handleId);

          return {
            ...node,
            data: {
              ...node.data,
              sourceHandles: node.data.sourceHandles.filter((ha) => ha.id !== handleId),
              content: {
                ...node.data.content,
                [sourcesField]: node.data.content[sourcesField].filter((_, i) => i !== index),
              },
            },
          };
        }
        return node;
      })
    );

    setEdges((eds) => eds.filter((edge) => edge.sourceHandle !== handleId));

    const sourceEl = document.querySelector(`[data-id="${nodeId}"]`);
    sourceEl.style.height = `${Number(sourceEl.style.height.split('px')[0]) - 20}px`;

    updateNodeInternals(nodeId);
  }

  function onNodeDelete(nodeId) {
    setNodes((nds) => nds.filter((node) => node.id !== nodeId));
    setEdges((eds) => eds.filter((edge) => edge.source !== nodeId && edge.target !== nodeId));
  }

  const onConnectEnd = useCallback((event, connectionState) => {
    event.stopPropagation();
    if (!connectionState.isValid) {
      setActiveNode({
        ...connectionState.fromNode,
        handle: connectionState.fromHandle,
        event,
      });
    }
  }, []);

  const onConnect = useCallback((connection) => {
    const edge = {
      ...connection,
      type: 'customEdge',
      animated: true,
    };
    setEdges((eds) =>
      !eds.find((e) => e.sourceHandle === edge.sourceHandle) ? addEdge(edge, eds) : eds
    );
  }, []);

  const handleSelectNode = (item) => {
    let targetId = uuid();

    const startPosition = nodes.find((node) => node.id === 'start').position;

    let position = activeNode.fromOrigin
      ? activeNode.fromOrigin
      : { x: startPosition.x, y: startPosition.y - 150 };
    if (activeNode.event) {
      const { clientX, clientY } =
        'changedTouches' in activeNode.event
          ? activeNode.event.changedTouches[0]
          : activeNode.event;
      position = { x: clientX, y: clientY };
    }

    if (item.operator) targetId = `${targetId}-operator`;

    let newNode = addInformationsToNode({
      ...createNodeFromUI(item),
      id: targetId,
      type: item.type || 'simple',
      position:
        activeNode.fromOrigin || activeNode.event ? screenToFlowPosition(position) : position,
    });

    let newEdges = [];

    if (activeNode && activeNode.handle) {
      const sourceHandle = activeNode.handle.id;

      // If the handle is on the left, we have to reverse the edge direction
      if (activeNode.handle.position === 'left') {
        newEdges.push({
          id: uuid(),
          source: newNode.id,
          sourceHandle: `output-${newNode.id}`,
          target: activeNode.id,
          targetHandle: sourceHandle,
          type: 'customEdge',
          animated: true,
        });
      } else {
        newEdges.push({
          id: uuid(),
          source: activeNode.id,
          sourceHandle,
          target: newNode.id,
          targetHandle: `input-${newNode.id}`,
          type: 'customEdge',
          animated: true,
        });
      }
    }

    const { targets = [], sources = [] } = newNode.data;
    newNode = {
      ...newNode,
      data: {
        ...newNode.data,
        targetHandles: ['input', ...targets].map((target) => {
          return { id: `${target}-${newNode.id}` };
        }),
        sourceHandles: [...sources].map((source) => {
          return { id: `${source}-${newNode.id}` };
        }),
      },
    };

    const newNodes = [...nodes, newNode].filter((f) => f);
    newEdges = [...edges, ...newEdges];

    setNodes(newNodes);
    setEdges(newEdges);

    setActiveNode(false);
  };

  const highlightNode = (nodeId) => {
    // console.log('highlightNode', nodeId)
    setNodes((nds) =>
      nds.map((node) => {
        if (node.id === nodeId && !node.data.highlighted)
          return {
            ...node,
            data: {
              ...node.data,
              highlighted: true,
            },
          };
        return node;
      })
    );
  };

  const highlightEdge = (targetId) => {
    // console.log('highlightEdge', targetId)
    setEdges((edgs) =>
      edgs.map((edge) => {
        if (edge.target === targetId && !edge.target.data?.highlighted)
          return {
            ...edge,
            data: {
              highlighted: true,
            },
          };
        return edge;
      })
    );
  };

  const setErrorEdge = (targetId) => {
    // console.log('setErrorEdge', targetId)
    setEdges((edgs) =>
      edgs.map((edge) => {
        if (edge.target === targetId && !edge.target.data?.error)
          return {
            ...edge,
            data: {
              error: true,
            },
          };
        return edge;
      })
    );
  };

  const setErrorNode = (nodeId) => {
    setNodes((nds) =>
      nds.map((node) => {
        if (node.id === nodeId)
          return {
            ...node,
            data: {
              ...node.data,
              highlighted: false,
              error: true,
            },
          };
        return node;
      })
    );
  };

  const unhighlighNode = (event_id) => {
    // console.log('unhighlighNode', event_id)
    setNodes((nds) =>
      nds.map((node) => {
        if (node.id === event_id)
          return {
            ...node,
            data: {
              ...node.data,
              highlighted: 'END',
            },
          };
        return node;
      })
    );
  };

  const closeAllModals = () => {
    setActiveNode(false);
    openTagModal(false);
  };

  const handleFlowClick = useCallback(() => {
    if (!activeNode.handle) closeAllModals();
  }, [activeNode]);

  const handleGroupNodeClick = useCallback((groupNode) => {
    setActiveNode(groupNode);
  }, []);

  const manageTags = useCallback(() => {
    closeAllModals(false);
    openTagModal(true);
  }, []);

  const setTags = useCallback(
    (newTags) => {
      setWorkflow((workflow) => ({
        ...workflow,
        tags: newTags,
      }));
    },
    [workflow]
  );

  const autoLayout = () => {
    applyLayout({
      nodes: nodes,
      edges: edges,
    }).then((res) => {
      setNodes(
        res.nodes.map((node) => {
          if (node.data.kind === 'note') {
            return nodes.find((n) => n.id === node.id);
          }
          return node;
        })
      );
      setEdges(res.edges);
    });
  };

  const resetFlow = () => {
    setEdges((eds) =>
      eds.map((e) => ({
        ...e,
        data: {
          highlighted: false,
          error: false,
        },
        animated: false,
      }))
    );
    setNodes((nds) =>
      nds.map((n) => ({
        ...n,
        data: {
          ...n.data,
          highlighted: false,
          error: false,
        },
      }))
    );
  };

  const getTestPayload = (input) => ({
    input: JSON.stringify(input),
    workflow: graphToJson()[0],
    workflow_id: props.workflow.id,
    functions: props.workflow.functions,
  });

  function getCenter(nodes) {
    if (nodes.length === 0) return { x: 0, y: 0 };

    let minX = Infinity,
      minY = Infinity;
    let maxX = -Infinity,
      maxY = -Infinity;

    nodes.forEach((node) => {
      const { x, y } = node.position;
      const width = node.width ?? 0;
      const height = node.height ?? 0;

      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x + width);
      maxY = Math.max(maxY, y + height);
    });

    return {
      x: (minX + maxX) / 2,
      y: (minY + maxY) / 2,
    };
  }

  const scrollToNode = (selectedNode) => {
    if (selectedNode.id === 'start') {
      const center = getCenter(nodes);

      setCenter(center.x + 75, center.y, { zoom: 0.75, duration: 1200 });
      return;
    }

    const node = reactFlow.getNode(selectedNode.id);

    if (node) {
      const nodeWidth = 150;
      const rightX = node.position.x + nodeWidth / 2;
      const centerY = node.position.y + 25;

      setCenter(rightX, centerY, { zoom: 1.5, duration: 1200 });
    }
  };

  if (nodes.length === 0) return null;

  // console.log(nodes)

  return (
    <div
      className="workflow"
      onMouseLeave={(e) => {
        if (resizingTerminal) {
          e.stopPropagation();
          toggleResizingTerminal(false);
        }
      }}
      onMouseUp={(e) => {
        if (resizingTerminal) {
          e.stopPropagation();
          toggleResizingTerminal(false);
        }
      }}
      onMouseMove={(e) => {
        if (resizingTerminal) {
          e.stopPropagation();
          const r = 1 - e.clientY / window.innerHeight;
          changeTerminalSize(r > 0.75 ? 0.75 : r);
        }
      }}
    >
      <div
        className="d-flex flex-column scroll-container"
        style={{
          flex: 1 - terminalSize,
          overflow: 'scroll',
        }}
      >
        <Navbar workflow={workflow} save={handleSave} manageTags={manageTags} />

        <NewTask onClick={() => setActiveNode(true)} />

        <TagsModal isOpen={showTagModal} tags={workflow} setTags={setTags} />

        {activeNode && (
          <NodesExplorer
            activeNode={activeNode}
            handleSelectNode={handleSelectNode}
            close={closeAllModals}
          />
        )}
        <Flow
          autoLayout={autoLayout}
          onConnectEnd={onConnectEnd}
          onConnect={onConnect}
          onEdgesChange={onEdgesChange}
          onNodesChange={onNodesChange}
          onClick={handleFlowClick}
          onGroupNodeClick={handleGroupNodeClick}
          nodes={nodes}
          edges={edges}
        />
      </div>

      <Terminal
        terminalSize={terminalSize}
        changeTerminalSize={changeTerminalSize}
        toggleResizingTerminal={toggleResizingTerminal}
        saveTerminalTab={saveTerminalTab}
        initialTerminalTab={initialTerminalTab}
        getTestPayload={getTestPayload}
        flowOperators={{
          highlightNode,
          highlightEdge,
          unhighlighNode,
          setErrorNode,
          setErrorEdge,
          resetFlow,
          scrollToNode,
        }}
      />
    </div>
  );
}
