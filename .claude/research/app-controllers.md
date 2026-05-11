# Directory `app/controllers/`

## Overview

This directory contains all **Play Framework HTTP controllers** that expose application endpoints: administration API, backoffice interface, authentication, and utility endpoints.

## Structure

```
controllers/
├── Auth0Controller.scala        # Authentication handling
├── BackOfficeController.scala   # Administration UI API
├── HealthController.scala       # Health endpoints
├── PrivateAppsController.scala  # Private apps authentication
├── SwaggerController.scala      # OpenAPI/Swagger exposure
├── U2FController.scala          # WebAuthn/FIDO2
└── adminapi/                    # Administration REST API (24 controllers)
```

## Main Controllers

### `BackOfficeController.scala` (~2500+ lines)
**Role**: Backend for the React administration interface.

**Main endpoints**:
- `GET /bo/dashboard` - Dashboard page
- `GET /bo/api/lines/:env` - Environments
- `GET /bo/api/globalconfig` - Global configuration
- `POST /bo/api/proxy/*` - Proxy for API calls from UI
- `POST /bo/api/search/services` - Service search
- `GET /bo/api/plugins` - Available plugins list
- `GET /bo/api/plugins/documentation` - Plugin documentation
- `POST /bo/api/toolbox/request` - Request testing tool
- `POST /bo/api/import` - Configuration import

**Features**:
- API request proxy with BackOffice authentication
- Entity search and filtering
- Configuration export/import
- Plugin documentation
- Real-time statistics and metrics

---

### `Auth0Controller.scala` (~800+ lines)
**Role**: User authentication handling (BackOffice and Private Apps).

**Endpoints**:
- `GET /backoffice/auth0/login` - Initiate OAuth login
- `GET /backoffice/auth0/logout` - Logout
- `GET /backoffice/auth0/callback` - OAuth callback
- `GET /privateapps/auth0/login` - Private apps login
- `GET /privateapps/auth0/logout` - Private apps logout
- `GET /privateapps/auth0/callback` - Private apps callback

**Features**:
- Multi-provider support (OAuth, OIDC, LDAP, SAML)
- Session management
- Post-login redirection

---

### `HealthController.scala` (~300 lines)
**Role**: Health check endpoints for monitoring and orchestration.

**Endpoints**:
- `GET /health` - Global health
- `GET /live` - Liveness probe (Kubernetes)
- `GET /ready` - Readiness probe (Kubernetes)
- `GET /startup` - Startup probe (Kubernetes)
- `GET /metrics` - Prometheus metrics

**Returned information**:
```json
{
  "otoroshi": "healthy|unhealthy|down",
  "datastore": "healthy|unhealthy|unreachable",
  "cluster": { "status": "healthy", "lastSync": "..." },
  "certificates": "loaded|loading",
  "scripts": "loaded|loading",
  "plugins": "loaded|loading"
}
```

---

### `PrivateAppsController.scala` (~400 lines)
**Role**: Session management for protected private applications.

**Endpoints**:
- `GET /privateapps/home` - Private apps home page
- `GET /privateapps/error` - Error page
- `GET /privateapps/logout` - Logout

---

### `SwaggerController.scala` (~100 lines)
**Role**: OpenAPI specification exposure.

**Endpoints**:
- `GET /api/swagger.json` - OpenAPI JSON spec
- `GET /api/swagger/ui` - Swagger UI
- `GET /api/openapi.json` - OpenAPI 3.0 spec

---

### `U2FController.scala` (~500 lines)
**Role**: WebAuthn/FIDO2 authentication (security keys).

**Endpoints**:
- `POST /bo/u2f/register/start` - Start registration
- `POST /bo/u2f/register/finish` - Finish registration
- `POST /bo/u2f/login/start` - Start authentication
- `POST /bo/u2f/login/finish` - Finish authentication
- `DELETE /bo/u2f/admins/:id` - Delete a U2F admin

---

## Subdirectory `adminapi/` (24 controllers)

These controllers implement the REST CRUD API for each entity. They use the traits:
- `CrudControllerHelper` - Standard CRUD operations
- `BulkControllerHelper` - Bulk operations (mass create/delete)
- `AdminApiHelper` - Common utilities

### Admin API Controllers

| Controller | Entity | Base Endpoints |
|------------|--------|----------------|
| `ServicesController` | ServiceDescriptor | `/api/services` |
| `ApiKeysController` | ApiKey | `/api/apikeys` |
| `CertificatesController` | Certificate | `/api/certificates` |
| `GlobalConfigController` | GlobalConfig | `/api/globalconfig` |
| `AuthModulesController` | AuthModuleConfig | `/api/auths` |
| `JwtVerifierController` | JwtVerifier | `/api/verifiers` |
| `DataExporterConfigController` | DataExporter | `/api/data-exporters` |
| `TenantsController` | Tenant | `/api/tenants` |
| `TeamsController` | Team | `/api/teams` |
| `ServiceGroupController` | ServiceGroup | `/api/groups` |
| `UsersController` | Admin/Users | `/api/admins`, `/api/users` |
| `ScriptApiController` | Script | `/api/scripts` |
| `TcpServiceApiController` | TcpService | `/api/tcp/services` |
| `TemplatesController` | Templates | `/api/templates` |
| `ErrorTemplatesController` | ErrorTemplate | `/api/error-templates` |
| `SnowMonkeyController` | ChaosConfig | `/api/snowmonkey` |
| `ClusterController` | Cluster | `/api/cluster` |
| `PkiController` | PKI | `/api/pki` |
| `AnalyticsController` | Analytics | `/api/events` |
| `StatsController` | Statistics | `/api/stats` |
| `EventsController` | Events | `/api/events/audit` |
| `ImportExportController` | Import/Export | `/api/import`, `/api/export` |
| `CanaryController` | Canary | `/api/canary` |
| `InfosController` | System info | `/api/infos` |

### Standard CRUD Pattern

Each controller exposes:
```
GET    /api/{entities}           # List all entities
POST   /api/{entities}           # Create an entity
GET    /api/{entities}/{id}      # Get an entity
PUT    /api/{entities}/{id}      # Update an entity
PATCH  /api/{entities}/{id}      # Partial patch (JSON Patch)
DELETE /api/{entities}/{id}      # Delete an entity
POST   /api/{entities}/_bulk     # Bulk operations
GET    /api/{entities}/_count    # Count entities
GET    /api/{entities}/_template # Template for new entity
```

---

## Security

### API Authentication
- `Otoroshi-Claim` header with signed JWT
- Contains the API Key `clientId`
- Validated by `ApiAction`

### BackOffice Authentication
- Session cookie `bousr`
- OAuth2/OIDC with automatic refresh
- Validated by `BackOfficeAction` / `BackOfficeActionAuth`

### Audit
All admin actions generate `AuditEvent`:
- Who did what
- When
- On which entity
- With what modifications

---

## Relationships with Other Components

```
controllers/
    │
    ├── Uses → actions/ (authentication)
    ├── Uses → models/ (entities)
    ├── Uses → storage/ (persistence)
    │
    ├── Generates → events/ (audit, alerts)
    └── Exposed via → conf/routes
```
