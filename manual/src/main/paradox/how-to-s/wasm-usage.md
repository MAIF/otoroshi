# Otoroshi and WASM

WebAssembly (WASM) is a simple machine model and executable format with an extensive specification. It is designed to be portable, compact, and execute at or near native speeds. Otoroshi already supports the execution of WASM files by providing different plugins that can be applied on routes. These plugins are:

- `WasmAccessValidator`: useful to control access to a route (jump to the next section to learn more about it)
- `WasmRequestTransformer`: transform the content of an incoming request (body, headers, etc ...)
- `WasmBackend`: execute a WASM file as Otoroshi target 
- `WasmResponseTransformer`: transform the content of the response produced by the target
- `WasmSink`: create a sink plugin to handle unmatched requests

To simplify the process of WASM creation and usage, Otoroshi provides:

- `WASM Manager`: a browser code editor to write your plugin in Rust or Assembly Script without having to think about compiling (you can find a complete tutorial of @ref:[How to install and use your own manager](./wasm-manager-installation.md))
- `UI Otoroshi Integration`: a full editor to allow you to select the WASM files generated at each stage of a route creation

@@@ div { .centered-img }
<img src="../imgs/otoroshi-wasm-manager-1.png" />
@@@

## Tutorial

1. [Before your start](#before-your-start)
2. [Create the route with the plugin validator](#create-the-route-with-the-plugin-validator)
3. [Test your validator](#test-your-validator)
4. [Update the target of the route by replacing the target with a WASM file](#update-the-target-of-the-route-by-replacing-the-target-with-a-wasm-file)
5. [Final test](#final-test)

After completing these steps you will have a route using WASM content.

## Before your start

@@include[initialize.md](../includes/initialize.md) { #initialize-otoroshi }

## Create the route with the plugin validator

For this tutorial, we will use a existing wasm file, specially wrote to only let pass requests with a foo header with bar at value. The file is downloadable at the following [https://raw.githubusercontent.com/MAIF/otoroshi/master/demos/wasm/first-validator.wasm](#https://raw.githubusercontent.com/MAIF/otoroshi/master/demos/wasm/first-validator.wasm)

The core of the validator, written in rust, should seem like:

```rust
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

The plugin receives from Otoroshi the context of the request (the matching route, the api key is present, the headers, etc) as `WasmAccessValidatorContext` object. Then it applies a checklist on the headers, and responds an error or success depending on the content of the foo header. Obviously, the previous snippet is an example and the editor allows you to write anything different as a check.

Let's create the route using the previous wasm file.

```sh
curl -X POST "http://otoroshi-api.oto.tools:8080/api/routes" \
-H "Content-type: application/json" \
-u admin-api-apikey-id:admin-api-apikey-secret \
-d @- <<'EOF'
{
  "id": "demo-otoroshi",
  "name": "demo-otoroshi",
  "frontend": {
    "domains": ["demo-otoroshi.oto.tools"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "mirror.otoroshi.io",
        "port": 443,
        "tls": true
      }
    ],
    "load_balancing": {
      "type": "RoundRobin"
    }
  },
  "plugins": [
    {
      "plugin": "cp:otoroshi.next.plugins.OverrideHost",
      "enabled": true
    },
    {
      "plugin": "cp:otoroshi.next.plugins.WasmAccessValidator",
      "enabled": true,
      "config": {
        "raw_source": "https://raw.githubusercontent.com/MAIF/otoroshi/master/demos/wasm/first-validator.wasm",
        "functionName": "execute"
      }
    }
  ]
}
EOF
```

This request will apply the following process:

* names the route *demo-otoroshi*
* creates a frontend exposed on the *demo-otoroshi.oto.tools* domain
* balances requests on one target, the service reachable on *mirror.otoroshi.io*
* adds the *WasmAccessValidator* plugin to validate access based on the foo header to our route

You can validation the route creation by navigating to the [dashboard](http://otoroshi.oto.tools:8080/bo/dashboard/routes/demo-otoroshi-2?tab=flow)

## Test your validator

```shell
curl "http://demo-otoroshi.oto.tools:8080" -I
```

This should output the following error:

```
HTTP/1.1 401 Unauthorized
```

Let's call again the route by adding the header foo with the bar value.

```shell
curl "http://demo-otoroshi.oto.tools:8080" -H "foo:bar" -I
```

This should output the successfull message:

```
HTTP/1.1 200 OK
```

## Update the target of the route by replacing the target with a WASM file

The next step in this tutorial is to use a WASM file as the target of our route. We will use an existing WASM file, available in our wasm demos repository on github. The content of this plugin, called `wasm-target.wasm`, looks like:

```rust
mod types;

use extism_pdk::*;
use std::collections::HashMap;

#[plugin_fn]
pub fn execute(Json(context): Json<types::WasmQueryContext>) -> FnResult<Json<types::WasmQueryResponse>> {
    let mut headers = HashMap::new();
    headers.insert("foo".to_string(), "bar".to_string());

    let response = types::WasmQueryResponse { 
      headers: Some(headers.into_iter().chain(context.raw_request.headers).collect()), 
      body: "{\"foo\": \"bar\"}".to_owned(),
      status: 200
    };
  
    Ok(Json(response))
}
```

Let's explain this snippet. The purpose of this type of plugin is to respond an HTTP response with http status, body and headers map.

1. Includes all public structures from `types.rs` file. This file contains predefined Otoroshi objects that plugins should manipulate.
2. Necessary imports. [Extism](https://extism.org/docs/overview)'s goal is to make all software programmable by providing a plug-in system. 
3. Creates a map of new headers that will be merged with incoming request headers.
4. Creates the response object with the map of merged headers, a simple JSON body and a successfull status code.

The file is downloadable at the following [URL](#https://raw.githubusercontent.com/MAIF/otoroshi/master/demos/wasm/wasm-target.wasm).

Let's update the route using the previous wasm file.

```sh
curl -X PUT "http://otoroshi-api.oto.tools:8080/api/routes/demo-otoroshi" \
-H "Content-type: application/json" \
-u admin-api-apikey-id:admin-api-apikey-secret \
-d @- <<'EOF'
{
  "id": "demo-otoroshi",
  "name": "demo-otoroshi",
  "frontend": {
    "domains": ["demo-otoroshi.oto.tools"]
  },
  "backend": {
    "targets": [
      {
        "hostname": "mirror.otoroshi.io",
        "port": 443,
        "tls": true
      }
    ],
    "load_balancing": {
      "type": "RoundRobin"
    }
  },
  "plugins": [
    {
      "plugin": "cp:otoroshi.next.plugins.OverrideHost",
      "enabled": true
    },
    {
      "plugin": "cp:otoroshi.next.plugins.WasmAccessValidator",
      "enabled": true,
      "config": {
        "raw_source": "https://raw.githubusercontent.com/MAIF/otoroshi/master/demos/wasm/first-validator.wasm",
        "functionName": "execute"
      }
    },
    {
      "plugin": "cp:otoroshi.next.plugins.WasmBackend",
      "enabled": true,
      "config": {
        "raw_source": "https://raw.githubusercontent.com/MAIF/otoroshi/master/demos/wasm/wasm-target.wasm",
        "functionName": "execute"
      }
    }
  ]
}
EOF
```

This should show the updated route content.

## Final test

Let's call our route.

```sh
curl "http://demo-otoroshi.oto.tools:8080" -H "foo:bar" -H "fifi: foo" -v
```

This should output:

```
*   Trying 127.0.0.1:8080...
* Connected to demo-otoroshi.oto.tools (127.0.0.1) port 8080 (#0)
> GET / HTTP/1.1
> Host: demo-otoroshi.oto.tools:8080
> User-Agent: curl/7.79.1
> Accept: */*
> foo:bar
> fifi:foo
>
* Mark bundle as not supporting multiuse
< HTTP/1.1 200 OK
< foo: bar
< Host: demo-otoroshi.oto.tools:8080
<
* Closing connection 0
{"foo": "bar"}
```

In this response, we can find our headers send in the curl command and those added by the wasm plugin.



