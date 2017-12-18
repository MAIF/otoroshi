# Managing api keys

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

go to a service descriptor using `All services` quick link in the sidebar or the search box, select a `service descriptor` and click on `API keys` in the sidebar

## Create an api key for a service descriptor
