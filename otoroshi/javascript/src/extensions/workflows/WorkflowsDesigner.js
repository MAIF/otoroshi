import React, { useCallback, useEffect, useState } from 'react';
import { Flow } from './Flow';
import { DesignerActions } from './DesignerActions';
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
import ReportExplorer from './ReportExplorer';
import { applyLayout } from './ElkOptions';
import { TagsModal } from './TagsModal';
import { useSignalValue } from 'signals-react-safe';

export const INFORMATION_FIELDS = ['description', 'kind', 'enabled', 'result', 'name'];

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

export function WorkflowsDesigner(props) {
  const updateNodeInternals = useUpdateNodeInternals();
  const { screenToFlowPosition } = useReactFlow();

  const [activeNode, setActiveNode] = useState(false);
  const [showTagModal, openTagModal] = useState(false);

  const [report, setReport] = useState();
  const [reportIsOpen, setReportStatus] = useState(false);

  const [nodes, setNodes, onNodesChange] = useNodesState([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState([]);

  const [workflow, setWorkflow] = useState(props.workflow);

  const catalog = useSignalValue(nodesCatalogSignal);

  function createNodeFromUI(node) {
    console.log('createNodeFromUI', node);

    const { information } = splitInformationAndContent(node);

    const data = catalog.nodes[(information.kind || information.name).toLowerCase()];

    let functionData = {};
    if (node.category === 'functions') {
      functionData = {
        function: node.name,
      }
    } else if (node.category === 'udfs') {
      functionData = {
        function: `self.${node.name}`
      }
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
    const { information, content } = splitInformationAndContent(child);

    const template = catalog.nodes[(information.kind || information.name).toLowerCase()];

    const node = {
      id,
      position: child.position || { x: 0, y: 0 },
      type: template?.type || 'simple',
      data: {
        ...template,
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

    const me = uuid();

    let edges = [];
    let nodes = [];

    const useCurrent = workflow.kind !== 'workflow';

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
    } else if (
      workflow.kind === 'foreach' ||
      workflow.kind === 'flatmap' ||
      workflow.kind === 'map'
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

  const initializeGraph = (config, orphans, addInformationsToNode) => {
    let startingNode = createNode(
      'start',
      {
        kind: 'start',
        description: config.description,
        position: config.position || { x: 0, y: 0 },
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
        returned: {
          ...(config.returned || {}),
        },
        description: config.returned.description,
        kind: 'returned',
      },
      addInformationsToNode
    );

    returnedNode = {
      ...returnedNode,
      position: config.returned?.position || returnedNode.position,
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
      nodes: [startingNode, ...subGraph.nodes, ...orphansNodes, returnedNode],
      edges: [...subGraph.edges, ...orphans.edges, startingEdge],
    };
  };

  useEffect(() => {
    const initialState = initializeGraph(workflow?.config, workflow.orphans, addInformationsToNode);

    if (initialState.nodes.every((node) => node.position.x === 0 && node.position.y === 0)) {
      applyLayout({
        nodes: initialState.nodes,
        edges: initialState.edges,
      }).then(({ nodes, edges }) => {
        setNodes(nodes);
        setEdges(edges);
      });
    } else {
      setNodes(initialState.nodes);
      setEdges(initialState.edges);
    }
  }, []);

  const graphToJson = () => {
    const lastNode = nodes.find((node) => node.id === 'returned-node');

    const startNode = nodes.find((node) => node.id === 'start')
    const startPosition = startNode.position;

    const start = {
      kind: 'workflow',
      steps: [],
      returned: lastNode.data.content?.returned,
      id: 'start',
      description: startNode.data.information.description,
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
          returned: {
            ...graph[0].returned,
            position: lastNode.position,
            description: lastNode.data.information.description,
          },
        },
        graph[1],
      ];
    }

    return [start, []];
  };

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

    if (kind === 'if') {
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

        thenNode = thenNode.steps.slice(0, commonEnd);
        elseNode = elseNode.steps.slice(
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
    } else if (kind === 'foreach') {
      const foreachFlow = node.data.content;
      const foreachLoop = connections.find((conn) => conn.sourceHandle.startsWith('ForEachLoop'));

      if (foreachLoop) {
        const [node, seen] = removeReturnedFromWorkflow(
          nodeToJson(
            nodes.find((n) => n.id === foreachLoop.target),
            emptyWorkflow,
            false,
            alreadySeen
          )
        );
        alreadySeen = alreadySeen.concat([seen]);

        subflow = {
          ...foreachFlow,
          node,
          kind,
        };
      } else {
        subflow = {
          ...foreachFlow,
          kind,
        };
      }
    } else if (kind === 'map' || kind === 'flatmap' || kind === 'foreach') {
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
      subflow = node.data.sourceHandles.reduce(
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

  const removeReturnedFromWorkflow = (output) => {
    if (output[0].kind === 'workflow' && output[0].id !== 'start') {
      return [{ ...output[0], returned: undefined }, output[1]];
    }
    return output;
  };

  const handleSave = () => {
    const graph = graphToJson()

    const [config, seen] = graph
    const alreadySeen = seen.flatMap((f) => f)

    const orphans = nodes.filter(
      (node) => node.id !== 'start' && node.id !== 'returned-node' && !alreadySeen.includes(node.id)
    )

    const orphansEdges = orphans
      .flatMap((orphan) =>
        edges.filter((edge) => edge.target === orphan.id || edge.source === orphan.id)
      )
      .reduce((edges, edge) => {
        if (!edges.find((e) => e.id === edge.id) && edge.id !== 'start-edge') {
          return [...edges, edge]
        }
        return edges
      }, [])

    return props.handleSave(
      config,
      {
        nodes: orphans.map((r) => ({
          id: r.id,
          ...r.data.content,
          ...r.data.information,
          position: r.position,
          kind: r.data.kind
        })),
        edges: orphansEdges,
      }
    )
  }

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
          deleteHandle: deleteHandle
        },
      },
    };
  }

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

  function appendSourceHandle(nodeId, handlePrefix) {
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
                paths: [...(node.data.content.paths || []), {}],
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

  function deleteHandle(nodeId, handleId) {
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
                paths: node.data.content.paths.filter((_, i) => i !== index),
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

    // console.log(edges, !edges.find(e => e.sourceHandle === edge.sourceHandle) ? addEdge(edge, edges) : edges)
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

      const parent = nodes.find((node) => node.id === activeNode.id);

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

  function run() {
    fetch('/extensions/workflows/_test', {
      method: 'POST',
      credentials: 'include',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        input: JSON.stringify({}, null, 4),
        workflow: graphToJson()[0],
        workflow_id: props.workflow.id,
        functions: props.workflow.functions
      }),
    })
      .then((r) => r.json())
      .then((report) => {
        setReport(report);
        setReportStatus(true);
      });
  }

  const closeAllModals = () => {
    setActiveNode(false);
    setReportStatus(false);
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
    }).then(({ nodes, edges }) => {
      setNodes(nodes);
      setEdges(edges);
    });
  };

  if (nodes.length === 0) return null;

  // console.log(nodes, edges);

  return (
    <div className="workflow">
      <DesignerActions run={run} />
      <Navbar workflow={workflow} save={handleSave} manageTags={manageTags} />

      <NewTask onClick={() => setActiveNode(true)} />

      <ReportExplorer
        report={report}
        isOpen={reportIsOpen}
        handleClose={() => setReportStatus(false)}
      />

      <TagsModal isOpen={showTagModal} tags={workflow} setTags={setTags} />

      {activeNode && <NodesExplorer activeNode={activeNode} handleSelectNode={handleSelectNode} />}
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
  );
}
