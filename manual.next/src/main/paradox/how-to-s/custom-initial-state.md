# Initial state customization

when you start otoroshi for the first time, some basic entities will be created and stored in the datastore in order to make your instance work properly. However it might not be enough for your use case but you do want to bother with restoring a complete otoroshi export.

In order to make state customization easy, otoroshi provides the config. key `app.initialCustomization`, overriden by the env. variable `OTOROSHI_INITIAL_CUSTOMIZATION`

The expected structure is the following :

```javascript
{
  "config": { ... },
  "admins": [],
  "simpleAdmins": [],
  "serviceGroups": [],
  "apiKeys": [],
  "serviceDescriptors": [],
  "errorTemplates": [],
  "jwtVerifiers": [],
  "authConfigs": [],
  "certificates": [],
  "clientValidators": [],
  "scripts": [],
  "tcpServices": [],
  "dataExporters": [],
  "tenants": [],
  "teams": []
}
```

in this structure, everything is optional. For every array property, items will be added to the datastore. For the global config. object, you can just add the parts that you need, and they will be merged with the existing config. object of the datastore.

for instance, if you want to customize the behavior of the TLS termination, you can use the following :

```sh
export OTOROSHI_INITIAL_CUSTOMIZATION='{"config":{"tlsSettings":{"defaultDomain":"www.foo.bar","randomIfNotFound":false}}'
```
