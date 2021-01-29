# Import initial state

Now you are almost ready to run Otoroshi for the first time, but maybe you want to import data from previous Otoroshi installation in your current datastore.
## Import from a file

To import data from file, you need to add the `app.importFrom` setting to the Otoroshi configuration (or `$APP_IMPORT_FROM` env).

It can be a file path or a URL. 

## Import with initial customization

To import data inline in your configuration, you need to add the `app.initialCustomization` setting to the Otoroshi configuration (or `$OTOROSHI_INITIAL_CUSTOMIZATION` env).

It is a one line json object with the following properties :

| name | description |
| ---- | ----- |
| config | Otoroshi global configuration |
| descs | Services descriptors |
| apikeys | Apikeys |
| groups | Services groups |
| tmplts | Error templates |
| jswVerifiers | Jwt verifiers |
| authConfigs | Auth module configs |
| certificates | certificates |
| scripts | Scripts |
| tcpServices | Tcp services |
| admins | otoroshi admins with web authentication |
| simpleAdmins | otoroshi admins (without web authentication) |
## Example of export

```json
{
  "config": {
    "lines": ["prod"],    
    "limitConcurrentRequests": true,
    "maxConcurrentRequests": 500,
    "useCircuitBreakers": true,
    "apiReadOnly": false,
    "registerFromCleverHook": false,
    "u2fLoginOnly": true,
    "ipFiltering": {
      "whitelist": [],
      "blacklist": []
    },
    "throttlingQuota": 100000,
    "perIpThrottlingQuota": 500,
    "analyticsEventsUrl": null,
    "analyticsWebhooks": [],
    "alertsWebhooks": [],
    "alertsEmails": [],
    "endlessIpAddresses": []
  },
  "admins": [],
  "simpleAdmins": [
    {
      "username": "admin@otoroshi.io",
      "password": "xxxxxxxxxxxxxxxxx",
      "label": "Otoroshi Admin",
      "createdAt": 1493971715708
    }
  ],
  "serviceGroups": [
    {
      "id": "default",
      "name": "default-group",
      "description": "The default group"
    },
    {
      "id": "admin-api-group",
      "name": "Otoroshi Admin Api group",
      "description": "No description"
    }
  ],
  "apiKeys": [
    {
      "clientId": "admin-api-apikey-id",
      "clientSecret": "admin-api-apikey-secret",
      "clientName": "Otoroshi Backoffice ApiKey",
      "authorizedEntities": ["group_admin-api-group"],
      "enabled": true,
      "throttlingQuota": 10000000,
      "dailyQuota": 10000000,
      "monthlyQuota": 10000000,
      "metadata": {}
    }
  ],
  "serviceDescriptors": [
    {
      "id": "admin-api-service",
      "groupId": "admin-api-group",
      "name": "otoroshi-admin-api",
      "env": "prod",
      "domain": "oto.tools",
      "subdomain": "otoroshi-api",
      "targets": [
        {
          "host": "localhost:8080",
          "scheme": "http"
        }
      ],
      "root": "/",
      "enabled": true,
      "privateApp": false,
      "forceHttps": false,
      "maintenanceMode": false,
      "buildMode": false,
      "enforceSecureCommunication": true,
      "publicPatterns": [],
      "privatePatterns": [],
      "additionalHeaders": {
        "Host": "otoroshi-admin-internal-api.oto.tools"
      },
      "matchingHeaders": {},
      "ipFiltering": {
        "whitelist": [],
        "blacklist": []
      },
      "api": {
        "exposeApi": false
      },
      "healthCheck": {
        "enabled": false,
        "url": "/"
      },
      "metadata": {}
    }
  ],
  "errorTemplates": []
}
```
