---
title: Route Templates
sidebar_position: 15
---
# Route Templates

Route templates allow you to define reusable, preconfigured route blueprints that can be used as a starting point when creating new routes. Instead of configuring every route from scratch, you can create templates with predefined frontends, backends, plugins, and settings, then instantiate them to quickly spin up new routes.

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
