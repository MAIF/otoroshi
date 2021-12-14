# Service groups

A service group is composed of an unique `id`, a `Group name`, a `Group description`, an `Organization` and a `Team`. As all Otoroshi resources, a service group have a list of tags and metadata associated.

@@@ div { .centered-img }
<img src="../imgs/models-group.png" />
@@@

The first instinctive usage of service group is to group a list of services. 

When it's done, you can authorize an api key on a specific group. Instead of authorize an api key for each service, you can regroup a list of services together, and give authorization on the group (read the page on the api keys and the usage of the `Authorized on.` field).

## Access to the list of service groups

To visualize and edit the list of groups, you can navigate to your instance on the `https://otoroshi.xxxxx/bo/dashboard/groups` route or click on the cog icon and select the Service groups button.

Once on the page, you can create a new item, edit an existing service group or delete an existing one.

> When a service group is deleted, the resources associated are not deleted. On the other hand, the service group of associated resources is let empty.

