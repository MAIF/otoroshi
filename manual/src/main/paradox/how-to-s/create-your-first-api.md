# Create your first API

The API let's you define reusable backends, declare multiple plugin flows that can be applied across different routes manage consumers and subscriptions, and track the state of an API throughout its lifecycle.

### Before you start

@@include[initialize.md](../includes/initialize.md) { #initialize-otoroshi }

### How to build an API based on an existing one

To start this tutorial, we'll use Otoroshi's UI.

1. Go to [`/bo/dashboard/apis`](http://otoroshi.oto.tools:8080/bo/dashboard/apis) on your instance.  
2. Click the `Create new API` button.  
3. Select `Import OpenAPI`.  
4. The default parameters target the Petstore API. 
5. Click the `Read information from OpenAPI URL` button. It will load the information about the target server.
6. Once done, apply the changes (and don’t forget the trailing slash in the root URL):

```yaml
    hostname: petstore3.swagger.io
    root URL: /api/v3/
```
7\. Create your API by clicking the `Create` button.  

You should see something like the following **Dashboard**:

@@@ div { .centered-img }
<img src="../imgs/petstore-api.png" />
@@@


@@@ note
The Petstore API has been loaded from the OpenAPI specification. A match was made between the OpenAPI format and Otoroshi.

Each OpenAPI path is converted into a frontend in Otoroshi. For each of them, Otoroshi preserves the HTTP method, path, description, and name.

To complete the setup, Otoroshi creates a single backend instance that is shared by all frontends, avoiding duplication.

This backend contains information about the target server, such as the hostname, port, and other connection details.

All routes are initialized with a default flow of plugins. By default, this flow is empty, so no transformation or filtering is applied to requests (for now).
@@@

We have a new API, but for now, we can't call it yet. 

We need to enable **Testing** mode on the API first. Later, we'll deploy it to production.

@@@note
To help you, there's a list of available tasks at the top of the dashboard. These tasks are simple and guide you toward a secure and well-understood production setup.
@@@

### Testing your API

Click the `Test your API` task on the top of the screen and enabled the mode by toggling the button.

Once done, copy the following `curl` ( **dont' forget to replace the X-OTOROSHI-TESTING value with your own** ):

```sh
 curl -X GET 'http://petstore.oto.tools:8080/pet/findByStatus\?status\=available' -H 'X-OTOROSHI-TESTING: 3c7c7a677-3bc0-4005-b404-a1e699582841'
```

### Secure the API with API keys

By default, no transformation, filtering or security are applied : it is called `keyless` in Otoroshi.

On your API, go to the **consumers** tab (you will find it in the sidebar). You should see the default and used consumer by your routes, called `keyless_consumer`.

Let's no touch to the defaults, and starting create a new one by clicking the `Create new consumer` button.

Replace the information with the following:

```yaml
    Name: API Key consumer
    Consumer Kind: apikey
    Status: published
```

We have to tell to our routes to used the new consumer. To avoid to apply this flow on all Petstore routes, let's just protect the previous one `findByStatus` route.

1. Go to the `Flows` tab
1. Create a new flow called `Secure flow by apikeys`
1. Enable the API Key consumer and save
1. Go to the `Routes` tab
1. Search the status route using the search name input
1. Change the flow by clicking the `Add plugins to your route by selecting a flow` button