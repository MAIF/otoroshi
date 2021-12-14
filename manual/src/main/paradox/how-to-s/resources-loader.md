# The resources loader

The resources loader is a tool to create an Otoroshi resource from a raw content. This content can be found on each Otoroshi resources pages (services descriptors, apikeys, certificates, etc ...). To get the content of a resource as file, you can use the two export buttons, one to export as JSON format and the other as YAML format.

Once exported, the content of the resource can be import with the resource loader. You can import single or multiples resources on one time, as JSON and YAML format.

The resource loader is available on this route [`bo/dashboard/resources-loader`](http://otoroshi.oto.tools:8080/bo/dashboard/resources-loader).

On this page, you can paste the content of your resources and click on **Load resources**.

For each detected resource, the loader will display :

* a resource name corresponding to the field `name` 
* a resource type corresponding to the type of created resource (ServiceDescriptor, ApiKey, Certificate, etc)
* a toggle to choose if you want to include the element for the creation step
* the updated status by the creation process

Once you have selected the resources to create, you can **Import selected resources**.

Once generated, all status will be updated. If all is working, the status will be equals to done.

If you want to get back to the initial page, you can use the **restart** button.