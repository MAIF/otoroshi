(function () {
  const extensionId = "otoroshi.extensions.Foo";
  Otoroshi.registerExtension(extensionId, false, (ctx) => {

    const dependencies = ctx.dependencies;

    const React = dependencies.react;
    const Component = React.Component;
    const uuid = dependencies.uuid;
    const Table = dependencies.Components.Inputs.Table;
    const BackOfficeServices = dependencies.BackOfficeServices;

    class FooTab extends Component {

      componentDidMount() {
        const { FeedbackButton, setSaveButton, isCreation } = this.props.settings;
        setSaveButton(
          React.createElement(FeedbackButton, {
            className: "ms-2 mb-1",
            onPress: () => console.log("onPress"),
            text: isCreation ? `Create route` : `Save route`,
            icon: () => React.createElement('i', { className: "fas fa-paper-plane" }),
          })
        );
      }

      render() {
        return (
          React.createElement('div', null, React.createElement('h1', { style: { color: 'var(--color_level1)' } }, 'Tab extension !'))
        );
      }
    }

    class FoosPage extends Component {

      formSchema = {
        _loc: {
          type: 'location',
          props: {},
        },
        id: { type: 'string', disabled: true, props: { label: 'Id', placeholder: '---' } },
        name: {
          type: 'string',
          props: { label: 'Name', placeholder: 'My Awesome Foo' },
        },
        description: {
          type: 'string',
          props: { label: 'Description', placeholder: 'Description of the Foo' },
        },
        metadata: {
          type: 'object',
          props: { label: 'Metadata' },
        },
        tags: {
          type: 'array',
          props: { label: 'Tags' },
        },
      };

      columns = [
        {
          title: 'Name',
          filterId: 'name',
          content: (item) => item.name,
        },
        { title: 'Description', filterId: 'description', content: (item) => item.description },
      ];

      formFlow = ['_loc', 'id', 'name', 'description', 'tags', 'metadata'];

      componentDidMount() {
        this.props.setTitle(`Foos`);
      }

      client = BackOfficeServices.apisClient('foo.extensions.otoroshi.io', 'v1', 'foos');

      render() {
        return (
          React.createElement(Table, {
            parentProps: this.props,
            selfUrl: "extensions/foo/foos",
            defaultTitle: "All foos",
            defaultValue: () => ({ id: 'foo_' + uuid(), name: 'Foo', description: 'New foo', tags: [], metadata: {} }),
            itemName: "Foo",
            formSchema: this.formSchema,
            formFlow: this.formFlow,
            columns: this.columns,
            stayAfterSave: true,
            fetchItems: (paginationState) => this.client.findAll(),
            updateItem: this.client.update,
            deleteItem: this.client.delete,
            createItem: this.client.create,
            navigateTo: (item) => {
              window.location = `/bo/dashboard/extensions/foo/foos/edit/${item.id}`
            },
            itemUrl: (item) => `/bo/dashboard/extensions/foo/foos/edit/${item.id}`,
            showActions: true,
            showLink: true,
            rowNavigation: true,
            extractKey: (item) => item.id,
            export: true,
            kubernetesKind: "Foo"
          }, null)
        );
      }
    }

    return {
      id: extensionId,
      routeDesignerTabs: [
        {
          id: "foo",
          label: "Foo",
          icon: 'fas fa-pencil',
          render: (settings) => React.createElement(FooTab, { settings }),
        }
      ],
      pluginForms: [
        {
          id: 'cp:otoroshi.next.extensions.FooPlugin',
          config_schema: {
            filter: {
              label: 'filter',
              type: 'string',
            },
            hi: {
              renderer: (props) => {
                return React.createElement('button', {
                  type: "button",
                  className: "btn btn-sm btn-primary mb-3",
                  onClick: () => console.log('hi')
                }, 'Say hi')
              }
            },
          },
          config_flow: ['filter', 'hi'],
        }
      ],
      features: [
        {
          title: 'Foos',
          description: 'All your foos',
          img: 'danger-zone',
          link: '/extensions/foo/foos',
          display: () => true,
          icon: () => 'fa-cubes',
        },
      ],
      sidebarItems: [
        {
          title: 'Foos',
          text: 'All your Foos',
          path: 'extensions/foo/foos',
          icon: 'cubes'
        }
      ],
      creationItems: [
        {
          title: 'Foos',
          path: 'extensions/foo/foos/add',
        }
      ],
      searchItems: [
        {
          action: () => {
            window.location.href = `/bo/dashboard/extensions/foo/foos`
          },
          env: React.createElement('span', { className: "fas fa-cubes" }, null),
          label: 'Foos',
          value: 'foos',
        }
      ],
      routes: [
        {
          path: '/extensions/foo/foos/:taction/:titem',
          component: (props) => {
            return React.createElement(FoosPage, props, null)
          }
        },
        {
          path: '/extensions/foo/foos/:taction',
          component: (props) => {
            return React.createElement(FoosPage, props, null)
          }
        },
        {
          path: '/extensions/foo/foos',
          component: (props) => {
            return React.createElement(FoosPage, props, null)
          }
        }
      ],
      dangerZoneParts: [
        {
          title: 'Foos',
          flow: [`extensions.${extensionId.replace(/\./g, '_')}.foos.maxSize`],
          schema: {
            [`extensions.${extensionId.replace(/\./g, '_')}.foos.maxSize`]: {
              type: 'number',
              props: { label: 'Max size of a foo' },
            }
          }
        }
      ],
      workflowNodes: [
        {
          name: '$foo_node',
          kind: '$foo_node',
          description: 'Foo node',
          display_name: "Foo node",
          icon: 'fas fa-cookie',
          type: 'group',
          flow: [],
          form_schema: {},
          sources: ['Custom Source', 'output'],
          nodeToJson: ({
            edges,
            nodes,
            node,
            alreadySeen,
            connections,
            nodeToJson,
            removeReturnedFromWorkflow }) => {
            const { kind } = node.data;
            const flow = node.data.content;
            const nodeLoop = connections.find((conn) => conn.sourceHandle.startsWith('Custom Source'));

            if (nodeLoop) {
              let [node, seen] = removeReturnedFromWorkflow(
                nodeToJson(nodes.find((n) => n.id === nodeLoop.target))
              );
              alreadySeen = alreadySeen.concat([seen]);


              if (node.steps.length === 1) node = node.steps[0];

              return {
                ...flow,
                node,
                kind,
              }
            } else {
              return subflow = {
                ...flow,
                kind,
              };
            }
          },
          buildGraph: ({ workflow, addInformationsToNode, targetId, handleId, buildGraph, current, me }) => {

            let nodes = []
            let edges = []

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

            return { nodes, edges }
          }
        },
        {
          name: 'extensions.com.cloud-apim.llm-extension.router',
          kind: 'extensions.com.cloud-apim.llm-extension.router',
          description: 'AI agent router',
          display_name: "AI agent router",
          icon: 'fas fa-road',
          type: 'group',
          flow: ['provider', 'input', 'instructions'],
          form_schema: {
            provider: {
              type: 'select',
              label: 'Provider',
              props: {
                optionsFrom: '/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers',
                optionsTransformer: {
                  label: 'name',
                  value: 'id',
                },
              },
            },
            input: {
              type: 'any',
              label: 'input',
              props: {
                height: '200px'
              }
            },
            instructions: {
              type: 'any',
              label: 'Instructions',
              props: {
                height: '200px'
              }
            }
          },
          sourcesIsArray: true,
          handlePrefix: "Path",
          sources: [],
          height: (data) => `$${110 + 20 * data?.sourceHandles?.length}px`,
          nodeToJson: ({
            edges,
            nodes,
            node,
            alreadySeen,
            connections,
            nodeToJson,
            removeReturnedFromWorkflow,
            emptyWorkflow }) => {
            const { kind } = node.data;
            return node.data.sourceHandles.reduce(
              (acc, source, idx) => {
                const connection = connections.find((conn) => conn.sourceHandle === source.id);

                if (!connection) {
                  // keep all fields except previous node
                  const rest = Object.fromEntries(
                    Object.entries(node.data.content.paths[idx])
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
                id: node.id,
              }
            );
          },
          buildGraph: ({ workflow, addInformationsToNode, targetId, handleId, buildGraph, current, me }) => {
            let nodes = []
            let edges = []

            let paths = [];

            if (workflow.paths) {
              for (let i = 0; i < workflow.paths.length; i++) {
                const subflow = workflow.paths[i];

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
              })
            }

            return { nodes, edges }
          }
        }
      ]
    }
  })
})();