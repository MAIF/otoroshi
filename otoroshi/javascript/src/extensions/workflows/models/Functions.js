import { AssignNode } from '../nodes/AssignNode';
import { CallNode } from '../nodes/CallNode';
import { FilterNode } from '../nodes/FilterNode';
import { FlatMapNode } from '../nodes/FlatMapNode';
import { ForEachNode } from '../nodes/ForEachNode';
import { IfThenElseNode } from '../nodes/IfThenElseNode';
import { MapNode } from '../nodes/MapNode';
import { ParallelFlowsNode } from '../nodes/ParallelFlowsNode';
import { SwitchNode } from '../nodes/SwitchNode';
import { ValueNode } from '../nodes/ValueNode';
import { WaitNode } from '../nodes/WaitNode';
import { StartNode } from '../nodes/StartNode';
import { ReturnedNode } from '../nodes/ReturnedNode';

import { IsFalsyOperator } from '../operators/IsFalsyOperator';
import { ArrayAppendOperator } from '../operators/ArrayAppendOperator';
import { NeqOperator } from '../operators/NeqOperator';
import { ArrayAtOperator } from '../operators/ArrayAtOperator';
import { ArrayPageOperator } from '../operators/ArrayPageOperator';
import { MapGetOperator } from '../operators/MapGetOperator';
import { MapRenameOperator } from '../operators/MapRenameOperator';
import { MapIsEmptyOperator } from '../operators/MapIsEmptyOperator';
import { ArrayIsEmptyOperator } from '../operators/ArrayIsEmptyOperator';
import { MemRefOperator } from '../operators/MemRefOperator';
import { EncodeBase64Operator } from '../operators/EncodeBase64Operator';
import { NotOperator } from '../operators/NotOperator';
import { ProjectionOperator } from '../operators/ProjectionOperator';
import { StrSplitOperator } from '../operators/StrSplitOperator';
import { ArrayPrependOperator } from '../operators/ArrayPrependOperator';
import { DecodeBase64Operator } from '../operators/DecodeBase64Operator';
import { JsonParseOperator } from '../operators/JsonParseOperator';
import { ContainsOperator } from '../operators/ContainsOperator';
import { MapPutOperator } from '../operators/MapPutOperator';
import { IsTruthyOperator } from '../operators/IsTruthyOperator';
import { MapDelOperator } from '../operators/MapDelOperator';
import { ArrayDelOperator } from '../operators/ArrayDelOperator';
import { ArrayDropOperator } from '../operators/ArrayDropOperator';

import { WorkflowFunction } from '../functions/WorkflowFunction';
import { SendMailFunction } from '../functions/SendMailFunction';
import { LogFunction } from '../functions/LogFunction';

import { signal } from 'signals-react-safe';
import { EndNode } from '../nodes/EndNode';
import { TryCatchNode } from '../nodes/TryCatchNode';
import { StopAndErrorNode } from '../nodes/StopAndErrorNode';
import { AsyncNode } from '../nodes/AsyncNode';
import { WhileNode } from '../nodes/WhileNode';
import { JumpNode } from '../nodes/JumpNode';

export const nodesCatalogSignal = signal({
  nodes: [],
  categories: [],
  workflows: [],
});

const OVERLOADED_NODES = {
  assign: AssignNode,
  parallel: ParallelFlowsNode,
  switch: SwitchNode,
  if: IfThenElseNode,
  jump: JumpNode,
  foreach: ForEachNode,
  try: TryCatchNode,
  map: MapNode,
  filter: FilterNode,
  flatmap: FlatMapNode,
  call: CallNode,
  wait: WaitNode,
  value: ValueNode,
  returned: ReturnedNode,
  start: StartNode,
  $mem_ref: MemRefOperator,
  $array_append: ArrayAppendOperator,
  $array_drop: ArrayDropOperator,
  $array_prepend: ArrayPrependOperator,
  $array_at: ArrayAtOperator,
  $array_del: ArrayDelOperator,
  $array_page: ArrayPageOperator,
  $projection: ProjectionOperator,
  $map_put: MapPutOperator,
  $map_get: MapGetOperator,
  $map_rename: MapRenameOperator,
  $map_is_empty: MapIsEmptyOperator,
  $array_is_empty: ArrayIsEmptyOperator,
  $map_del: MapDelOperator,
  $json_parse: JsonParseOperator,
  $is_truthy: IsTruthyOperator,
  $is_falsy: IsFalsyOperator,
  $contains: ContainsOperator,
  $neq: NeqOperator,
  $encode_base64: EncodeBase64Operator,
  $decode_base64: DecodeBase64Operator,
  $not: NotOperator,
  $str_split: StrSplitOperator,
  'core.workflow_call': WorkflowFunction,
  'core.send_mail': SendMailFunction,
  'core.log': LogFunction,
  end: EndNode,
  while: WhileNode,
  async: AsyncNode,
  error: StopAndErrorNode
};

