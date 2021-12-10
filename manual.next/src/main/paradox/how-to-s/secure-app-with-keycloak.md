# Secure an app with Keycloak

### Before you start

@@include[initialize.md](../includes/initialize.md) { #initialize-otoroshi }

### Running a keycloak instance with docker

```sh
docker run \
  -p 8080:8080 \
  -e KEYCLOAK_USER=admin \
  -e KEYCLOAK_PASSWORD=admin \
  --name keycloak-server \
  --detach jboss/keycloak:15.0.1
```

This should download the image of keycloak (if you haven't already it) and display the digest of the created container. This command mapped TCP port 8080 in the container to port 8080 of your laptop and created a server with `admin/admin` as admin credentials.

Once started, you can open a browser on `http://localhost:8080/` and click on `Administration Console`. Log to your instance with `admin/admin` as credentials.

The first step is to create a Keycloak client, an entity that can request Keycloak to authenticate a user. Click on the `clients` button on the sidebar, and then on `Create` button at the right top of the view.

Fill the client form with the following values.

* `Client ID`: `keycloak-otoroshi-backoffice`
* `Client Protocol`: `openid-connect`
* `Root URL`: `http://otoroshi.oto.tools:8080/`

End by create the client with the `Save` button.

The next step is to change the `Access Type` used by default. Jump to the `Access Type` field and select `confidential`. The confidential configuration force the client application to send at Keycloak a client ID and a client Secret. Scroll to the bottom of the page and save the configuration.

Now scroll to the top of your page. Just at the right of the `Settings` tab, a new tab appeared : the `Credentials` page. Click on this tab, and make sure that `Client Id and Secret` is selected as `Client Authenticator` and copy the generated `Secret` to the next part.

### Create a Keycloak provider module

1. Go ahead, and navigate to http://otoroshi.oto.tools:8080
1. Click on the cog icon on the top right
1. Then `Authentication configs` button
1. And add a new configuration when clicking on the `Add item` button
2. Select the `OAuth2 / OIDC provider` in the type selector field
3. Set a basic name and description

A simple way to import a Keycloak client is to give the `URL of the OpenID Connect` Otoroshi. By default, keycloak used the next URL : `http://localhost:8080/auth/realms/master/.well-known/openid-configuration`. 

Click on the `Get from OIDC config` button and paste the previous link. Once it's done, scroll to the `URLs` section. All URLs has been fill with the values picked from the JSON object returns by the previous URL.

The only fields to change are : 

* `Client ID`: `keycloak-otoroshi-backoffice`
* `Client Secret`: Paste the Secret from the Credentials Keycloak page. In my case, it's something like `90c9bf0b-2c0c-4eb0-aa02-72195beb9da7`
* `Callback URL`: `http://otoroshi.oto.tools:8080/backoffice/auth0/callback`

At the bottom of the page, disable the `secure` button (because we're using http and this configuration avoid to include cookie in an HTTP Request without secure channel, typically HTTPs). Nothing else to change, just save the configuration.

### Connect to Otoroshi with Keycloak authentication

To secure Otoroshi with your Keycloak configuration, we have to register an Authentication configuration as a BackOffice Auth. configuration.

1. Navigate to the *danger zone* (when clicking on the cog on the top right and selecting Danger zone)
1. Scroll to the *BackOffice auth. settings*
1. Select your last Authentication configuration (created in the previous section)
1. Save the global configuration with the button on the top right

### Testing your configuration

1. Disconnect from your instance
1. Then click on the *Login using third-party* button (or navigate to http://otoroshi.oto.tools:8080)
2. Click on `Login using Third-party` button
3. If all is configured, Otoroshi will redirect you to the keycloak login page
4. Set `admin/admin` as user and trust the user by clicking on `yes` button.
5. Good work! You're connected to Otoroshi with an Keycloak module.

> A fallback solution is always available, by going to http://otoroshi.oto.tools:8080/bo/simple/login, for administrators in case your Authentication module is not well configured or not available

### Visualize an admin user session or a private user session

Each user, wheter connected user to the Otoroshi UI or at a private Otoroshi app, has an own session. As an administrator of Otoroshi, you can visualize via Otoroshi the list of the connected users and their profile.

Let's start by navigating to the `Admin users sessions` page (just @link:[here](http://otoroshi.oto.tools:8080/bo/dashboard/sessions/admin) or when clicking on the cog, and on the `Admins sessions` button at the bottom of the list).

This page gives a complete view of the connected admins. For each admin, you have his connection date and his expiration date. You can also check the `Profile` and the `Rights` of the connected users.

If we check the profile and the rights of the previously logged user (from Keycloak in the previous part) we can retrieve the following information :

```json
{
  "sub": "4c8cd101-ca28-4611-80b9-efa504ac51fd",
  "upn": "admin",
  "email_verified": false,
  "address": {},
  "groups": [
    "create-realm",
    "default-roles-master",
    "offline_access",
    "admin",
    "uma_authorization"
  ],
  "preferred_username": "admin"
}
```

and his default rights 

```sh
[
  {
    "tenant": "default:rw",
    "teams": [
      "default:rw"
    ]
  }
]
```

We haven't create any specific groups in Keycloak or specify rights in Otoroshi for him. In this case, the use received the default Otoroshi rights at his connection. We can see that he can navigate on the default Organization and Teams (which are two resources created by Otoroshi at the boot) and that he have the full access (`r`: Read, `w`: Write, `*`: read/write) on its.

In the same way, you'll find all users connected to a private Otoroshi app when navigate on the @link:[`Private App View`](http://otoroshi.oto.tools:8080/bo/dashboard/sessions/private) or using the cog at the top of the page. 

### Configure the Keycloak module to force logged in users to be an Otoroshi admin with full access

Go back to the Keycloak module in `Authentication configs` view. Turn on the `Supers admin only` button and save your configuration. Try again the connection to Otoroshi using Keycloak third-party server.

Once connected, click on the cog button, and check that you have access to the full features of Otoroshi (like Admin user sessions). Now, your rights should be : 
```json
[
  {
    "tenant": "*:rw",
    "teams": [
      "*:rw"
    ]
  }
]
```

### Merge Id token content on user profile

Go back to the Keycloak module in `Authentication confgis` view. Turn on the `Read profile` from token button and save your configuration. Try again the connection to Otoroshi using Keycloak third-party server.

Once connected, your profile should be contains all Keycloak id token : 
```json
{
    "exp": 1634286674,
    "iat": 1634286614,
    "auth_time": 1634286614,
    "jti": "eb368578-e886-4caa-a51b-c1d04973c80e",
    "iss": "http://localhost:8080/auth/realms/master",
    "aud": [
        "master-realm",
        "account"
    ],
    "sub": "4c8cd101-ca28-4611-80b9-efa504ac51fd",
    "typ": "Bearer",
    "azp": "keycloak-otoroshi-backoffice",
    "session_state": "e44fe471-aa3b-477d-b792-4f7b4caea220",
    "acr": "1",
    "allowed-origins": [
        "http://otoroshi.oto.tools:8080"
    ],
    "realm_access": {
        "roles": [
        "create-realm",
        "default-roles-master",
        "offline_access",
        "admin",
        "uma_authorization"
        ]
    },
    "resource_access": {
        "master-realm": {
        "roles": [
            "view-identity-providers",
            "view-realm",
            "manage-identity-providers",
            "impersonation",
            "create-client",
            "manage-users",
            "query-realms",
            "view-authorization",
            "query-clients",
            "query-users",
            "manage-events",
            "manage-realm",
            "view-events",
            "view-users",
            "view-clients",
            "manage-authorization",
            "manage-clients",
            "query-groups"
        ]
        },
        "account": {
        "roles": [
            "manage-account",
            "manage-account-links",
            "view-profile"
        ]
        }
    }
    ...
}
```

### Manage the Otoroshi user rights from keycloak

One powerful feature supports by Otoroshi, is to use the Keycloak groups attributes to set a list of rights for a Otoroshi user.

In the Keycloak module, you have a field, named `Otoroshi rights field name` with `otoroshi_rights` as default value. This field is used by Otoroshi to retrieve information from the Id token groups.

Let's create a group in Keycloak, and set our default Admin user inside.
In Keycloak admin console :
1. Navigate to the groups view, using the keycloak sidebar
2. Create a new group with `my-group` as `Name`
3. Then, on the `Attributes` tab, create an attribute with `otoroshi_rights` as `Key` and the following json array as `Value`
```json
[
    {
        "tenant": "*:rw",
        "teams": [
            "*:rw",
            "my-future-team:rw"
        ]
    }
]
```

With this configuration, the user have a full access on all Otoroshi resources (my-future-team is not created in Otoroshi but it's not a problem, Otoroshi can handle it and use this rights only when the teams will be present)

Click on the `Add` button and save the group. The last step is to assign our user to this group. Jump to `Users` view using the sidebar, click on `View all users`, edit the user and his group membership using the `Groups` tab (use `join` button the assign user in `my-group`).

The next step is to add a mapper in the Keycloak client. By default, Keycloak doesn't expose any users information (like group membership or users attribute). We need to ask to Keycloak to expose the user attribute `otoroshi_rights` set previously on group.

Navigate to the `Keycloak-otoroshi-backoffice` client, and jump to `Mappers` tab. Create a new mapper with the following values: 

* Name: `otoroshi_rights`
* Mapper Type: `User Attribute`
* User Attribute: `otoroshi_rights`
* Token Claim Name: `otoroshi_rights`
* Claim JSON Type: `JSON`
* Multivalued: `√`
* Aggregate attribute values: `√`

Go back to the Authentication Keycloak module inside Otoroshi UI, and turn off `Super admins only`. Save the configuration.

Once done, try again the connection to Otoroshi using Keycloak third-party server.
Now, your rights should be : 
```json
[
  {
    "tenant": "*:rw",
    "teams": [
      "*:rw",
      "my-future-team:rw"
    ]
  }
]
```

### Secure an app with Keycloak authentication

The only change to apply on the previous authentication module, is on the callback URL. When you want secure a Otoroshi service, and transform it on `Private App`, you need to set the `Callback URL` at `http://privateapps.oto.tools:8080/privateapps/generic/callback`

1. Go back to the authentication module
1. Jump to the `Callback URL` field
2. Paste this value `http://privateapps.oto.tools:8080/privateapps/generic/callback`
3. Save your configuration
4. Navigate to `http://myservice.oto.tools:8080`.
5. You should redirect to the keycloak login page.
6. Once logged in, you can check the content of the private app session created.

The rights should be : 

```json
[
  {
    "tenant": "*:rw",
    "teams": [
      "*:rw",
      "my-future-team:rw"
    ]
  }
]
```