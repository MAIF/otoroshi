# Deploy your own Wasmo

Installing Wasmo can be done by following the [Getting Started](https://maif.github.io/wasmo/builder/getting-started) in Wasmo documentation.

## Tutorial

- [Deploy your own Wasmo](#deploy-your-own-wasmo)
  - [Tutorial](#tutorial)
    - [Before your start](#before-your-start)
    - [Create a route to expose and protect Wasmo with authentication](#create-a-route-to-expose-and-protect-wasmo-with-authentication)
    - [Create a first validator plugin using Wasmo](#create-a-first-validator-plugin-using-wasmo)
    - [Pairing Otoroshi and Wasmo](#pairing-otoroshi-with-wasmo)
    - [Create a route using the generated wasm file](#create-a-route-using-the-generated-wasm-file)
    - [Test your route](#test-your-route)

After completing these steps you will have a running Otoroshi instance and our owm Wasmo linked together.

### Before your start

@@include[initialize.md](../includes/initialize.md) { #initialize-otoroshi }

### Create a route to expose and protect Wasmo with authentication

We are going to use the admin API of Otoroshi to create the route. The configuration of the route is:

* `wasmo` as name
* `wasmo.oto.tools` as exposed domain
* `localhost:5001` as target without TLS option enabled

We need to add two more plugins to require the authentication from users and to pass the logged in user to Wasmo. 
These plugins are named `Authentication` and `Otoroshi Info. token`. 
The Authentication plugin will use an in-memory authentication with one default user (wasm@otoroshi.io/password). 
The second plugin will be configured with the value of the `OTOROSHI_USER_HEADER` environment variable. 

Let's create the authentication module (if you are interested in how authentication module works, 
you should read the other tutorials about How to secure an app). 
The following command creates an in-memory authentication module with an user.

```sh
curl -X POST "http://otoroshi-api.oto.tools:8080/api/auths" \
-u "admin-api-apikey-id:admin-api-apikey-secret" \
-H 'Content-Type: application/json; charset=utf-8' \
-d @- <<'EOF'
{
  "id": "wasmo_in_memory",
  "type": "basic",
  "name": "In memory authentication",
  "desc": "Group of static users",
  "users": [
    {
      "name": "User Otoroshi",
      "password": "$2a$10$oIf4JkaOsfiypk5ZK8DKOumiNbb2xHMZUkYkuJyuIqMDYnR/zXj9i",
      "email": "wasm@otoroshi.io"
    }
  ],
  "sessionCookieValues": {
    "httpOnly": true,
    "secure": false
  }
}
EOF
```

Once created, you can create our route to expose Wasmo.

```sh
curl -X POST "http://otoroshi-api.oto.tools:8080/api/routes" \
-H "Content-type: application/json" \
-u "admin-api-apikey-id:admin-api-apikey-secret" \
-d @- <<'EOF'
{
  "id": "wasmo",
  "name": "wasmo",
  "frontend": {
    "domains": ["wasmo.oto.tools"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "localhost",
        "port": 5001,
        "tls": false
      }
    ],
    "load_balancing": {
      "type": "RoundRobin"
    }
  },
  "plugins": [
     {
      "enabled": true,
      "plugin": "cp:otoroshi.next.plugins.AuthModule",
      "exclude": [
        "/plugins",
        "/wasm/.*"
      ],
      "config": {
        "pass_with_apikey": false,
        "auth_module": null,
        "module": "wasmo_in_memory"
      }
    },
    {
      "enabled": true,
      "plugin": "cp:otoroshi.next.plugins.ApikeyCalls",
      "include": [
        "/plugins",
        "/wasm/.*"
      ],
      "config": {}
    },
    {
      "enabled": true,
      "plugin": "cp:otoroshi.next.plugins.OtoroshiInfos",
      "config": {
        "version": "Latest",
        "ttl": 30,
        "header_name": "Otoroshi-User",
        "algo": {
          "type": "HSAlgoSettings",
          "size": 512,
          "secret": "veryverysecret"
        }
      }
    }
  ]
}
EOF
```

Try to access to Wasmo with the new domain: http://wasmo.oto.tools:8080. 
This should redirect you to the login page of Otoroshi. Enter the credentials of the user: wasm@otoroshi.io/password
Congratulations, you now have secured Wasmo.

### Create a first validator plugin using Wasmo

In the previous part, we secured the access to Wasmo. Now, is the time to create your first simple plugin, written in Rust. 
This plugin will apply a check on the request and ensure that the headers contains the key-value foo:bar.

1. On the right top of the screen, click on the plus icon to create a new plugin
2. Select the Rust language
3. Call it `my-first-validator` and press the enter key
4. Click on the new plugin called `my-first-validator`

Before continuing, let's explain the different files already present in your plugin. 

* `types.rs`: this file contains all Otoroshi structures that the plugin can receive and respond
* `lib.rs`: this file is the core of your plugin. It must contain at least one **function** which will be called by Otoroshi when executing the plugin.
* `Cargo.toml`: for each rust package, this file is called its manifest. It is written in the TOML format. 
It contains metadata that is needed to compile the package. You can read more information about it [here](https://doc.rust-lang.org/cargo/reference/manifest.html)

You can write a plugin for different uses cases in Otoroshi: validate an access, transform request or generate a target. 
In terms of plugin type,
you need to change your plugin's context and reponse types accordingly.

Let's take the example of creating a validator plugin. If we search in the types.rs file, we can found the corresponding 
types named: `WasmAccessValidatorContext` and `WasmAccessValidatorResponse`.
These types must be use in the declaration of the main **function** (named execute in our case).

```rust
... 
pub fn execute(Json(context): Json<types::WasmAccessValidatorContext>) -> FnResult<Json<types::WasmAccessValidatorResponse>> {
  
}
```

With this code, we declare a function named `execute`, which takes a context of type WasmAccessValidatorContext as parameter, 
and which returns an object of type WasmAccessValidatorResponse. Now, let's add the check of the foo header.

```rust
... 
pub fn execute(Json(context): Json<types::WasmAccessValidatorContext>) -> FnResult<Json<types::WasmAccessValidatorResponse>> {
    match context.request.headers.get("foo") {
        Some(foo) => if foo == "bar" {
            Ok(Json(types::WasmAccessValidatorResponse { 
                result: true,
                error: None
            }))
        } else {
            Ok(Json(types::WasmAccessValidatorResponse { 
                result: false, 
                error: Some(types::WasmAccessValidatorError { 
                    message: format!("{} is not authorized", foo).to_owned(),  
                    status: 401
                })  
            }))
        },
        None => Ok(Json(types::WasmAccessValidatorResponse { 
            result: false, 
            error: Some(types::WasmAccessValidatorError { 
                message: "you're not authorized".to_owned(),  
                status: 401
            })  
        }))
    }
}
```

First, we checked if the foo header is present, otherwise we return an object of type WasmAccessValidatorError.
In the other case, we continue by checking its value. In this example, we have used three types, already declared for you in the types.rs file:
`WasmAccessValidatorResponse`, `WasmAccessValidatorError` and `WasmAccessValidatorContext`. 

At this time, the content of your lib.rs file should be:

```rust
mod types;

use extism_pdk::*;

#[plugin_fn]
pub fn execute(Json(context): Json<types::WasmAccessValidatorContext>) -> FnResult<Json<types::WasmAccessValidatorResponse>> {
    match context.request.headers.get("foo") {
        Some(foo) => if foo == "bar" {
            Ok(Json(types::WasmAccessValidatorResponse { 
                result: true,
                error: None
            }))
        } else {
            Ok(Json(types::WasmAccessValidatorResponse { 
                result: false, 
                error: Some(types::WasmAccessValidatorError { 
                    message: format!("{} is not authorized", foo).to_owned(),  
                    status: 401
                })  
            }))
        },
        None => Ok(Json(types::WasmAccessValidatorResponse { 
            result: false, 
            error: Some(types::WasmAccessValidatorError { 
                message: "you're not authorized".to_owned(),  
                status: 401
            })  
        }))
    }
}
```

Let's compile this plugin by clicking on the hammer icon at the right top of your screen. Once done, you can try your built plugin directly in the UI.
Click on the play button at the right top of your screen, select your plugin and the correct type of the incoming fake context. 
Once done, click on the run button at the bottom of your screen. This should output an error.

```json
{
    "result": false,
    "error": {
        "message": "asd is not authorized",
        "status": 401
    }
}
```

Let's edit the fake input context by adding the exepected foo Header.

```json
{
    "request": {
        "id": 0,
        "method": "",
        "headers": {
          "foo": "bar"
        },
        "cookies"
        ...
```

Resubmit the command. It should pass.

### Pairing Otoroshi and Wasmo

Now that we have our compiled plugin, we have to connect Otoroshi with Wasmo. Let's navigate to the danger zone, and add the following values in the Wasmo section:

* `URL`: http://localhost:5001
* `Apikey id`: admin-api-apikey-id
* `Apikey secret`: admin-api-apikey-secret
* `User(s)`: *
* `Token secret`:

The User(s) property is used by Wasmo to filter the list of returned plugins (example: wasm@otoroshi.io will only return the list of plugins created by this user). 

Don't forget to save the configuration.

### Create a route using the generated wasm file

The last step of our tutorial is to create the route using the validator. Let's create the route with the following parameters:

```sh
curl -X POST "http://otoroshi-api.oto.tools:8080/api/routes" \
-H "Content-type: application/json" \
-u admin-api-apikey-id:admin-api-apikey-secret \
-d @- <<'EOF'
{
  "id": "wasm-route",
  "name": "wasm-route",
  "frontend": {
    "domains": ["wasm-route.oto.tools"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "localhost",
        "port": 5001,
        "tls": false
      }
    ],
    "load_balancing": {
      "type": "RoundRobin"
    }
  },
  "plugins": [
     {
      "plugin": "cp:otoroshi.next.plugins.WasmAccessValidator",
      "enabled": true,
      "config": {
        "compiler_source": "my-first-validator",
        "functionName": "execute"
      }
    }
  ]
}
EOF
```

You can validate the creation by navigating to the [dashboard](http://otoroshi.oto.tools:9999/bo/dashboard/routes/wasm-route?tab=flow)

### Test your route

Run the two following commands. The first should show an unauthorized error and the second should conclude this tutorial.

```sh
curl "http://wasm-route.oto.tools:8080"
```

and 

```sh
curl "http://wasm-route.oto.tools:8080" -H "foo:bar"
```

Congratulations, you have successfully written your first validator using your own Wasmo.
