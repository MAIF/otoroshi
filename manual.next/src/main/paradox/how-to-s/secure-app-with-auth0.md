# Secure an app with Auth0

### Cover by this tutorial
- [Before you start](#before-you-start)
- [Configure an Auth0 client](#configure-an-auth0-client)
- [Create an Auth0 provider module](#create-an-auth0-provider-module)
- [Connect to Otoroshi with Auth0 authentication](#connect-to-otoroshi-with-keycloak-authentication)
- [Testing your configuration](#testing-your-configuration)
- [Secure an app with Auth0 authentication](#secure-an-app-with-keycloak-authentication)

@@@ warning
TODO - schema
@@@

### Before you start

Let's start by downloading the latest Otoroshi
```sh
curl -L -o otoroshi.jar 'https://github.com/MAIF/otoroshi/releases/download/v1.5.0-dev/otoroshi.jar'
```

By default, Otoroshi starts with domain `oto.tools` that targets `127.0.0.1`

Run Otoroshi
```sh
java -Dapp.adminPassword=password -Dhttp.port=9999 -Dhttps.port=9998 -jar otoroshi.jar 
```

Log to Otoroshi at http://otoroshi.oto.tools:9999/ with `admin@otoroshi.io/password`

Then create a simple service (@ref[instructions are available here](./secure-with-apikey.md#about-the-downstream-example-service))

### Configure an Auth0 client

The first step of this tutorial is to setup an Auth0 application with the information of the instance of our Otoroshi.

Navigate to *https://manage.auth0.com/* (create an account if it's not already done). 

Let's create an application when clicking on the `Applications` button on the sidebar. Then click on the `Create application` button on the right top.

1. Choose `Regular Web Applications` as `Application type`
2. Then set for example `otoroshi-client` as `Name`, and confirm the creation
3. Jump to the `Settings` tab
4. Scroll to the `Application URLs` section and add the following url as `Allowed Callback URLs` : `http://otoroshi.oto.tools:9999/backoffice/auth0/callback`
5. Set `https://otoroshi.oto.tools:9999/` as `Allowed Logout URLs`
6. Set `https://otoroshi.oto.tools:9999` as `Allowed Web Origins` 
7. Save changes at the bottom of the page.

Once done, we have a full setup, with a client ID and secret at the top of the page, which authorize our Otoroshi and redirect the user to the callback url when it will connect to Auth0.

### Create an Auth0 provider module

Let's back to Otoroshi to create an authentication module with `OAuth2 / OIDC provider` as `type`.

1. Go ahead, and navigate to http://otoroshi.oto.tools:9999
1. Click on the cog icon on the top right
1. Then `Authentication configs` button
1. And add a new configuration when clicking on the `Add item` button
2. Select the `OAuth provider` in the type selector field
3. Then click on `Get from OIDC config` and paste *https://<tenant-name>.<region>.auth0.com/.well-known/openid-configuration*. Replace the tenant name by the name of your tenant (displayed on the left top of auth0 page), and the region of the tenant (`eu` in my case).

Once done, set the `Client ID` and the `Client secret` from your auth0 application. End the configuration with `http://otoroshi.oto.tools:9999/backoffice/auth0/callback` as `Callback URL`.

At the bottom of the page, disable the `secure` button (because we're using http and this configuration avoid to include cookie in an HTTP Request without secure channel, typically HTTPs).

### Connect to Otoroshi with Auth0 authentication

To secure Otoroshi with your Auth0 configuration, we have to register an Authentication configuration as a BackOffice Auth. configuration.

1. Navigate to the *danger zone* (when clicking on the cog on the top right and selecting Danger zone)
2. Scroll to the *BackOffice auth. settings*
3. Select your last Authentication configuration (created in the previous section)
4. Save the global configuration with the button on the top right

#### Testing your configuration

1. Disconnect from your instance
1. Then click on the *Login using third-party* button (or navigate to *http://otoroshi.oto.tools:9999*)
2. Click on `Login using Third-party` button
3. If all is configured, Otoroshi will redirect you to the auth0 server login page
4. Set your account credentials
5. Good works! You're connected to Otoroshi with an Auth0 module.

### Secure an app with Auth0 authentication

With the previous configuration, you can secure any of Otoroshi services with it. 

The first step is to apply a little change on the previous configuration. 

1. Navigate to *http://otoroshi.oto.tools:9999/bo/dashboard/auth-configs*.
2. Create a new auth module configuration with the same values.
3. Replace the `Callback URL` field to `http://privateapps.oto.tools:9999/privateapps/generic/callback` (we changed this value because the redirection of a logged user by a third-party server is cover by an other route by Otoroshi).
4. Disable the `secure` button (because we're using http and this configuration avoid to include cookie in an HTTP Request without secure channel, typically HTTPs)

> Note : a Otoroshi service is call a private app when it is protected by an authentication module.

We can set the authentication module on the service.

1. Navigate to any created service
2. Scroll to `Authentication` section
3. Enable `Enforce user authentication`
4. Select your Authentication config inside the list
5. Enable `Strict mode`
6. Don't forget to save your configuration.
7. Now you can try to call your defined service and see the Auth0 login page appears.


