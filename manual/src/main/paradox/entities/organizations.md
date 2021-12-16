# Organizations

The resources of Otoroshi are grouped by `Organization`. This the highest level for grouping resources.

An organization have a unique `id`, a `name` and a `description`. As all Otoroshi resources, an Organization have a list of tags and metadata associated.

For example, you can use the organizations as a mean of :

* to seperate resources by services or entities in your enterprise
* to split internal and external usage of the resources (it's useful when you have a list of services deployed in your company and another one deployed by your partners)

@@@ div { .centered-img }
<img src="../imgs/organizations-and-teams.png" />
@@@

## Access to the list of organizations

To visualize and edit the list of organizations, you can navigate to your instance on the `https://otoroshi.xxxxxx/bo/dashboard/organizations` route or click on the cog icon and select the organizations button.

Once on the page, you can create a new item, edit an existing organization or delete an existing one.

> When an organization is deleted, the resources associated are not deleted. On the other hand, the organization and the team of associated resources are let empty.

## Entities location

Any otoroshi entity has a location property (`_loc` when serialized to json) explaining where and by whom the entity can be seen. 

An entity can be part of one organization (`tenant` in the json document)

```javascript
{
  "_loc": {
    "tenant": "tenant-1",
    "teams": ...
  }
  ...
}
```

or all organizations

```javascript
{
  "_loc": {
    "tenant": "*",
    "teams": ...
  }
  ...
}
```

