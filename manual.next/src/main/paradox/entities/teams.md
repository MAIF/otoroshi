# Teams

In Otoroshi, all resources are attached to an `Organization` and a `Team`. 

A team is composed of an unique `id`, a `name`, a `description` and an `Organization`. As all Otoroshi resources, a Team have a list of tags and metadata associated.

A team have an unique organization and can be use on multiples resources (services, api keys, etc ...).

A connected user on Otoroshi UI has a list of teams and organizations associated. It can be helpful when you want restrict the rights of a connected user.

@@@ div { .centered-img }
<img src="../imgs/organizations-and-teams.png" />
@@@

## Access to the list of teams

To visualize and edit the list of teams, you can navigate to your instance on the `/bo/dashboard/teams` route or click on the cog icon and select the teams button.

Once on the page, you can create a new item, edit an existing team or delete an existing one.

> When a team is deleted, the resources associated are not deleted. On the other hand, the team of associated resources is let empty.

## Entities location

any otoroshi entity has a location property (`_loc` when serialized to json) explaining where and by whom the entity can be seen. 

An entity can be part of multiple teams in an organization

```javascript
{
  "_loc": {
    "tenant": "tenant-1",
    "teams": [
      "team-1",
      "team-2"
    ]
  }
  ...
}
```

or all teams

```javascript
{
  "_loc": {
    "tenant": "tenant-1",
    "teams": [
      "*"
    ]
  }
  ...
}
```