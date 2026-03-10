# Otoroshi Admins

Otoroshi Admins are the users that can access the Otoroshi admin backoffice. There are two types of admins: **Simple admins** that authenticate with username and password, and **WebAuthn admins** that use WebAuthn/FIDO2 hardware security keys for authentication.

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

## API

The admin API endpoints are:

```
http://otoroshi-api.oto.tools:8080/api/admins/simple
http://otoroshi-api.oto.tools:8080/api/admins/webauthn
```
