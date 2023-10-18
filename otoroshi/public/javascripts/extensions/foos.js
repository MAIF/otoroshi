(function() {
  const extensionId = "otoroshi.extensions.Foo";
  Otoroshi.registerExtension(extensionId, (ctx) => {

    const dependencies = ctx.dependencies;  
  
    const React     = dependencies.react;
    const Component = React.Component;
    const uuid      = dependencies.uuid;
    const Table     = dependencies.Components.Inputs.Table;
    const BackOfficeServices     = dependencies.BackOfficeServices;

    class FooTab extends Component {

      componentDidMount() {
        const { FeedbackButton, setSaveButton, isCreation } = this.props.settings;
        setSaveButton(
          React.createElement(FeedbackButton, {
            className: "ms-2 mb-1",
            onPress: () => console.log("onPress"),
            text: isCreation ? `Create route` : `Save route`,
            icon: () =>  React.createElement('i', { className: "fas fa-paper-plane" }),
          })
        );
      }

      render() {
        return (
          React.createElement('div', null, React.createElement('h1', { style: { color: 'white' }}, 'Tab extension !'))
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
              defaultValue: () => ({ id: 'foo_' + uuid(), name: 'Foo', description: 'New foo', tags: [], metadata: {}}),
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
          render: (settings) =>  React.createElement(FooTab, { settings }),
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
      ]
    }
  });
})();