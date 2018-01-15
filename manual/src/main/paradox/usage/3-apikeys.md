# Managing API keys

Now that you know how to create service groups and service descriptors, we will see how to create API keys.

## Otoroshi entities

There are 3 major entities at the core of Otoroshi.

* service groups
* service descriptors
* **api keys**

@@@ div { .centered-img }
<img src="../img/models-apikey.png" />
@@@

An `API key` related to a `service group` to allow you to access any `service descriptor` contained in a `service group`. You can, of course, create multiple `API key` for a given `service group`.

In the Otoroshi admin dashboard, we chose to access `API keys` from `service descriptors` only, but when you access `API keys` for a `service descriptor`, you actually access `API keys` for the `service group` containing the `service descriptor`.

`API keys` can be provided to Otoroshi through :

* `Authorization: Basic $base64(client_id:client_secret)` header
* `Authorization: Bearer $jwt_token` where the JWT token has been signed with the `API key` client secret
* `Otoroshi-Client-Id` and `Otoroshi-Client-Secret` header

## List API keys for a service descriptor

Go to a service descriptor using `All services` quick link in the sidebar or the search box.

@@@ div { .centered-img }
<img src="../img/sidebar-all-services.png" />
@@@

Select a `service descriptor`.

@@@ div { .centered-img }
<img src="../img/all-services.png" />
@@@

Click on `API keys` in the sidebar

@@@ div { .centered-img }
<img src="../img/sidebar-apikeys.png" />
@@@

You should see the list of API keys for that `service descriptor`

@@@ div { .centered-img }
<img src="../img/apikeys-list.png" />
@@@

## Create an API key for a service descriptor

@@@ div { .centered-img }
<img src="../img/add-apikey.png" />
@@@

You can add a name for your new API key, you can also change client's id and client's secret. You can also configure the throttling rate of the API key (calls per second), and the authorized number of call per day and per month. You may also activate or de-activate the api key from that screen.

Informations about current quotas usage will be returned in response headers.

* `Otoroshi-Daily-Calls-Remaining` : authorized calls remaining for this day
* `Otoroshi-Monthly-Calls-Remaining` : authorized calls remaining for this month
* `Otoroshi-Proxy-Latency` : latency induced by Otoroshi
* `Otoroshi-Upstream-Latency` : latency between Otoroshi and target

@@@ div { .centered-img #quotas }
<img src="../img/create-apikey.png" />
@@@

## Update an API key

To update an `API key`, just click on the edit button <img src="../img/edit.png" /> of your `API key`

@@@ div { .centered-img }
<img src="../img/apikey-edit.png" />
@@@

Update the name, secret, state and quotas (if needed) of the `API key` and click on the `Update API key` button

@@@ div { .centered-img }
<img src="../img/apikey-update.png" />
@@@

## Delete an API key

To delete an `API key`, just click on the delete button <img src="../img/delete.png" /> of your `API key`

@@@ div { .centered-img }
<img src="../img/apikey-delete.png" />
@@@

and confirm the command

@@@ div { .centered-img }
<img src="../img/apikey-delete-confirm.png" />
@@@
