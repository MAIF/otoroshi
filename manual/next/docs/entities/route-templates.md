---
title: Route Templates
sidebar_position: 15
---
# Route Templates

## What are route templates?

In a typical Otoroshi deployment, many routes share the same foundational configuration: the same authentication plugin, the same rate-limiting policy, the same CORS headers, the same logging setup. Without a mechanism to capture those shared defaults, every new route must be configured from scratch, which is both tedious and error-prone. Route templates solve this problem by letting you define **reusable, preconfigured route blueprints** that serve as starting points when creating new routes.

A route template is essentially a **pre-filled route configuration**. It contains a complete route definition -- frontend, backend, plugins, and all associated settings -- but it is **not a live route**. A template does not match any domain, does not serve traffic, and does not appear in the proxy engine's routing table. Think of it as a blueprint: when you create a new route from a template, Otoroshi copies the template's configuration into a real route that you can then customize for the specific service you are exposing.

### The philosophy: convention over configuration

Route templates embody the principle of **convention over configuration**. Instead of requiring every team to remember (and correctly apply) the full set of organizational standards each time they create a route, you define those standards once inside a template. Teams then only need to override what is specific to their service -- typically the target hostname and the frontend domain -- while everything else is inherited from the template.

This approach has several practical benefits:

- **Enforcing organizational standards** -- Every new route automatically starts with the plugins and policies your platform team has approved (rate limiting, logging, authentication, CORS, and so on). There is no risk of forgetting a critical security plugin.
- **Speeding up service onboarding** -- New teams or new microservices can go from zero to a fully configured, production-ready route in seconds rather than minutes.
- **Reducing configuration drift** -- Because all routes originate from a known-good template, the overall configuration across your fleet stays consistent and auditable.
- **Supporting environment-specific defaults** -- You can maintain separate templates for development, staging, and production, each with the appropriate backend targets, timeout values, and observability settings.

### How templates differ from routes

It is important to understand that a template and a route are distinct entities, even though a template contains a full route definition internally. A route is an active proxy rule that Otoroshi evaluates on every incoming request. A template is an inert configuration object stored alongside your other entities. Creating, updating, or deleting a template has no effect on live traffic. The template only comes into play at route-creation time, when its embedded route definition is used to pre-populate the new route's fields.

## UI page

You can find all route templates [here](http://otoroshi.oto.tools:8080/bo/dashboard/route-templates)

## Properties

| Property | Type | Description |
|----------|------|-------------|
| `id` | string | Unique identifier of the route template |
| `name` | string | Display name of the template |
| `description` | string | Description of what this template provides |
| `tags` | array of string | Tags for categorization |
| `metadata` | object | Key/value metadata |
| `route` | object | A full [route](./routes.md) definition used as the template content |

The `route` property contains a complete NgRoute definition including frontend, backend, and plugins configuration. When a new route is created from this template, the route definition is used as the initial configuration that can then be customized.

## Default template

Otoroshi can have a global default route template configured in the [Global Config](./global-config.md) (under `templates.routeTemplate`). When set, all new routes will be initialized from this template unless another template is explicitly selected.

## JSON example

```json
{
  "id": "route-template_secured_api",
  "name": "Secured API template",
  "description": "Template for secured API routes with API key authentication and rate limiting",
  "tags": ["security", "api"],
  "metadata": {
    "team": "platform"
  },
  "route": {
    "id": "tpl-route",
    "name": "Template route",
    "description": "A secured route template",
    "tags": [],
    "metadata": {},
    "enabled": true,
    "debug_flow": false,
    "capture": false,
    "export_reporting": false,
    "groups": ["default"],
    "bound_listeners": [],
    "frontend": {
      "domains": ["new-api.oto.tools"],
      "strip_path": true,
      "exact": false,
      "headers": {},
      "query": {},
      "methods": []
    },
    "backend": {
      "targets": [
        {
          "id": "target_1",
          "hostname": "changeme.internal",
          "port": 8080,
          "tls": false,
          "weight": 1
        }
      ],
      "root": "/",
      "rewrite": false,
      "load_balancing": { "type": "RoundRobin" }
    },
    "plugins": {
      "slots": [
        {
          "plugin": "cp:otoroshi.next.plugins.OverrideHost",
          "enabled": true,
          "include": [],
          "exclude": [],
          "config": {}
        },
        {
          "plugin": "cp:otoroshi.next.plugins.ApikeyCalls",
          "enabled": true,
          "include": [],
          "exclude": [],
          "config": {}
        }
      ]
    }
  }
}
```

## Use cases

* **Standardized security**: Create templates that enforce your organization's security policies (authentication, rate limiting, CORS) so all new routes start with the correct configuration
* **Environment-specific defaults**: Create templates per environment (dev, staging, production) with appropriate backends, timeouts, and logging levels
* **Team onboarding**: Provide ready-made templates so new team members can quickly create properly configured routes
* **Microservice patterns**: Define templates for common patterns like BFF (Backend for Frontend), API gateway, or gRPC proxy

## Admin API

```
GET    /api/route-templates           # List all route templates
POST   /api/route-templates           # Create a new route template
GET    /api/route-templates/:id       # Get a route template
PUT    /api/route-templates/:id       # Update a route template
DELETE /api/route-templates/:id       # Delete a route template
PATCH  /api/route-templates/:id       # Partially update a route template
```
