# Managing service groups

Go to `settings (cog icon) / All service groups` to access the list of service groups.

@@@ div { .centered-img }
<img src="../img/settings-menu-groups.png" />
@@@

And you should see the list of existing `Service groups`.

@@@ div { .centered-img }
<img src="../img/service-groups.png" />
@@@

But what is a `Service group` anyway ?

## Otoroshi entities

There are 3 major entities at the core of Otoroshi :

* **service groups**
* service descriptors
* api keys

@@@ div { .centered-img }
<img src="../img/models-group.png" />
@@@

A `service group` is just some kind of logical container for `service descriptors`. A `service group` also has some `api keys` assigned that will be used to access all the `service descriptors` contained in the `service group`.

## Create a service group

A `service group` is a really simple structure with an `id`, a name and a description. To create a new one, just click on the `Add item` button.

@@@ div { .centered-img }
<img src="../img/service-groups-add.png" />
@@@

modify the name and the description of the group

@@@ div { .centered-img }
<img src="../img/service-groups-new.png" />
@@@

and click on `Create group`

@@@ div { .centered-img }
<img src="../img/service-groups-create.png" />
@@@

Then, you should find your brand new `Service group` in the list of `Service groups`

@@@ div { .centered-img }
<img src="../img/service-groups-created.png" />
@@@

## Update a service

To update a `Service group`, just click on the edit button <img src="../img/edit.png" /> of your `Service group`

@@@ div { .centered-img }
<img src="../img/service-groups-edit.png" />
@@@

Update the name and description of the `Service group` and click on the `Update group` button to validate name update.

@@@ div { .centered-img }
<img src="../img/service-groups-update.png" />
@@@

## Delete a service group

To delete a `Service group`, just click on the delete button <img src="../img/delete.png" /> of your `Service group`

@@@ div { .centered-img }
<img src="../img/service-groups-delete.png" />
@@@

Finally confirm the command

@@@ div { .centered-img }
<img src="../img/service-groups-delete-confirm.png" />
@@@
