# Secure an app with OAuth1 client flow

### Cover by this tutorial
- [Before you start](#before-you-start)
- [Running an simple OAuth 1 server](#running-an-simple-oauth-1-server)
- [Create an OAuth 1 provider module](#create-an-oauth-1-provider-module)
- [Connect to Otoroshi with OAuth1 authentication](#connect-to-otoroshi-with-oauth1-authentication)
- [Testing your configuration](#testing-your-configuration)
- [Secure an app with OAuth 1 authentication](#secure-an-app-with-oauth-1-authentication)

### Before you start

@@include[initialize.md](../includes/initialize.md) { #initialize-otoroshi }

### Running an simple OAuth 1 server

In this tutorial, we'll instanciate a oauth 1 server with docker. If you alredy have the necessary, skip this section @ref:[to](#create-an-oauth-1-provider-module).

Let's start by running the server
```sh
docker run -d --name oauth1-server --rm \
    -p 5000:5000 \
    -e OAUTH1_CLIENT_ID=2NVVBip7I5kfl0TwVmGzTphhC98kmXScpZaoz7ET \
    -e OAUTH1_CLIENT_SECRET=wXzb8tGqXNbBQ5juA0ZKuFAmSW7RwOw8uSbdE3MvbrI8wjcbGp \
    -e OAUTH1_REDIRECT_URI=http://otoroshi.oto.tools:8080/backoffice/auth0/callback \
    ghcr.io/beryju/oauth1-test-server
```

We created a oauth 1 server which accepts `http://otoroshi.oto.tools:8080/backoffice/auth0/callback` as `Redirect URI`. This URL is used by Otoroshi to retrieve a token and a profile at the end of an authentication process.

After this command, the container logs should output :
```sh 
127.0.0.1 - - [14/Oct/2021 12:10:49] "HEAD /api/health HTTP/1.1" 200 -
```

### Create an OAuth 1 provider module

1. Go ahead, and navigate to http://otoroshi.oto.tools:8080
1. Click on the cog icon on the top right
1. Then `Authentication configs` button
1. And add a new configuration when clicking on the `Add item` button
2. Select the `Oauth1 provider` in the type selector field
3. Set a basic name and description like `oauth1-provider`
4. Set `2NVVBip7I5kfl0TwVmGzTphhC98kmXScpZaoz7ET` as `Consumer key`
5. Set `wXzb8tGqXNbBQ5juA0ZKuFAmSW7RwOw8uSbdE3MvbrI8wjcbGp` as `Consumer secret`
6. Set `http://localhost:5000/oauth/request_token` as `Request Token URL`
7. Set `http://localhost:5000/oauth/authorize` as `Authorize URL`
8. Set `http://localhost:oauth/access_token` as `Access token URL`
9. Set `http://localhost:5000/api/me` as `Profile URL`
10. Set `http://otoroshi.oto.tools:8080/backoffice/auth0/callback` as `Callback URL`
11. At the bottom of the page, disable the `secure` button (because we're using http and this configuration avoid to include cookie in an HTTP Request without secure channel, typically HTTPs)

 At this point, your configuration should be similar to :
<!-- oto-scenario
 - goto /bo/dashboard/auth-configs/edit/auth_mod_oauth1.0_provider
 - wait 1000
 - screenshot generated-hows-to-secure-with-oauth1-provider.png
-->
<img src="../imgs/generated-hows-to-secure-with-oauth1-provider.png" />

With this configuration, the connected user will receive default access on teams and organizations. If you want to change the access rights for a specific user, you can achieve it with the `Rights override` field and a configuration like :
```json
{
  "foo@example.com": [
    {
      "tenant": "*:rw",
      "teams": [
        "*:rw"
      ]
    }
  ]
}
```

Save your configuration at the bottom of the page, then navigate to the `danger zone` to use your module as a third-party connection to the Otoroshi UI.

### Connect to Otoroshi with OAuth1 authentication

To secure Otoroshi with your OAuth1 configuration, we have to register an Authentication configuration as a BackOffice Auth. configuration.

1. Navigate to the *danger zone* (when clicking on the cog on the top right and selecting Danger zone)
1. Scroll to the *BackOffice auth. settings*
1. Select your last Authentication configuration (created in the previous section)
1. Save the global configuration with the button on the top right

### Testing your configuration

1. Disconnect from your instance
1. Then click on the *Login using third-party* button (or navigate to *http://otoroshi.oto.tools:8080*)
2. Click on `Login using Third-party` button
3. If all is configured, Otoroshi will redirect you to the oauth 1 server login page
4. Set `example-user` as user and trust the user by clicking on `yes` button.
5. Good work! You're connected to Otoroshi with an OAuth1 module.

> A fallback solution is always available, by going to *http://otoroshi.oto.tools:8080/bo/simple/login*, for administrators in case your Authentication module is not available

### Secure an app with OAuth 1 authentication

With the previous configuration, you can secure any of Otoroshi services with it. 

The first step is to apply a little change on the previous configuration. 

1. Navigate to *http://otoroshi.oto.tools:8080/bo/dashboard/auth-configs*.
2. Create a new auth module configuration with the same values.
3. Replace the `Callback URL` field to `http://privateapps.oto.tools:8080/privateapps/generic/callback` (we changed this value because the redirection of a logged user by a third-party server is cover by an other route by Otoroshi).
4. Disable the `secure` button (because we're using http and this configuration avoid to include cookie in an HTTP Request without secure channel, typically HTTPs)

> Note : a Otoroshi service is call a private app when it is protected by an authentication module.

Our example server supports only one redirect URI. We need to kill it, and to create a new container with `http://otoroshi.oto.tools:8080/privateapps/generic/callback` as `OAUTH1_REDIRECT_URI`
```sh
docker rm -f oauth1-server
docker run -d --name oauth1-server --rm \
    -p 5000:5000 \
    -e OAUTH1_CLIENT_ID=2NVVBip7I5kfl0TwVmGzTphhC98kmXScpZaoz7ET \
    -e OAUTH1_CLIENT_SECRET=wXzb8tGqXNbBQ5juA0ZKuFAmSW7RwOw8uSbdE3MvbrI8wjcbGp \
    -e OAUTH1_REDIRECT_URI=http://privateapps.oto.tools:8080/privateapps/generic/callback \
    ghcr.io/beryju/oauth1-test-server
```

Once the authentication module and the new container created, we can set the authentication module on the service.

1. Navigate to any created service
2. Scroll to `Authentication` section
3. Enable `Enforce user authentication`
4. Select your Authentication config inside the list
5. Enable `Strict mode`
6.  Don't forget to save your configuration.

Now you can try to call your defined service and see the login module appears.

> <img src="../imgs/hows-to-secure-app-with-oauth1-provider-input.png">

The allow access to the user.

> <img src="../imgs/hows-to-secure-app-with-oauth1-provider-trust.png">

If you had any errors, make sure of :
* check if you are on http or https, and if the *secure cookie option* is enabled or not on the authentication module
* check if your oauth1 server has the REDIRECT_URI set on *privateapps/...*
* Make sure your server supports POST or GET oauth1 flow set on authentication module

Once the configuration is working, you can check, when connecting with an Otoroshi admin user, the `Private App session` created (use the cog at the right top of the page, and select `Priv. app sesssions`, or navigate to *http://otoroshi.oto.tools:8080/bo/dashboard/sessions/private*).

One interesing feature is to check the profile of the connected user. In our case, when clicking on the `Profile` button of the right user, we should have : 
```json
{
  "email": "foo@example.com",
  "id": 1,
  "name": "test name",
  "screen_name": "example-user"
}
```