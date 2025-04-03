# Apis

<div style="display: flex; align-items: center; gap: .5rem;">
<a class="badge" href="https://maif.github.io/otoroshi/manual/plugins/built-in-plugins.html#otoroshi.next.plugins.ApikeyCalls">ALPHA</a>
</div>

Otoroshi now introduces a new core entity: APIs. This feature marks a major step towards a more API Management–oriented experience by allowing users to manage their APIs as a whole, rather than configuring them route by route. 

With the API entity, you can define reusable [backends](#backends), declare multiple plugin [flows](#flows) that can be applied across different [routes](#routes) manage [consumers](#consumers) and [subscriptions](#subscriptions), and track the state of an API throughout its lifecycle. 

This evolution unlocks powerful capabilities for building developer portals and brings Otoroshi closer to the expectations of a full-featured API Management platform.

@@@ div { .centered-img }
<img src="../imgs/apis-home.png" />
@@@


## Routes

An API is composed of multiples routes. It is the same entity as the @ref[Routes](./routes.md) entity in Otoroshi. You can think of Routes as the complete description of your API in terms of HTTP. 

The routes allow you to describe your entire API, including the available endpoints (/users) and operations on each endpoint (GET /users, POST /users).

A route is composed of a frontend, describing the entry point of the route (which can be an exact domain or an domain plus path), a [backend](#backends), a name and [flow](#flows) of plugins.

A route can be enabled or disabled independently of the API's activation.

## Backends

A backend represents a list of servers to target in a route, along with its client settings, load balancing, etc.

The backends can be define directly on the API editor or on their dedicated page in order to be reusable.

## Flows

A flow is a collection of plugins. Plugins can be applied between the frontend and the backend, and vice versa, from the backend to the client.

You can check the list of plugins @ref[here](../plugins/built-in-plugins.md)

## Consumers

Consumers apply security patterns to specific flows. Both consumers and subscriptions are the new way to track consumption of your API.

Otoroshi supports the following type of consumers:


- *KEYLESS*: specifies a flow without any restrictions.
- *APIKEY*: applies the `Apikeys` plugin to flows to enforce the Api key requirements on routes
- *OAUTH*: applies the `Client credentials endpoint` to flows. It is useful in machine-to-machine (M2M) applications, where the system authenticates and authorizes the app rather than a user, allowing it to obtain an API key for your application and call others routes/apis with retrieved JWT (@ref[tutorial](../how-to-s/secure-with-oauth2-client-credentials.md)) 
- *MTLS*: specifies a flow with a list of accepted TLS certificates
- *JWT*: applies `JWT verification only` plugin to flows and enforces calls with JWT and the expected signature only.

## Subscriptions

The end-user, who could be a developer portal, an human or a machine, can subscribe to your published
consumers.

A subscription contains information about the end-user : description and generated tokens (API key, certificate, jwt, etc). Subscriptions and consumers have statuses, which can be in `staging`, `published`, `deprecated` and `closed` states.

