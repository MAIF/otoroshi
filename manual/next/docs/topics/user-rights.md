---
title: Otoroshi user rights
sidebar_position: 28
---
# Otoroshi user rights

In Otoroshi, all users are considered **Administrators**. This choice is reinforced by the fact that Otoroshi is designed to be an administrator user interface and not an interface for users who simply want to view information. For this type of use, we encourage to use the admin API rather than giving access to the user interface.

The Otoroshi rights are split by a list of authorizations on **organizations** and **teams**. 

Let's taking an example where we want to authorize an administrator user on all organizations and teams.

The list of rights will be :

```json
[
  {
    "tenant": "*:rw", # (1)
    "teams": ["*:rw"] # (2)
  }
]
```

* (1): this field, separated by a colon, indicates the name of the tenant and the associated rights. In our case, we set `*` to apply the rights to all tenants, and the `rw` to get the read and write access on them.
* (2): the `teams` array field, represents the list of rights, applied by team. The behaviour is the same as the tenant field, we define the team or the wildcard, followed by the rights

if you want to have an user that is administrator only for one organization, the rights will be :

```json
[
  {
    "tenant": "orga-1:rw",
    "teams": ["*:rw"]
  }
]
```

if you want to have an user that is administrator only for two organization, the rights will be :

```json
[
  {
    "tenant": "orga-1:rw",
    "teams": ["*:rw"]
  },
  {
    "tenant": "orga-2:rw",
    "teams": ["*:rw"]
  }
]
```

if you want to have an user that can only see 3 teams of one organization and one team in the other, the rights will be :

```json
[
  {
    "tenant": "orga-1:rw",
    "teams": [
      "team-1:rw",
      "team-2:rw",
      "team-3:rw",
    ]
  },
  {
    "tenant": "orga-2:rw",
    "teams": [
      "team-4:rw"
    ]
  }
]
```

The list of possible rights for an organization or a team is:

* **r**: read access
* **w**: write access
* **not**: none access to the resource

The list of possible tenant and teams are your created tenants and teams, and the wildcard to define rights to all resources once.

