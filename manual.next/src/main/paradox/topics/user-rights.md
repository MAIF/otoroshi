# Otoroshi user rights

In Otoroshi, all users are considered **Administrators**. This choice is reinforced by the fact that Otoroshi is designed to be an administrative user interface and not an interface for users who simply want to view information. For this type of use, we encourage to use the admin API rather than giving access to the user interface.

The Otoroshi rights are split by a list of authorizations on **tenants** and **teams**. 

Let's taking an example where we want to authorize an administrator user on all tenants and teams.

The list of rights will be:
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


The list of possible rights is:

* **r**: read access
* **w**: write access
* **not**: none access to the resource

The list of possible tenant and teams are your created tenants and teams, and the wildcard to define rights to all resources once.

The user rights is defined by the @ref:[authentication modules](../entities/auth-modules.md).
