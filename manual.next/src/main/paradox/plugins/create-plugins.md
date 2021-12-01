# Create plugins

To understand the content of a @ref:[plugin entity](../entities/plugins.md)

Otoroshi provides a list of predefined plugins, grouped by category. 
In some case, these plugins will not matched your needs.
The right way is to create your own plugin.

### Visualize the list of created plugins

Navigate to `<your-otoroshi-domain>/bo/dashboard/plugins`

### Create a plugin

Navigate to `<your-otoroshi-domain>/bo/dashboard/plugins` and click on the `Add item` button.

### Plugins assignement 

The supplied Otoroshi plugins and your custom plugins can be placed on any Otoroshi service and assigned to all services from the global zone.
Each plugin intervenes at a precise moment in the processing of a request.

### Compile a new plugin

The compilation from the UI of a custom plugin is only available in production mode. 

