# Managing api keys

now that you know how to create service groups and service descriptors, we will see how to create api keys

## Otoroshi entities

There are 3 major entities at the core of Otoroshi

* service groups
* service descriptors
* **api keys**

@@@ div { .centered-img }
<img src="../img/models-apikey.png" />
@@@

an `api key` exist for a `service group` so an `api key` will allow you to access any `service descriptor` contained in a `service group`. You can of course create multiple `api key` for a `service group`.

In the Otoroshi admin dashboard, we chose to access `api keys` from `service descriptors` only, but when you access `api keys` for a `service descriptor`, you actually access `api keys` for the `service group` containing the `service descriptor`.

## List api keys for a service descriptor

go to a service descriptor using `All services` quick link in the sidebar or the search box,


@@@ div { .centered-img }
<img src="../img/sidebar-all-services.png" />
@@@

select a `service descriptor` 

@@@ div { .centered-img }
<img src="../img/all-services.png" />
@@@

and click on `API keys` in the sidebar

@@@ div { .centered-img }
<img src="../img/sidebar-apikeys.png" />
@@@

and you should see the list of api keys for that `service descriptor`

@@@ div { .centered-img }
<img src="../img/apikeys-list.png" />
@@@

## Create an api key for a service descriptor

@@@ div { .centered-img }
<img src="../img/add-apikey.png" />
@@@

now you can add a name for your new apikey, you can also change the client id and the client secret. You can also configure the throttling rate of the apikey (calls per second), and the authorized number of call by day and by month. You can alos activate or de-activate the api key from that screen.

@@@ div { .centered-img }
<img src="../img/create-apikey.png" />
@@@

## Update an api key

to update a `api key`, just click on the edit button <img src="../img/edit.png" /> of your `api key`

@@@ div { .centered-img }
<img src="../img/apikey-edit.png" />
@@@

update the name, secret, state and quotas (if needed) of the `api key` and click on the `Update ApiKey` button

@@@ div { .centered-img }
<img src="../img/apikey-update.png" />
@@@

## Delete an api key

to delete an `api key`, just click on the delete button <img src="../img/delete.png" /> of your `api key`

@@@ div { .centered-img }
<img src="../img/apikey-delete.png" />
@@@

and confirm the command

@@@ div { .centered-img }
<img src="../img/apikey-delete-confirm.png" />
@@@