function getNodeCategory(categories, node) {
  let nodeCategory = node.category;

  let hasCategory = categories.find((cat) => cat.id === nodeCategory);

  if (!hasCategory) {
    for (const category of categories) {
      const defaultCategory = category.nodes.find((n) => n === node.kind);

      if (defaultCategory) {
        nodeCategory = defaultCategory;
        hasCategory = true;
        break;
      }
    }
  }

  return nodeCategory;
}

export function getNodeFromKind(kind) {
  return Object.values(nodesCatalogSignal.value.nodes).find(
    (n) => n.kind === kind || n.name === kind
  );
}

export function NODES_BY_CATEGORIES(nodes, categories) {
  return Object.values(nodes).reduce(
    (categories, node) => {
      const nodeCategory = getNodeCategory(categories, node);

      return categories.map((category) => {
        if (!nodeCategory && category.id === 'others')
          return {
            ...category,
            nodes: [...category.nodes, node.name],
          };
        if (category.id === nodeCategory) {
          return {
            ...category,
            nodes: [...category.nodes, node.name],
          };
        }
        return category;
      });
    },
    categories.sort((a, b) => a.name.localeCompare(b.name))
  );
}

export const NODES = (documentation, extensionOverloads) => {
  let defaultValues = [
    ...documentation.nodes.map((n) => ({
      ...n,
      nodes: true,
      sources: ['output'],
      schema: n.form_schema,
      kind: n.kind || n.name,
    })),
    ...documentation.functions.map((n) => ({
      ...n,
      category: 'functions',
      sources: ['output'],
      schema: n.form_schema,
      kind: 'Call',
    })),
    ...documentation.operators.map((n) => ({
      ...n,
      operators: true,
      sources: ['output'],
      schema: n.form_schema,
      kind: n.kind || n.name,
    })),
  ];

  const items = Object.fromEntries(
    Object.entries({
      ...OVERLOADED_NODES,
      ...extensionOverloads.nodes.reduce((acc, n) => {
        return {
          ...acc,
          [n.name]: {
            ...n,
            node: true,
            schema: n.form_schema,
            kind: n.kind || n.name,
          },
        };
      }, {}),
      ...extensionOverloads.operators.reduce((acc, ope) => {
        return {
          ...acc,
          [ope.name]: {
            ...ope,
            operators: true,
            schema: ope.form_schema,
            kind: ope.kind || ope.name,
          },
        };
      }, {}),
      ...extensionOverloads.functions.map((acc, func) => {
        return {
          ...acc,
          [func.name]: {
            ...func,
            category: 'functions',
            schema: func.form_schema,
          },
        };
      }, {}),
    }).map(([key, node]) => {
      const defaultValue = defaultValues.find((n) => n.name === key);

      if (defaultValue) defaultValues = defaultValues.filter((f) => f.name !== key);

      return [
        key,
        {
          ...(defaultValue || {}),
          ...node,
          kind: defaultValue?.kind || defaultValue?.name || node.name || node.kind,
        },
      ];
    })
  );

  defaultValues.forEach((node) => {
    if (!items[node.name])
      items[node.name] = {
        ...node,
        kind: node.kind || node.name,
      };
  });

  // [...extensionOverloads.nodes, ...extensionOverloads.operators, ...extensionOverloads.functions].map(node => {
  //   if (!items[node.name]) {
  //     items[node.name] = {
  //       ...node,
  //       kind: node.kind || node.name,
  //     };
  //   }
  // })

  return items;
};