The user rights is defined by the [authentication modules](../entities/auth-modules.md), and can be defined for every auth module at once with a [user rights policy](#user-rights-policies).

## Rights limited to some kinds of entities

A right can be limited to some kinds of entities with `authorized_entities_read` and `authorized_entities_write`. For instance, a user that can read everything in the `ops` team of `orga-1`, but can only write the backends and the certificates there:

```json
[
  {
    "tenant": "orga-1:rw",
    "teams": ["ops:rw"],
    "authorized_entities_read": ["*"],
    "authorized_entities_write": ["backends", "certificates"]
  }
]
```

A kind of entity is written

* `*`: every kind of entity
* `<plural>`: the kind of entity of this plural name, in any group, like `backends`
* `<group>/<plural>`: one kind of entity, like `proxy.otoroshi.io/backends`
* `<group>/*`: every kind of entity of a group, like `pki.otoroshi.io/*`

The plural names and the groups are the ones of the [generic admin api](./generic-resource-api.md) (`GET /apis/entities` lists them).

* the kinds a right can write are readable too
* a right without `authorized_entities_read` nor `authorized_entities_write` gives access to every kind of entity, like it always did. A right with only one of them gives access to nothing more than this list: `{ "authorized_entities_read": ["routes"] }` reads the routes and writes nothing
* the organization, one of the teams and the kind of entity have to be allowed by the same right
* a right limited to some kinds never makes a super admin nor an organization admin, even on `*:rw` / `*:rw`: the actions reserved to them (global config, admins, sessions, ...) stay out of reach
* the tools of the backoffice that make Otoroshi call a url given by the user need the right to write the kind of entity they serve: reading the configuration of an OIDC or SAML provider and testing an LDAP connection need `auth-modules`, the Elasticsearch tools need `data-exporters`, the GraphQL composer needs `routes`, the Eureka servers `routes` or `backends`, the sources of WASM files `wasm-plugins` or `routes`

## User rights policies

A user rights policy defines the rights of a backoffice user, by email. The policies are managed by the super admins in the backoffice (**User rights policies** in the menu), or with the admin api (`security.otoroshi.io/v1/user-rights-policies`).

```json
{
  "email": "jane.doe@oto.tools",
  "name": "Jane Doe rights",
  "mode": "replace",
  "auth_module_ref": null,
  "rights": [
    {
      "tenant": "orga-1:rw",
      "teams": ["ops:rw"],
      "authorized_entities_read": ["*"],
      "authorized_entities_write": ["backends", "certificates"]
    }
  ]
}
```

The `mode` of the policy tells how it combines with the rights the auth module gives the user:

* `replace` (the default): the rights of the policy are the rights of the users of its auth module, the ones the module gives them are ignored
* `restrict`: the user keeps the rights of its auth module (its `rightsOverride`, the rights read from its profile, ...), capped by the rights of the policy. The auth module cannot give what the policy keeps away. When a pattern of the auth module and a pattern of the policy overlap without one covering the other (`team-a-*` and `team-*-b`), the access is dropped

### An email is not an identity

Several users can log in to the backoffice with the same email: a user of the backoffice auth module, a local admin (simple or webauthn) whose username is this email, and the users of the auth modules used as backoffice auth module before. The username of a local admin is chosen by whoever creates it, a tenant admin included. A policy giving its rights to all of them would let a tenant admin create a local admin named after the email of a super admin, and log in with the rights of its policy.

So a policy only **gives** its rights to the users of one auth module: the one of `auth_module_ref`, or, when it is empty, the backoffice auth module of the moment (`backOfficeAuthRef` in the global config). Every other user with the same email, the local admins included, keeps its own rights, **capped** by the rights of the policy, whatever the mode: a policy never gives a user more than it already has, unless the user comes from the auth module of the policy. Without a backoffice auth module nor `auth_module_ref`, a policy only restricts.

The rights of the local admins are written on the admins themselves (**Admins** in the menu), rights limited to some kinds of entities included: they do not need a policy.

:::warning
The policies trust the emails given by their auth module. Whoever can write this auth module (its `rightsOverride`, the users of a basic auth module, its identity provider, ...) decides who logs in with which email, and so who gets the rights of the policies. Locate the backoffice auth module, and every auth module named in an `auth_module_ref`, in an organization only the super admins can write. An entity located on every team (`*`) can be written by anyone writing one of the teams of its organization, not only by the tenant admins: the default location (organization `default`, every team) does not protect the module as soon as a user can write a team of `default`.
:::

The rights of a user are resolved on each of its calls: a policy written, changed or deleted takes effect on the next call of the user, without a new login (on the other nodes of a cluster, after their next state sync, every 10 seconds by default).

* only the super admins read and write the policies (a policy can take rights away from anyone, a super admin included), and they cannot write a policy giving more rights than they have
* a policy is identified by the email of its user, compared without case: a user has one policy at most

The policies can also be declared as Kubernetes resources with the `UserRightsPolicy` CRD (see [Kubernetes](../deploy/kubernetes.mdx)). Anyone allowed to write these resources in the cluster then decides of the rights of the Otoroshi users: give the write access to `user-rights-policies.proxy.otoroshi.io` with care.

```yaml
apiVersion: proxy.otoroshi.io/v1
kind: UserRightsPolicy
metadata:
  name: jane-doe
spec:
  email: jane.doe@oto.tools
  mode: replace
  auth_module_ref: null
  rights:
    - tenant: "orga-1:rw"
      teams: ["ops:rw"]
      authorized_entities_read: ["*"]
      authorized_entities_write: ["backends", "certificates"]
```

## Personal admin apikey

A backoffice user can get its own admin apikey, even when it cannot write apikeys: **My admin apikey** in the menu of the backoffice, or the endpoints of the backoffice

* `GET /bo/api/me/admin-apikey`: the apikey of the user, if any, and the rights it has right now
* `POST /bo/api/me/admin-apikey`: creates the apikey, or answers the one the user already has. A user has one personal admin apikey at most
* `POST /bo/api/me/admin-apikey/_rotate`: a new secret, the same client id
* `DELETE /bo/api/me/admin-apikey`: deletes the apikey

The rights of the apikey are not written in it: they are the rights a session of its owner would have, read on each call.

* a **local admin** gets an apikey with its own rights, capped by its rights policy if it has one. Changing the rights of the admin takes effect on the next call of the apikey. The apikey belongs to this very admin: once the admin is deleted, the apikey cannot do anything, even if an admin is created again with the same username
* a **user of an auth module** gets an apikey only with a rights policy giving it rights (`replace` mode, for this auth module): the rights the auth module gives at login are not stored anywhere, and a call made with an apikey has no login. The apikey has the rights of the policy. Changing or deleting the policy takes effect on the next call of the apikey, and an apikey whose owner has no such policy anymore cannot do anything

The owner of an apikey is an email given by a source: a local admin and a user of an auth module with the same email have two different apikeys, and do not see the apikey of one another.

The secret of a personal admin apikey gives the rights of its owner, today's and tomorrow's: only its owner and the super admins can read it, and only the super admins can write it through the admin api. An apikey bypasses the second factor of its owner (the webauthn device of a local admin, the MFA of an identity provider): rotate its secret, or delete it, when it may have leaked.

## Otoroshi Admin. API apikey rights

If you want to restrict the scope of an Otoroshi Admin. API apikey, you can add a metadata named `otoroshi-access-rights` on it  containing an array of user rights like

```json
{
  ...
  "metadata": {
    ...
    "otoroshi-access-rights": "[{\"tenant\":\"orga-2:rw\",\"teams\":[\"team-4:rw\"]}]"
  }
}
```

### Who reads the secret of an apikey

The secret of an apikey that gives access to the admin api is a credential with the rights of that apikey: reading it is getting those rights. So a caller sees it only if it could have granted that apikey, the same rule as writing one:

* a super admin, and an admin api apikey without `otoroshi-access-rights`, see every secret
* an admin api apikey (authorized on the admin api group) is seen whole by the callers whose rights cover the rights of its `otoroshi-access-rights`. One without `otoroshi-access-rights`, which has no restriction at all, is seen by the super admins only
* a personal admin apikey is seen by its owner and the super admins
* any other apikey, the one of a consumer, is seen by whoever can read it

The others still read the apikey (it stays in the lists, with its rights, metadata and quotas), with `********` in place of its `clientSecret`, of its `bearer` and of the `nextSecret` and `bearer` of its rotation, and `GET /api/apikeys/:id/bearer` answers `403`. That applies to every read of the admin api (`/api/apikeys`, `/apis/apim.otoroshi.io/v1/apikeys`, the apikeys of a group, of a route, of an api) and of the backoffice. An apikey written back with `********` as its secret keeps the secret it has.
