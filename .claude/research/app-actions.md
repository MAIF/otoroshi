# Directory `app/actions/`

## Overview

This directory contains Play Framework **Action Builders** - interceptors that run before controllers to handle authentication and authorization.

## Files

### `api.scala` (~480 lines)
**Role**: Authentication handling for the administration REST API.

**Main classes**:
- `ApiActionContext[A]` - Context passed to API controllers with ApiKey and request
- `ApiActionContextCapable` - Trait with utility methods for rights verification
- `ApiAction` - Main action builder for authenticated API endpoints
- `UnAuthApiAction` - Action builder for unauthenticated API endpoints

**Features**:
- API Key validation via `Otoroshi-Claim` header (signed JWT)
- Multi-tenant management via `Otoroshi-Tenant` header
- User rights verification (read/write) per entity
- BackOffice user support via JWT in `Otoroshi-BackOffice-User` header
- Entity validation with configurable JSON validators
- Utility methods: `canUserRead()`, `canUserWrite()`, `checkRights()`, `validateEntity()`

**API authentication flow**:
1. Verify host matches `adminApiHost`
2. Decode JWT from `Otoroshi-Claim` header
3. Extract `clientId` from `sub` claim (format: `apikey:clientId`)
4. Verify API key is authorized on backoffice
5. Pass context to controller

---

### `backoffice.scala` (~240 lines)
**Role**: Authentication handling for the administration interface (UI).

**Main classes**:
- `BackOfficeActionContext[A]` - Context with optional user (unauthenticated allowed)
- `BackOfficeActionContextAuth[A]` - Context with required user (authenticated required)
- `BackOfficeAction` - Action builder allowing unauthenticated requests
- `BackOfficeActionAuth` - Action builder requiring authentication

**Features**:
- User session via `bousr` cookie (session ID)
- User blacklist management (alert + logout)
- Automatic OAuth2 token refresh
- CSRF protection via origin verification
- API read-only mode (blocks modifications if enabled)
- Redirect to login if unauthenticated

**BackOffice authentication flow**:
1. Verify host matches `backOfficeHost`
2. Get session ID from `bousr` cookie
3. Load `BackOfficeUser` from datastore
4. Check if user is not blacklisted
5. Refresh OAuth2 token if needed
6. Pass context to controller

---

### `privateapps.scala` (~105 lines)
**Role**: Authentication handling for private applications protected by Otoroshi.

**Main classes**:
- `PrivateAppsActionContext[A]` - Context with list of logged-in users
- `PrivateAppsAction` - Action builder for private apps

**Features**:
- Multi-session support (multiple `oto-papps-*` cookies)
- Session validation in cluster mode (worker verifies with leader)
- Automatic OAuth2 token refresh
- Automatic cleanup of invalid cookies
- Session expiration management

**Private Apps authentication flow**:
1. Verify host matches `privateAppsHost`
2. Collect all `oto-papps-*` cookies
3. Validate each session (local or via cluster)
4. Refresh OAuth2 tokens if needed
5. Pass list of valid users to controller

---

## Relationships with Other Components

```
actions/
    │
    ├── Used by → controllers/adminapi/*.scala (REST API)
    ├── Used by → controllers/BackOfficeController.scala (Admin UI)
    ├── Used by → controllers/PrivateAppsController.scala (Private Apps)
    │
    ├── Depends on → models/BackOfficeUser, ApiKey, PrivateAppsUser
    ├── Depends on → storage/ (datastores for sessions)
    ├── Depends on → auth/ (GenericOauth2Module for token refresh)
    └── Depends on → cluster/ (session validation in worker mode)
```

## Key Points

1. **Context separation**: 3 distinct authentication types (API, BackOffice, PrivateApps)
2. **Multi-tenant**: Native multi-tenant support via headers
3. **RBAC**: Fine-grained rights verification (read/write per tenant/team)
4. **Cluster-aware**: Sessions can be validated via cluster leader
5. **Token refresh**: Automatic OAuth2 token refresh management
