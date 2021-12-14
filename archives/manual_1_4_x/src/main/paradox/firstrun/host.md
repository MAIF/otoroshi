# Setup your hosts

By default, Otoroshi starts with domain `oto.tools` that targets `127.0.0.1`. Of course you can change the domain, you have to add the values in your `/etc/hosts` file according to the setting you put in Otoroshi configuration

* `app.domain` => `oto.tools`
* `app.backoffice.subdomain` => `otoroshi`
* `app.privateapps.subdomain` => `privateapps`
* `app.adminapi.exposedSubdomain` => `otoroshi-api`
* `app.adminapi.targetSubdomain` => `otoroshi-admin-internal-api`

for instance if you want to change the default domain and use something like `otoroshi.mydomain.org`, then start otoroshi like 

```sh
java -Dapp.domain=mydomain.org -jar otoroshi.jar
```

@@@ warning
Otoroshi cannot be accessed using `http://127.0.0.1:8080` or `http://localhost:8080` because Otoroshi uses Otoroshi to serve it's own UI and API. When otoroshi starts with an empty database, it will create a service descriptor for that using `app.domain` and the settings listed on this page and in the * @ref:[Config. with files page](./configfile.md) that serve Otoroshi API and UI on `http://otoroshi-api.${app.domain}` and `http://otoroshi.${app.domain}`.
Once the descriptor is saved in database, if you want to change `app.domain`, you'll have to edit the descriptor in the database or restart Otoroshi with an empty database.
@@@
