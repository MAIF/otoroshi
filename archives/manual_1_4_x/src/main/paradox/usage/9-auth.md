# Authentication

You can create auth. configuration in Otoroshi. Just go to `settings (cog icon) / Authentication configs`.

## OAuth 2

Create a new `Generic oauth2 provider` config and customize the following informations:

```json
{
  "clientId": "xxxx",
  "clientSecret": "xxxx",
  "authorizeUrl": "http://yourOAuthServer/oauth/authorize",
  "tokenUrl": "http://yourOAuthServer/oauth/token",
  "userInfoUrl": "http://yourOAuthServer/userinfo",
  "loginUrl": "http://yourOAuthServer/login",
  "logoutUrl": "http://yourOAuthServer/logout?redirectQueryParamName=${redirect}",
  "accessTokenField": "access_token",
  "nameField": "name",
  "emailField": "email",
  "callbackUrl": "http://privateapps.oto.tools/privateapps/generic/callback"
}
```

If used for BackOffice authentication, the callback url should be `http://otoroshi.oto.tools/backoffice/auth0/callback`.

For `logoutUrl`, `redirectQueryParamName` is a parameter with a name specific to your OAuth2 provider (for example, in Auth0, this parameter is called `returnTo`, in Kecloak it is called `redirect_uri`).

if you are using a [KeyCloak](https://www.keycloak.org/) server, you can configure it this way, assuming you are using the master realm and you created a new client with a client secret, callback urls set to `http://privateapps.oto.tools/*`.

```json
{
  "clientId": "clientId",
  "clientSecret": "clientSecret",
  "authorizeUrl": "http://keycloakHost/auth/realms/master/protocol/openid-connect/auth",
  "tokenUrl": "http://keycloakHost/auth/realms/master/protocol/openid-connect/token",
  "userInfoUrl": "http://keycloakHost/auth/realms/master/protocol/openid-connect/userinfo",
  "loginUrl": "http://keycloakHost/auth/realms/master/protocol/openid-connect/auth",
  "logoutUrl": "http://keycloakHost/auth/realms/master/protocol/openid-connect/logout?redirect_uri=${redirect}",
  "accessTokenField": "access_token",
  "nameField": "name",
  "emailField": "email",
  "callbackUrl": "http://privateapps.oto.tools/privateapps/generic/callback"
}
```

## Ldap

Create a new `Ldap auth. provider` config and customize the following informations:

```json
{
  "serverUrl": "ldap://ldap.forumsys.com:389",
  "searchBase": "dc=example,dc=com",
  "groupFilter": "ou=chemists",
  "searchFilter": "(mail=${username})",
  "adminUsername": "cn=read-only-admin,dc=example,dc=com",
  "adminPassword": "password",
  "nameField": "cn",
  "emailField": "mail"
}
```

## In Memory

Create a new `In memory auth. provider` config and then you will be able to create new users. To set the password, just click on the `Set password` button. It will generate a BCrypt hash of the password you typed.

## Auth0

Create a new OAuth 2 config and add the following informations:

```json
{
  "clientId": "yourAuth0ClientId",
  "clientSecret": "yourAuth0ClientSecret",
  "authorizeUrl": "https://yourAuth0Domain/authorize",
  "tokenUrl": "https://yourAuth0Domain/oauth/token",
  "userInfoUrl": "https://yourAuth0Domain/userinfo",
  "loginUrl": "https://yourAuth0Domain/authorize",
  "logoutUrl": "https://yourAuth0Domain/v2/logout?returnTo=${redirect}",
  "accessTokenField": "access_token",
  "nameField": "name",
  "emailField": "email",
  "otoroshiDataField": "app_metadata | otoroshi_data",
  "callbackUrl": "http://privateapps.oto.tools/privateapps/generic/callback"
}
```

If you enable Otoroshi exchange protocol, the JWT xill have the following fields (all optional)

* `email`
* `name`
* `picture`
* `user_id`
* `given_name`
* `family_name`
* `gender`
* `locale`
* `nickname`

In Auth0, the metadata is a flat object placed in the `profile / http://yourdomain/app_metadata / otoroshi_data`. You might need to write an Auth0 rule to copy app metadata under `http://yourdomain/app_metadata`, the `http://yourdomain/app_metadata` value is a config property `app.appMeta`. The rule could be something like the following

```js
function (user, context, callback) {
  var namespace = 'http://yourdomain/';
  context.idToken[namespace + 'user_id'] = user.user_id;
  context.idToken[namespace + 'user_metadata'] = user.user_metadata;
  context.idToken[namespace + 'app_metadata'] = user.app_metadata;
  callback(null, user, context);
}
```