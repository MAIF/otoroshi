---
title: Otoroshi Admins
sidebar_position: 13
---
# Otoroshi Admins

## Overview

Otoroshi Admins are the accounts that control who can access the Otoroshi admin backoffice and what they are allowed to do once inside. Because the backoffice exposes every aspect of gateway configuration -- routes, API keys, certificates, global settings, and more -- it is critical to restrict access to trusted operators and to limit each operator to only the resources they need.

It is important to distinguish Otoroshi admins from **auth module users**. Auth modules (OAuth2, OIDC, LDAP, SAML, etc.) authenticate end-users who access applications protected by Otoroshi, known as "private apps". Otoroshi admins, on the other hand, are specifically the operators who log into the backoffice to configure and manage the gateway itself.

### Two authentication methods

Otoroshi supports two types of admin accounts:

- **Simple admins** authenticate with a username (email) and a bcrypt-hashed password. This is the default and most straightforward method.
- **WebAuthn admins** authenticate using FIDO2/WebAuthn hardware security keys (such as a YubiKey). This provides phishing-resistant, passwordless authentication for environments where stronger security guarantees are required.

### Fine-grained access control (RBAC)

Every admin account carries a `rights` definition that scopes its access by **tenant** (organization) and **team**, with independent read and write permissions for each. An admin whose rights grant wildcard access (`*`) to all tenants and all teams with full read/write is considered a **super admin** with unrestricted control. Any other configuration produces a scoped admin who can only view or modify resources within the tenants and teams explicitly granted to them.

Additionally, each admin can have **entity validators** -- rules that constrain which values an admin may set when creating or modifying specific entity types. This allows, for example, enforcing that a particular admin can only create routes tagged with a certain label or belonging to a certain metadata scope.

### Security philosophy

The admin system follows a security-first approach: passwords are always stored as bcrypt hashes, modern hardware-based authentication is a first-class option, and the RBAC model defaults to least privilege. Together, these features ensure that backoffice access can be tightly controlled in both small single-team deployments and large multi-tenant environments.

## UI page

You can find all admins [here](http://otoroshi.oto.tools:8080/bo/dashboard/admins)

## Common properties

Both admin types share the following properties:

* `username`: the email address used as the admin identifier
* `password`: the bcrypt-hashed password of the admin
* `label`: a display label for the admin
* `createdAt`: the creation timestamp
* `type`: the type of admin (`SIMPLE` or `WEBAUTHN`)
* `tags`: list of tags associated to the admin
* `metadata`: list of metadata associated to the admin
* `rights`: the access rights of the admin (see below)
* `adminEntityValidators`: a map of entity-type to validation rules that restricts which entities this admin can create or modify

## Admin types

### Simple admin

A Simple admin authenticates using a username (email) and a password. This is the default and most common admin type.

### WebAuthn admin

A WebAuthn admin authenticates using a FIDO2/WebAuthn hardware security key (such as a YubiKey). In addition to the common properties, a WebAuthn admin has:

* `handle`: the WebAuthn handle identifier
* `credentials`: a map of registered WebAuthn credentials (key id to credential data)

## Rights

The `rights` field controls what the admin can access. It is a list of `UserRight` entries, each defining access to a tenant and its teams:

```json
{
  "rights": [
    {
      "tenant": {
        "value": "*",
        "canRead": true,
        "canWrite": true
      },
      "teams": [
        {
          "value": "*",
          "canRead": true,
          "canWrite": true
        }
      ]
    }
  ]
}
```

* `tenant.value`: the tenant identifier (`*` for all tenants)
* `tenant.canRead`: read access to the tenant
* `tenant.canWrite`: write access to the tenant
* `teams[].value`: the team identifier (`*` for all teams)
* `teams[].canRead`: read access to the team
* `teams[].canWrite`: write access to the team

An admin with `tenant.value = "*"` and full read/write on all teams is a **super admin** with unrestricted access.

## Entity validators

The `adminEntityValidators` field allows restricting the values an admin can set when creating or modifying entities. It is a map where keys are entity types and values are lists of JSON validation rules. This is useful to enforce that certain admins can only create entities with specific metadata, tags, or other field constraints.

## Admin API

```
GET    /api/admins/simple            # List all simple admins
POST   /api/admins/simple            # Create a simple admin
GET    /api/admins/simple/:id        # Get a simple admin
PUT    /api/admins/simple/:id        # Update a simple admin
DELETE /api/admins/simple/:id        # Delete a simple admin

GET    /api/admins/webauthn          # List all WebAuthn admins
POST   /api/admins/webauthn          # Create a WebAuthn admin
GET    /api/admins/webauthn/:id      # Get a WebAuthn admin
PUT    /api/admins/webauthn/:id      # Update a WebAuthn admin
DELETE /api/admins/webauthn/:id      # Delete a WebAuthn admin
```
