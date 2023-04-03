# Expression language

- [Documentation and examples](#documentation-and-examples)
- [Test the expression language](#test-the-expression-language)

The expression language provides an important mechanism for accessing and manipulating Otoroshi data on different inputs. For example, with this mechanism, you can mapping a claim of an inconming token directly in a claim of a generated token (using @ref:[JWT verifiers](../entities/jwt-verifiers.md)). You can add information of the service descriptor traversed such as the domain of the service or the name of the service. This information can be useful on the backend service.

## Documentation and examples
<!-- Documentation is in expression-language.js and it build when page is rendered -->
@@@div { #expressions }
&nbsp;
@@@

If an input contains a string starting by `${`, Otoroshi will try to evaluate the content. If the content doesn't match a known expression,
the 'bad-expr' value will be set.

## Test the expression language

You can test to get the same values than the right part by creating these following services. 

```sh
# Let's start by downloading the latest Otoroshi.
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v16.2.1/otoroshi.jar'

# Once downloading, run Otoroshi.
java -Dotoroshi.adminPassword=password -jar otoroshi.jar 

# Create an authentication module to protect the following route.
curl -X POST http://otoroshi-api.oto.tools:8080/api/auths \
-H "Otoroshi-Client-Id: admin-api-apikey-id" \
-H "Otoroshi-Client-Secret: admin-api-apikey-secret" \
-H 'Content-Type: application/json; charset=utf-8' \
-d @- <<'EOF'
{"type":"basic","id":"auth_mod_in_memory_auth","name":"in-memory-auth","desc":"in-memory-auth","users":[{"name":"User Otoroshi","password":"$2a$10$oIf4JkaOsfiypk5ZK8DKOumiNbb2xHMZUkYkuJyuIqMDYnR/zXj9i","email":"user@foo.bar","metadata":{"username":"roger"},"tags":["foo"],"webauthn":null,"rights":[{"tenant":"*:r","teams":["*:r"]}]}],"sessionCookieValues":{"httpOnly":true,"secure":false}}
EOF


# Create a proxy of the mirror.otoroshi.io on http://api.oto.tools:8080
curl -X POST http://otoroshi-api.oto.tools:8080/api/routes \
-u admin-api-apikey-id:admin-api-apikey-secret \
-H 'Content-Type: application/json; charset=utf-8' \
-d @- <<'EOF'
{
    "id": "expression-language-api-service",
    "name": "expression-language",
    "enabled": true,
    "frontend": {
        "domains": [
            "api.oto.tools/"
        ]
    },
    "backend": {
        "targets": [
            {
                "hostname": "mirror.otoroshi.io",
                "port": 443,
                "tls": true
            }
        ]
    },
    "plugins": [
        {
            "enabled": true,
            "plugin": "cp:otoroshi.next.plugins.OverrideHost"
        },
        {
          "enabled": true,
          "plugin": "cp:otoroshi.next.plugins.ApikeyCalls",
          "config": {
              "validate": true,
              "mandatory": true,
              "pass_with_user": true,
              "wipe_backend_request": true,
              "update_quotas": true
          },
          "plugin_index": {
              "validate_access": 1,
              "transform_request": 2,
              "match_route": 0
          }
      },
        {
            "enabled": true,
            "plugin": "cp:otoroshi.next.plugins.AuthModule",
            "config": {
                "pass_with_apikey": true,
                "auth_module": null,
                "module": "auth_mod_in_memory_auth"
            },
            "plugin_index": {
                "validate_access": 1
            }
        },
        {
            "enabled": true,
            "plugin": "cp:otoroshi.next.plugins.AdditionalHeadersIn",
            "config": {
                "headers": {
                    "my-expr-header.apikey.unknown-tag": "${apikey.tags['0':'no-found-tag']}",
                    "my-expr-header.request.uri": "${req.uri}",
                    "my-expr-header.ctx.replace-field-all-value": "${ctx.foo.replaceAll('o','a')}",
                    "my-expr-header.env.unknown-field": "${env.java_h:not-found-java_h}",
                    "my-expr-header.service-id": "${service.id}",
                    "my-expr-header.ctx.unknown-fields": "${ctx.foob|ctx.foot:not-found}",
                    "my-expr-header.apikey.metadata": "${apikey.metadata.foo}",
                    "my-expr-header.request.protocol": "${req.protocol}",
                    "my-expr-header.service-domain": "${service.domain}",
                    "my-expr-header.token.unknown-foo-field": "${token.foob:not-found-foob}",
                    "my-expr-header.service-unknown-group": "${service.groups['0':'unkown group']}",
                    "my-expr-header.env.path": "${env.PATH}",
                    "my-expr-header.request.unknown-header": "${req.headers.foob:default value}",
                    "my-expr-header.service-name": "${service.name}",
                    "my-expr-header.token.foo-field": "${token.foob|token.foo}",
                    "my-expr-header.request.path": "${req.path}",
                    "my-expr-header.ctx.geolocation": "${ctx.geolocation.foo}",
                    "my-expr-header.token.unknown-fields": "${token.foob|token.foob2:not-found}",
                    "my-expr-header.request.unknown-query": "${req.query.foob:default value}",
                    "my-expr-header.service-subdomain": "${service.subdomain}",
                    "my-expr-header.date": "${date}",
                    "my-expr-header.ctx.replace-field-value": "${ctx.foo.replace('o','a')}",
                    "my-expr-header.apikey.name": "${apikey.name}",
                    "my-expr-header.request.full-url": "${req.fullUrl}",
                    "my-expr-header.ctx.default-value": "${ctx.foob:other}",
                    "my-expr-header.service-tld": "${service.tld}",
                    "my-expr-header.service-metadata": "${service.metadata.foo}",
                    "my-expr-header.ctx.useragent": "${ctx.useragent.foo}",
                    "my-expr-header.service-env": "${service.env}",
                    "my-expr-header.request.host": "${req.host}",
                    "my-expr-header.config.unknown-port-field": "${config.http.ports:not-found}",
                    "my-expr-header.request.domain": "${req.domain}",
                    "my-expr-header.token.replace-header-value": "${token.foo.replace('o','a')}",
                    "my-expr-header.service-group": "${service.groups['0']}",
                    "my-expr-header.ctx.foo": "${ctx.foo}",
                    "my-expr-header.apikey.tag": "${apikey.tags['0']}",
                    "my-expr-header.service-unknown-metadata": "${service.metadata.test:default-value}",
                    "my-expr-header.apikey.id": "${apikey.id}",
                    "my-expr-header.request.header": "${req.headers.foo}",
                    "my-expr-header.request.method": "${req.method}",
                    "my-expr-header.ctx.foo-field": "${ctx.foob|ctx.foo}",
                    "my-expr-header.config.port": "${config.http.port}",
                    "my-expr-header.token.unknown-foo": "${token.foo}",
                    "my-expr-header.date-with-format": "${date.format('yyy-MM-dd')}",
                    "my-expr-header.apikey.unknown-metadata": "${apikey.metadata.myfield:default value}",
                    "my-expr-header.request.query": "${req.query.foo}",
                    "my-expr-header.token.replace-header-all-value": "${token.foo.replaceAll('o','a')}"
                }
            }
        }
    ]
}
EOF
```

Create an apikey or use the default generate apikey.

```sh
curl -X POST 'http://otoroshi-api.oto.tools:8080/api/apikeys' \
-H "Content-type: application/json" \
-u admin-api-apikey-id:admin-api-apikey-secret \
-d @- <<'EOF'
{
    "clientId": "api-apikey-id",
    "clientSecret": "api-apikey-secret",
    "clientName": "api-apikey-name",
    "description": "api-apikey-id-description",
    "authorizedGroup": "default",
    "enabled": true,
    "throttlingQuota": 10,
    "dailyQuota": 10,
    "monthlyQuota": 10,
    "tags": ["foo"],
    "metadata": {
      "fii": "bar"
    }
}
EOF
```

Then try to call the first service.

```sh
curl http://api.oto.tools:8080/api/\?foo\=bar \
-H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyLCJmb28iOiJiYXIifQ.lV130dFXR3bNtWBkwwf9dLmfsRVmnZhfYF9gvAaRzF8" \
-H "Otoroshi-Client-Id: api-apikey-id" \
-H "Otoroshi-Client-Secret: api-apikey-secret" \
-H "foo: bar" | jq
```

This will returns the list of the received headers by the mirror.

```json
{
  ...
  "headers": {
    ...
    "my-expr-header.date": "2021-11-26T10:54:51.112+01:00",
    "my-expr-header.ctx.foo": "no-ctx-foo",
    "my-expr-header.env.path": "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
    "my-expr-header.apikey.id": "admin-api-apikey-id",
    "my-expr-header.apikey.tag": "one-tag",
    "my-expr-header.service-id": "expression-language-api-service",
    "my-expr-header.apikey.name": "Otoroshi Backoffice ApiKey",
    "my-expr-header.config.port": "8080",
    "my-expr-header.request.uri": "/api/?foo=bar",
    "my-expr-header.service-env": "prod",
    "my-expr-header.service-tld": "oto.tools",
    "my-expr-header.request.host": "api.oto.tools:8080",
    "my-expr-header.request.path": "/api/",
    "my-expr-header.service-name": "expression-language",
    "my-expr-header.ctx.foo-field": "no-ctx-foob-foo",
    "my-expr-header.ctx.useragent": "no-ctx-useragent.foo",
    "my-expr-header.request.query": "bar",
    "my-expr-header.service-group": "default",
    "my-expr-header.request.domain": "api.oto.tools",
    "my-expr-header.request.header": "bar",
    "my-expr-header.request.method": "GET",
    "my-expr-header.service-domain": "api.oto.tools",
    "my-expr-header.apikey.metadata": "bar",
    "my-expr-header.ctx.geolocation": "no-ctx-geolocation.foo",
    "my-expr-header.token.foo-field": "no-token-foob-foo",
    "my-expr-header.date-with-format": "2021-11-26",
    "my-expr-header.request.full-url": "http://api.oto.tools:8080/api/?foo=bar",
    "my-expr-header.request.protocol": "http",
    "my-expr-header.service-metadata": "no-meta-foo",
    "my-expr-header.ctx.default-value": "other",
    "my-expr-header.env.unknown-field": "not-found-java_h",
    "my-expr-header.service-subdomain": "api",
    "my-expr-header.token.unknown-foo": "no-token-foo",
    "my-expr-header.apikey.unknown-tag": "one-tag",
    "my-expr-header.ctx.unknown-fields": "not-found",
    "my-expr-header.token.unknown-fields": "not-found",
    "my-expr-header.request.unknown-query": "default value",
    "my-expr-header.service-unknown-group": "default",
    "my-expr-header.request.unknown-header": "default value",
    "my-expr-header.apikey.unknown-metadata": "default value",
    "my-expr-header.ctx.replace-field-value": "no-ctx-foo",
    "my-expr-header.token.unknown-foo-field": "not-found-foob",
    "my-expr-header.service-unknown-metadata": "default-value",
    "my-expr-header.config.unknown-port-field": "not-found",
    "my-expr-header.token.replace-header-value": "no-token-foo",
    "my-expr-header.ctx.replace-field-all-value": "no-ctx-foo",
    "my-expr-header.token.replace-header-all-value": "no-token-foo",
  }
}
```

Then try the second call to the webapp. Navigate on your browser to `http://webapp.oto.tools:8080`. Continue with `user@foo.bar` as user and `password` as credential.

This should output:

```json
{
  ...
  "headers": {
    ...
    "my-expr-header.user": "User Otoroshi",
    "my-expr-header.user.email": "user@foo.bar",
    "my-expr-header.user.metadata": "roger",
    "my-expr-header.user.profile-field": "User Otoroshi",
    "my-expr-header.user.unknown-metadata": "not-found",
    "my-expr-header.user.unknown-profile-field": "not-found",
  }
}
```