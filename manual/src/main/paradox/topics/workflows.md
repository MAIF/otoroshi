# Otoroshi Workflows

Workflows in Otoroshi provide a powerful way to describe and execute sequences of logic using a JSON-based pseudo-language. Think of it as a lightweight alternative to tools like n8n or Apache NiFi — without the GUI (yet). Workflows let you orchestrate actions, transform data, trigger functions, and control flow based on conditional logic, all within the Otoroshi ecosystem.

## Key Concepts

There are three primary building blocks:

* **Nodes**: The execution units that form the workflow steps.
* **Functions**: Reusable logic units that can be invoked by `call` nodes.
* **Operators**: Data transformation helpers that let you compute, compare, transform, and extract values within the workflow.

Otoroshi comes with a set of default implementations, but you can easily extend workflows with custom plugins:

```scala
WorkflowFunction.registerFunction("custom.function", new MyFunction())
Node.registerNode("custom-node", new MyNode())
WorkflowOperator.registerOperator("$custom_op", new MyOperator())
```

## Basic Example

```json
{
  "kind": "workflow",
  "steps": [
    {
      "kind": "call",
      "description": "Say hello to the name passed as input",
      "function": "core.hello",
      "args": {
        "name": "${workflow_input.name}"
      },
      "result": "call_res"
    }
  ],
  "returned": {
    "$mem_ref": {
      "name": "call_res"
    }
  }
}
```

Input:

```json
{
  "name": "foo"
}
```

Output:

```json
{
  "returned": "Hello foo !",
  "error": null
}
```

Memory state:

```json
{
  "call_res": "Hello foo !",
  "input": {
    "name": "foo"
  }
}
```

## Memory

Each workflow execution comes with its own **memory**, where variables can be read from or written to. This memory enables communication between steps.

The `assign` node lets you manipulate memory directly, often combined with operators to compute values.

At any moment you can access the memory using an expression language like `${workflow_input.name}` given a memory containing something like:

```json
{
  "input": {
    "name": "foo"
  }
}
```

## Nodes

Each node must declare a `kind` field and can optionally define:

 - `description` (`string`) - The description of what this node does in the workflow (optional). for debug purposes only
 - `result` (`string`) - The name of the memory that will be assigned with the result of this node (optional)
 - `enabled` (`boolean`) - Is the node enabled (optional)
 - `returned` (`string`) - Overrides the output of the node with the result of an operator (optional)
 - `id` (`string`) - id of the node (optional). for debug purposes only
 - `kind` (`string`) - The kind of the node
 - required fields are: **kind**

### Full List of Nodes


#### <span class="fas fa-code-branch"></span> `Workflow (workflow)`

This node executes a sequence of nodes sequentially

expected configuration:

 - `description` (`string`) - The description of what this node does in the workflow (optional). for debug purposes only
 - `result` (`string`) - The name of the memory that will be assigned with the result of this node (optional)
 - `enabled` (`boolean`) - Is the node enabled (optional)
 - `returned` (`string`) - Overrides the output of the node with the result of an operator (optional)
 - `id` (`string`) - id of the node (optional). for debug purposes only
 - `steps` (`array`) - the nodes to be executed
 - `kind` (`string`) - The kind of the node
 - required fields are: **kind**

Usage example

```json
{
  "kind" : "workflow",
  "description" : "This node executes says hello, waits and says hello again",
  "steps" : [ {
    "kind" : "call",
    "function" : "core.hello"
  }, {
    "kind" : "wait",
    "duration" : 10000
  }, {
    "kind" : "call",
    "function" : "core.hello"
  } ]
}
```


---


#### <span class="fas fa-exchange-alt"></span> `Switch paths (switch)`

This node executes the first path matching a predicate

expected configuration:

 - `description` (`string`) - The description of what this node does in the workflow (optional). for debug purposes only
 - `result` (`string`) - The name of the memory that will be assigned with the result of this node (optional)
 - `enabled` (`boolean`) - Is the node enabled (optional)
 - `paths` (`array`) - the nodes to be executed
     - `predicate` (`boolean`) - The predicate defining if the path is run or not
     - required fields are: 
 - `returned` (`string`) - Overrides the output of the node with the result of an operator (optional)
 - `id` (`string`) - id of the node (optional). for debug purposes only
 - `kind` (`string`) - The kind of the node
 - required fields are: **kind**

Usage example

```json
{
  "kind" : "switch",
  "description" : "This node will say 'Hello Otoroshi 1'",
  "paths" : [ {
    "predicate" : true,
    "kind" : "call",
    "function" : "core.hello",
    "args" : {
      "name" : "Otoroshi 1"
    }
  }, {
    "predicate" : false,
    "kind" : "call",
    "function" : "core.hello",
    "args" : {
      "name" : "Otoroshi 2"
    }
  } ]
}
```


---


#### <span class="fas fa-pause"></span> `Pause (pause)`

This node pauses the current workflow

expected configuration:

 - `description` (`string`) - The description of what this node does in the workflow (optional). for debug purposes only
 - `result` (`string`) - The name of the memory that will be assigned with the result of this node (optional)
 - `enabled` (`boolean`) - Is the node enabled (optional)
 - `returned` (`string`) - Overrides the output of the node with the result of an operator (optional)
 - `id` (`string`) - id of the node (optional). for debug purposes only
 - `kind` (`string`) - The kind of the node
 - required fields are: **kind**

Usage example

```json
{
  "kind" : "pause",
  "description" : "Pause the workflow at this point."
}
```


---


#### <span class="fas fa-clock"></span> `Wait (wait)`

This node waits a certain amount of time

expected configuration:

 - `description` (`string`) - The description of what this node does in the workflow (optional). for debug purposes only
 - `result` (`string`) - The name of the memory that will be assigned with the result of this node (optional)
 - `enabled` (`boolean`) - Is the node enabled (optional)
 - `returned` (`string`) - Overrides the output of the node with the result of an operator (optional)
 - `id` (`string`) - id of the node (optional). for debug purposes only
 - `kind` (`string`) - The kind of the node
 - required fields are: **kind**

Usage example

```json
{
  "kind" : "wait",
  "description" : "This node waits 20 seconds",
  "duration" : 20000
}
```


---


#### <span class="fas fa-code-branch"></span> `Parallel paths (parallel)`

This node executes multiple nodes in parallel

expected configuration:

 - `description` (`string`) - The description of what this node does in the workflow (optional). for debug purposes only
 - `result` (`string`) - The name of the memory that will be assigned with the result of this node (optional)
 - `enabled` (`boolean`) - Is the node enabled (optional)
 - `paths` (`array`) - the nodes to be executed

     - required fields are: 
 - `returned` (`string`) - Overrides the output of the node with the result of an operator (optional)
 - `id` (`string`) - id of the node (optional). for debug purposes only
 - `kind` (`string`) - The kind of the node
 - required fields are: **kind**

Usage example

```json
{
  "kind" : "parallel",
  "description" : "This node call 3 core.hello function in parallel",
  "paths" : [ {
    "kind" : "call",
    "function" : "core.hello",
    "args" : {
      "name" : "Otoroshi 1"
    }
  }, {
    "kind" : "call",
    "function" : "core.hello",
    "args" : {
      "name" : "Otoroshi 2"
    }
  }, {
    "kind" : "call",
    "function" : "core.hello",
    "args" : {
      "name" : "Otoroshi 3"
    }
  } ]
}
```


---


#### <span class="fas fa-layer-group"></span> `Flatmap (flatmap)`

This node transforms an array by applying a node on each value

expected configuration:

 - `description` (`string`) - The description of what this node does in the workflow (optional). for debug purposes only
 - `result` (`string`) - The name of the memory that will be assigned with the result of this node (optional)
 - `enabled` (`boolean`) - Is the node enabled (optional)
 - `returned` (`string`) - Overrides the output of the node with the result of an operator (optional)
 - `id` (`string`) - id of the node (optional). for debug purposes only
 - `kind` (`string`) - The kind of the node
 - required fields are: **kind**

Usage example

```json
{
  "kind" : "map",
  "description" : "This node extract all emails from users",
  "values" : "${users}",
  "node" : {
    "kind" : "value",
    "value" : "${foreach_value.emails}"
  }
}
```


---


#### <span class="fas fa-map"></span> `Map (map)`

This node transforms an array by applying a node on each value

expected configuration:

 - `description` (`string`) - The description of what this node does in the workflow (optional). for debug purposes only
 - `result` (`string`) - The name of the memory that will be assigned with the result of this node (optional)
 - `enabled` (`boolean`) - Is the node enabled (optional)
 - `returned` (`string`) - Overrides the output of the node with the result of an operator (optional)
 - `id` (`string`) - id of the node (optional). for debug purposes only
 - `kind` (`string`) - The kind of the node
 - required fields are: **kind**

Usage example

```json
{
  "kind" : "map",
  "description" : "This node will transform user names",
  "values" : "${users}",
  "node" : {
    "kind" : "value",
    "value" : {
      "$str_upper_case" : "${foreach_value.name}"
    }
  }
}
```


---


#### <span class="fas fa-sync"></span> `For each (foreach)`

This node executes a node for each element in an array

expected configuration:

 - `description` (`string`) - The description of what this node does in the workflow (optional). for debug purposes only
 - `result` (`string`) - The name of the memory that will be assigned with the result of this node (optional)
 - `enabled` (`boolean`) - Is the node enabled (optional)
 - `returned` (`string`) - Overrides the output of the node with the result of an operator (optional)
 - `id` (`string`) - id of the node (optional). for debug purposes only
 - `kind` (`string`) - The kind of the node
 - required fields are: **kind**

Usage example

```json
{
  "kind" : "foreach",
  "description" : "This node execute the core.hello function for each user",
  "values" : "${users}",
  "node" : {
    "kind" : "call",
    "function" : "core.hello",
    "args" : {
      "name" : "${foreach_value.name}"
    }
  }
}
```


---


#### <span class="fas fa-dollar-sign"></span> `Assign in memory (assign)`

This node with executes a sequence of memory assignation operations sequentially

expected configuration:

 - `description` (`string`) - The description of what this node does in the workflow (optional). for debug purposes only
 - `result` (`string`) - The name of the memory that will be assigned with the result of this node (optional)
 - `enabled` (`boolean`) - Is the node enabled (optional)
 - `returned` (`string`) - Overrides the output of the node with the result of an operator (optional)
 - `id` (`string`) - id of the node (optional). for debug purposes only
 - `values` (`array`) - the assignation operations sequence
     - `name` (`string`) - the name of the assignment in memory
     - `value` (`any`) - the value of the assignment
     - required fields are: **name**, **value**
 - `kind` (`string`) - The kind of the node
 - required fields are: **kind**

Usage example

```json
{
  "kind" : "assign",
  "description" : "set a counter, increment it of 2 and initialize an array in memory",
  "values" : [ {
    "name" : "count",
    "value" : 0
  }, {
    "name" : "count",
    "value" : {
      "$incr" : {
        "value" : "count",
        "increment" : 2
      }
    }
  }, {
    "name" : "items",
    "value" : [ 1, 2, 3 ]
  } ]
}
```


---


#### <span class="fas fa-font"></span> `Value (value)`

This node returns a value

expected configuration:

 - `description` (`string`) - The description of what this node does in the workflow (optional). for debug purposes only
 - `result` (`string`) - The name of the memory that will be assigned with the result of this node (optional)
 - `enabled` (`boolean`) - Is the node enabled (optional)
 - `returned` (`string`) - Overrides the output of the node with the result of an operator (optional)
 - `id` (`string`) - id of the node (optional). for debug purposes only
 - `kind` (`string`) - The kind of the node
 - `value` (`any`) - the returned value
 - required fields are: **kind**

Usage example

```json
{
  "kind" : "value",
  "description" : "This node returns 'foo'",
  "value" : "foo"
}
```


---


#### <span class="fas fa-question"></span> `If then else (if)`

This executes a node if the predicate matches or another one if not

expected configuration:

 - `predicate` (`boolean`) - The predicate defining if the path is run or not
 - `description` (`string`) - The description of what this node does in the workflow (optional). for debug purposes only
 - `result` (`string`) - The name of the memory that will be assigned with the result of this node (optional)
 - `enabled` (`boolean`) - Is the node enabled (optional)
 - `else` (`object`) - The node run if the predicate does not matches
 - `returned` (`string`) - Overrides the output of the node with the result of an operator (optional)
 - `id` (`string`) - id of the node (optional). for debug purposes only
 - `then` (`object`) - The node run if the predicate matches
 - `kind` (`string`) - The kind of the node
 - required fields are: **kind**

Usage example

```json
{
  "kind" : "if",
  "description" : "This node will say 'Hello Otoroshi 1'",
  "predicate" : "${truthyValueInMemory}",
  "then" : {
    "kind" : "call",
    "function" : "core.hello",
    "args" : {
      "name" : "Otoroshi 1"
    }
  },
  "else" : {
    "kind" : "call",
    "function" : "core.hello",
    "args" : {
      "name" : "Otoroshi 2"
    }
  }
}
```


---


#### <span class="fas fa-exclamation"></span> `Stop and Error (error)`

This node returns an error

expected configuration:

 - `description` (`string`) - The description of what this node does in the workflow (optional). for debug purposes only
 - `result` (`string`) - The name of the memory that will be assigned with the result of this node (optional)
 - `enabled` (`boolean`) - Is the node enabled (optional)
 - `returned` (`string`) - Overrides the output of the node with the result of an operator (optional)
 - `id` (`string`) - id of the node (optional). for debug purposes only
 - `kind` (`string`) - The kind of the node
 - required fields are: **kind**

Usage example

```json
{
  "kind" : "error",
  "description" : "This node fails the workflow with an error",
  "message" : "an error occurred",
  "details" : {
    "foo" : "bar"
  }
}
```


---


#### <span class="fas fa-code"></span> `Call (call)`

This node calls a function an returns its result

expected configuration:

 - `description` (`string`) - The description of what this node does in the workflow (optional). for debug purposes only
 - `result` (`string`) - The name of the memory that will be assigned with the result of this node (optional)
 - `enabled` (`boolean`) - Is the node enabled (optional)
 - `returned` (`string`) - Overrides the output of the node with the result of an operator (optional)
 - `id` (`string`) - id of the node (optional). for debug purposes only
 - `function` (`string`) - the function name
 - `args` (`object`) - the arguments of the call
 - `kind` (`string`) - The kind of the node
 - required fields are: **kind**

Usage example

```json
{
  "kind" : "call",
  "description" : "This node call the core.hello function",
  "function" : "core.hello",
  "args" : {
    "name" : "Otoroshi"
  }
}
```


---


#### <span class="fas fa-filter"></span> `Filter (filter)`

This node transforms an array by filtering values based on a node execution

expected configuration:

 - `description` (`string`) - The description of what this node does in the workflow (optional). for debug purposes only
 - `result` (`string`) - The name of the memory that will be assigned with the result of this node (optional)
 - `enabled` (`boolean`) - Is the node enabled (optional)
 - `returned` (`string`) - Overrides the output of the node with the result of an operator (optional)
 - `id` (`string`) - id of the node (optional). for debug purposes only
 - `kind` (`string`) - The kind of the node
 - required fields are: **kind**

Usage example

```json
{
  "kind" : "filter",
  "description" : "This node will filter out users that are not admins",
  "values" : "${users}",
  "predicate" : {
    "kind" : "value",
    "value" : "${foreach_value.admin}"
  }
}
```


## Functions

Workflow functions are reusable logic blocks invoked via `call` nodes.

Prototype:

```json
{
  "kind": "call",
  "function": "<function_name>",
  "args": { ... },
  "result": "<memory_var_name>"
}
```

### Full List of Functions


#### <span class="fas fa-circle"></span>`otoroshi_plugins.com.cloud.apim.otoroshi.extensions.biscuit.BiscuitForgeFunction (biscuit.extensions.cloud-apim.com.biscuit_forge)`

no description

expected configuration:



---


#### <span class="fas fa-circle"></span>`com.cloud.apim.otoroshi.extensions.aigateway.GenerateImageFunction (extensions.com.cloud-apim.llm-extension.image_generate)`

no description

expected configuration:



---


#### <span class="fas fa-cogs"></span>`Read from Otoroshi config. (core.config_read)`

This function retrieves values from otoroshi config.

expected configuration:

 - `path` (`string`) - The path of the config. to read
 - required fields are: **path**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.config_read",
  "args" : {
    "path" : "otoroshi.domain"
  }
}
```


---


#### <span class="fas fa-circle"></span>`com.cloud.apim.otoroshi.extensions.aigateway.GenerateVideoFunction (extensions.com.cloud-apim.llm-extension.video_generate)`

no description

expected configuration:



---


#### <span class="fas fa-download"></span>`Datastore get (core.store_get)`

This function gets keys from the store

expected configuration:

 - `key` (`string`) - The key to get
 - required fields are: **key**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.store_get",
  "args" : {
    "key" : "my_key"
  }
}
```


---


#### <span class="fas fa-file-alt"></span>`Read a file (core.file_read)`

This function reads a file

expected configuration:

 - `path` (`string`) - The path of the file to read
 - `parse_json` (`boolean`) - Whether to parse the file as JSON
 - `encode_base64` (`boolean`) - Whether to encode the file content in base64
 - required fields are: **path**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.file_read",
  "args" : {
    "path" : "/path/to/file.txt",
    "parse_json" : true,
    "encode_base64" : true
  }
}
```


---


#### <span class="fas fa-hand-paper"></span>`Hello function (core.hello)`

This function returns a hello message

expected configuration:

 - `name` (`string`) - The name of the person to greet
 - required fields are: **name**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.hello",
  "args" : {
    "name" : "Otoroshi"
  }
}
```


---


#### <span class="fas fa-search"></span>`Datastore matching keys (core.store_match)`

This function gets keys from the datastore matching a pattern

expected configuration:

 - `pattern` (`string`) - The pattern to match
 - required fields are: **pattern**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.store_match",
  "args" : {
    "pattern" : "my_pattern:*"
  }
}
```


---


#### <span class="fas fa-upload"></span>`Datastore set (core.store_set)`

This function sets a key in the datastore

expected configuration:

 - `key` (`string`) - The key to set
 - `value` (`string`) - The value to set
 - `ttl` (`number`) - The optional time to live in seconds
 - required fields are: **key**, **value**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.store_set",
  "args" : {
    "key" : "my_key",
    "value" : "my_value",
    "ttl" : 3600
  }
}
```


---


#### <span class="fas fa-leaf"></span>`Get environment variable (core.env_get)`

This function retrieves values from environment variables

expected configuration:

 - `name` (`string`) - The environment variable name
 - required fields are: **name**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.env_get",
  "args" : {
    "name" : "OPENAI_APIKEY"
  }
}
```


---


#### <span class="fas fa-circle"></span>`otoroshi_plugins.com.cloud.apim.otoroshi.extensions.biscuit.BiscuitKeypairGenFunction (biscuit.extensions.cloud-apim.com.biscuit_keypair_gen)`

no description

expected configuration:



---


#### <span class="fas fa-circle"></span>`com.cloud.apim.otoroshi.extensions.aigateway.ModerationCallFunction (extensions.com.cloud-apim.llm-extension.moderation_call)`

no description

expected configuration:



---


#### <span class="fas fa-cogs"></span>`Compute a resume token for the current workflow (core.compute_resume_token)`

This function computes a resume token for the current workflow

expected configuration:



Usage example

```json
{
  "kind" : "call",
  "function" : "core.compute_resume_token",
  "args" : { }
}
```


---


#### <span class="fas fa-circle"></span>`com.cloud.apim.otoroshi.extensions.aigateway.CallToolFunctionFunction (extensions.com.cloud-apim.llm-extension.tool_function_call)`

no description

expected configuration:



---


#### <span class="fas fa-cube"></span>`Wasm call (core.wasm_call)`

This function calls a wasm function

expected configuration:

 - `wasm_plugin` (`string`) - The wasm plugin to use
 - `function` (`string`) - The function to call
 - `params` (`object`) - The parameters to passed to the function
 - required fields are: **wasm_plugin**, **function**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.wasm_call",
  "args" : {
    "wasm_plugin" : "my_wasm_plugin",
    "function" : "my_function",
    "params" : {
      "foo" : "bar"
    }
  }
}
```


---


#### <span class="fas fa-trash"></span>`Delete a file (core.file_del)`

This function deletes a file

expected configuration:

 - `path` (`string`) - The path of the file to delete
 - required fields are: **path**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.file_delete",
  "args" : {
    "path" : "/path/to/file.txt"
  }
}
```


---


#### <span class="fas fa-key"></span>`Datastore list keys (core.store_keys)`

This function lists keys from the datastore

expected configuration:

 - `pattern` (`string`) - The pattern to match
 - required fields are: **pattern**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.store_keys",
  "args" : {
    "pattern" : "my_pattern:*"
  }
}
```


---


#### <span class="fas fa-circle"></span>`com.cloud.apim.otoroshi.extensions.aigateway.AudioTtsFunction (extensions.com.cloud-apim.llm-extension.audio_tts)`

no description

expected configuration:



---


#### <span class="fas fa-circle"></span>`com.cloud.apim.otoroshi.extensions.aigateway.LlmCallFunction (extensions.com.cloud-apim.llm-extension.llm_call)`

no description

expected configuration:



---


#### <span class="fas fa-clipboard-list"></span>`Log a message (core.log)`

This function writes whatever the user want to the otoroshi logs

expected configuration:

 - `message` (`string`) - The message to log
 - `params` (`array`) - The parameters to log
 - required fields are: **message**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.log",
  "args" : {
    "message" : "Hello",
    "params" : [ "World" ]
  }
}
```


---


#### <span class="fas fa-circle"></span>`com.cloud.apim.otoroshi.extensions.aigateway.ComputeEmbeddingFunction (extensions.com.cloud-apim.llm-extension.embedding_compute)`

no description

expected configuration:



---


#### <span class="fas fa-circle"></span>`otoroshi_plugins.com.cloud.apim.otoroshi.extensions.biscuit.BiscuitVerifyFunction (biscuit.extensions.cloud-apim.com.biscuit_verify)`

no description

expected configuration:



---


#### <span class="fas fa-network-wired"></span>`HTTP client (core.http_client)`

This function makes a HTTP request

expected configuration:

 - `tls_config` (`object`) - The TLS configuration
 - `method` (`string`) - The HTTP method to use
 - `body` (`string`) - The body (string) to send
 - `url` (`string`) - The URL to call
 - `body_bytes` (`array`) - The body (bytes array) to send
 - `body_json` (`object`) - The body (json) to send
 - `body_str` (`string`) - The body (string) to send
 - `body_base64` (`string`) - The body (base64) to send
 - `headers` (`object`) - The headers to send
 - `timeout` (`number`) - The timeout in milliseconds
 - required fields are: **url**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.http_client",
  "args" : {
    "url" : "https://httpbin.org/get",
    "method" : "GET",
    "headers" : {
      "User-Agent" : "Otoroshi"
    },
    "timeout" : 30000,
    "body_json" : {
      "foo" : "bar"
    }
  }
}
```


---


#### <span class="fas fa-envelope"></span>`Send an email (core.send_mail)`

This function sends an email

expected configuration:

 - `mailer_config` (`object`) - The mailer configuration
 - `subject` (`string`) - The email subject
 - `to` (`array`) - The recipient email addresses
 - `from` (`string`) - The sender email address
 - `html` (`string`) - The email HTML content
 - required fields are: **from**, **to**, **subject**, **html**, **mailer_config**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.send_mail",
  "args" : {
    "from" : "sender@example.com",
    "to" : [ "recipient@example.com" ],
    "subject" : "Test email",
    "html" : "Hello, this is a test email",
    "mailer_config" : {
      "kind" : "mailgun",
      "api_key" : "your_api_key",
      "domain" : "your_domain"
    }
  }
}
```


---


#### <span class="fas fa-terminal"></span>`System call (core.system_call)`

This function calls a system command

expected configuration:

 - `command` (`array`) - The command to execute
 - required fields are: **command**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.system_call",
  "args" : {
    "command" : [ "ls", "-l" ]
  }
}
```


---


#### <span class="fas fa-layer-group"></span>`Get all resources from the state (core.state_get_all)`

This function gets all resources from the state

expected configuration:

 - `name` (`string`) - The name of the resource
 - `group` (`string`) - The group of the resource
 - `version` (`string`) - The version of the resource
 - required fields are: **name**, **group**, **version**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.state_get_all",
  "args" : {
    "name" : "my_resource",
    "group" : "my_group",
    "version" : "my_version"
  }
}
```


---


#### <span class="fas fa-circle"></span>`com.cloud.apim.otoroshi.extensions.aigateway.VectorStoreAddFunction (extensions.com.cloud-apim.llm-extension.vector_store_add)`

no description

expected configuration:



---


#### <span class="fas fa-eraser"></span>`Datastore delete (core.store_del)`

This function deletes keys from the store

expected configuration:

 - `keys` (`array`) - The keys to delete
 - required fields are: **keys**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.store_del",
  "args" : {
    "keys" : [ "key1", "key2" ]
  }
}
```


---


#### <span class="fas fa-circle"></span>`otoroshi_plugins.com.cloud.apim.otoroshi.extensions.biscuit.BiscuitAttenuationFunction (biscuit.extensions.cloud-apim.com.biscuit_attenuation)`

no description

expected configuration:



---


#### <span class="fas fa-file-signature"></span>`Write a file (core.file_write)`

This function writes a file

expected configuration:

 - `path` (`string`) - The path of the file to write
 - `value` (`string`) - The value to write
 - `prettify` (`boolean`) - Whether to prettify the JSON
 - `from_base64` (`boolean`) - Whether to decode the base64 content
 - required fields are: **path**, **value**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.file_write",
  "args" : {
    "path" : "/path/to/file.txt",
    "value" : "my_value",
    "prettify" : true,
    "from_base64" : true
  }
}
```


---


#### <span class="fas fa-circle"></span>`com.cloud.apim.otoroshi.extensions.aigateway.CallMcpFunctionFunction (extensions.com.cloud-apim.llm-extension.mcp_function_call)`

no description

expected configuration:



---


#### <span class="fas fa-project-diagram"></span>`Call a workflow (core.workflow_call)`

This function calls another workflow stored in otoroshi

expected configuration:

 - `workflow_id` (`string`) - The ID of the workflow to call
 - `input` (`object`) - The input of the workflow
 - required fields are: **workflow_id**, **input**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.workflow_call",
  "args" : {
    "workflow_id" : "my_workflow_id",
    "input" : {
      "foo" : "bar"
    }
  }
}
```


---


#### <span class="fas fa-cube"></span>`Get a resource from the state (core.state_get)`

This function gets a resource from the state

expected configuration:

 - `id` (`string`) - The ID of the resource
 - `name` (`string`) - The name of the resource
 - `group` (`string`) - The group of the resource
 - `version` (`string`) - The version of the resource
 - required fields are: **id**, **name**, **group**, **version**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.state_get_one",
  "args" : {
    "id" : "my_id",
    "name" : "my_resource",
    "group" : "my_group",
    "version" : "my_version"
  }
}
```


---


#### <span class="fas fa-boxes"></span>`Datastore get multiple keys (core.store_mget)`

This function gets multiple keys from the datastore

expected configuration:

 - `keys` (`array`) - The keys to get
 - required fields are: **keys**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.store_mget",
  "args" : {
    "keys" : [ "key1", "key2" ]
  }
}
```


---


#### <span class="fas fa-circle"></span>`com.cloud.apim.otoroshi.extensions.aigateway.VectorStoreSearchFunction (extensions.com.cloud-apim.llm-extension.vector_store_search)`

no description

expected configuration:



---


#### <span class="fas fa-bullhorn"></span>`Emit an event (core.emit_event)`

This function emits an event

expected configuration:

 - `event` (`object`) - The event to emit
 - required fields are: **event**

Usage example

```json
{
  "kind" : "call",
  "function" : "core.emit_event",
  "args" : {
    "event" : {
      "type" : "object",
      "properties" : {
        "name" : {
          "type" : "string",
          "description" : "The name of the event"
        }
      }
    }
  }
}
```


---


#### <span class="fas fa-circle"></span>`com.cloud.apim.otoroshi.extensions.aigateway.AudioSttFunction (extensions.com.cloud-apim.llm-extension.audio_stt)`

no description

expected configuration:



---


#### <span class="fas fa-circle"></span>`com.cloud.apim.otoroshi.extensions.aigateway.VectorStoreRemoveFunction (extensions.com.cloud-apim.llm-extension.vector_store_remove)`

no description

expected configuration:



## Operators

Operators are one-key JSON objects (e.g. `{ "$eq": { ... } }`) used to manipulate data. They can:

* Navigate and extract memory (`$mem_ref`)
* Transform strings (`$str_concat`, `$encode_base64`, `$decode_base64`)
* Compare values (`$eq`, `$gt`, `$lt`, `$gte`, `$lte`, `$neq`)
* Evaluate truthiness (`$is_truthy`, `$is_falsy`, `$not`)
* Work with arrays/maps (`$array_append`, `$array_prepend`, `$array_at`, `$array_del`, `$array_page`, `$map_get`, `$map_put`, `$map_del`)
* Perform math (`$add`, `$subtract`, `$multiply`, `$divide`)
* Parse and format dates/times (`$parse_datetime`, `$parse_date`, `$parse_time`, `$now`)
* Parse and project JSON (`$json_parse`, `$projection`)
* Perform expression evaluation (`$expression_language`)
* Create auth headers (`$basic_auth`)
* Check containment (`$contains`)

Example:

```json
{
  "$eq": {
    "a": "foo",
    "b": "bar"
  }
}
```

Result: `false`

### Full List of Operators


#### <span class="fas fa-shield-alt"></span> `Basic Auth ($basic_auth)`

This operator returns a basic authentication header

expected configuration:

 - `user` (`string`) - The username
 - `password` (`string`) - The password
 - required fields are: **user**, **password**

Usage example

```json
{
  "$basic_auth" : {
    "user" : "username",
    "password" : "password"
  }
}
```


---


#### <span class="fas fa-equals"></span> `Equal to ($eq)`

This operator checks if two values are equal

expected configuration:

 - `a` (`any`) - The first value
 - `b` (`any`) - The second value
 - required fields are: **a**, **b**

Usage example

```json
{
  "$eq" : {
    "a" : 1,
    "b" : 1
  }
}
```


---


#### <span class="fas fa-clock"></span> `Now ($now)`

This operator returns the current timestamp

expected configuration:



Usage example

```json
{
  "$now" : { }
}
```


---


#### <span class="fas fa-times-circle"></span> `Is falsy ($is_falsy)`

This operator checks if a value is falsy

expected configuration:

 - `value` (`any`) - The value to check
 - required fields are: **value**

Usage example

```json
{
  "$is_falsy" : {
    "value" : 0
  }
}
```


---


#### <span class="fas fa-plus"></span> `Add ($add)`

This operator adds a list of numbers

expected configuration:


 - required fields are: 

Usage example

```json
{
  "$add" : {
    "values" : [ 1, 2, 3 ]
  }
}
```


---


#### <span class="fas fa-arrow-right"></span> `Array append ($array_append)`

This operator appends a value to an array

expected configuration:

 - `value` (`any`) - The value to append
 - `array` (`array`) - The array to append the value to
 - required fields are: **value**, **array**

Usage example

```json
{
  "$array_append" : {
    "value" : "my_value",
    "array" : [ "my_value" ]
  }
}
```


---


#### <span class="fas fa-not-equal"></span> `Not equal to ($neq)`

This operator checks if two values are not equal

expected configuration:

 - `a` (`any`) - The first value
 - `b` (`any`) - The second value
 - required fields are: **a**, **b**

Usage example

```json
{
  "$neq" : {
    "a" : 1,
    "b" : 2
  }
}
```


---


#### <span class="fas fa-list-ol"></span> `Array at ($array_at)`

This operator gets an element from an array

expected configuration:

 - `idx` (`integer`) - The index of the element to get
 - `array` (`array`) - The array to get the element from
 - required fields are: **idx**, **array**

Usage example

```json
{
  "$array_at" : {
    "idx" : 0,
    "array" : [ "my_value" ]
  }
}
```


---


#### <span class="fas fa-calendar-alt"></span> `Parse Date ($parse_date)`

This operator parses a date string into a timestamp

expected configuration:

 - `value` (`string`) - The date string to parse
 - `pattern` (`string`) - The pattern to use for parsing
 - required fields are: **value**, **pattern**

Usage example

```json
{
  "$parse_date" : {
    "value" : "2023-01-01",
    "pattern" : "yyyy-MM-dd"
  }
}
```


---


#### <span class="fas fa-clock"></span> `Parse DateTime ($parse_datetime)`

This operator parses a datetime string into a timestamp

expected configuration:

 - `value` (`string`) - The datetime string to parse
 - `pattern` (`string`) - The pattern to use for parsing
 - required fields are: **value**, **pattern**

Usage example

```json
{
  "$parse_datetime" : {
    "value" : "2023-01-01T00:00:00",
    "pattern" : "yyyy-MM-dd'T'HH:mm:ss"
  }
}
```


---


#### <span class="fas fa-file-alt"></span> `Array page ($array_page)`

This operator gets a page of an array

expected configuration:

 - `page` (`integer`) - The page number
 - `page_size` (`integer`) - The page size
 - `array` (`array`) - The array to get the page from
 - required fields are: **page**, **page_size**, **array**

Usage example

```json
{
  "$array_page" : {
    "page" : 1,
    "page_size" : 10,
    "array" : [ "my_value" ]
  }
}
```


---


#### <span class="fas fa-code"></span> `String Replace ($str_replace)`

This operator replace values inside a string

expected configuration:

 - `value` (`string`) - The string with parts to replace
 - `target` (`string`) - The value replaced
 - `replacement` (`string`) - The value to replace with
 - required fields are: **value**, **target**, **replacement**

Usage example

```json
{
  "$str_replace" : {
    "value" : "Hello World!",
    "target" : "Hello",
    "replacement" : "Goodbye"
  }
}
```


---


#### <span class="fas fa-search"></span> `Map get ($map_get)`

This operator gets a value from a map

expected configuration:

 - `key` (`string`) - The key to get
 - `map` (`object`) - The map to get the value from
 - required fields are: **key**, **map**

Usage example

```json
{
  "$map_get" : {
    "key" : "my_key",
    "map" : {
      "my_key" : "my_value"
    }
  }
}
```


---


#### <span class="fas fa-clock"></span> `Parse Time ($parse_time)`

This operator parses a time string into a timestamp

expected configuration:

 - `value` (`string`) - The time string to parse
 - `pattern` (`string`) - The pattern to use for parsing
 - required fields are: **value**, **pattern**

Usage example

```json
{
  "$parse_time" : {
    "value" : "00:00:00",
    "pattern" : "HH:mm:ss"
  }
}
```


---


#### <span class="fas fa-memory"></span> `Memory reference ($mem_ref)`

This operator gets a value from the memory

expected configuration:


 - required fields are: 

Usage example

```json
{
  "$memref" : {
    "name" : "my_memory",
    "path" : "my_path"
  }
}
```


---


#### <span class="fas fa-arrow-down"></span> `Lowercase ($str_lower_case)`

This operator converts a string to lowercase

expected configuration:

 - `value` (`string`) - The string to convert to lowercase
 - required fields are: **value**

Usage example

```json
{
  "$str_lower_case" : {
    "value" : "hello"
  }
}
```


---


#### <span class="fas fa-lock"></span> `Encode Base64 ($encode_base64)`

This operator encodes a string in base64

expected configuration:

 - `value` (`string`) - The string to encode in base64
 - required fields are: **value**

Usage example

```json
{
  "$encode_base64" : {
    "value" : "Hello, World!"
  }
}
```


---


#### <span class="fas fa-code"></span> `Stringify ($stringify)`

This operator stringify a json value

expected configuration:

 - `value` (`any`) - The json to convert to string
 - required fields are: **value**

Usage example

```json
{
  "$stringify" : {
    "value" : {
      "foo" : "bar"
    }
  }
}
```


---


#### <span class="fas fa-code"></span> `Expression Language ($expression_language)`

This operator evaluates an expression language

expected configuration:

 - `expression` (`string`) - The expression to evaluate
 - required fields are: **expression**

Usage example

```json
{
  "$expression_language" : {
    "expression" : "${req.headers.X-Custom-Header}"
  }
}
```


---


#### <span class="fas fa-exclamation"></span> `Not ($not)`

This operator negates a boolean value

expected configuration:

 - `value` (`boolean`) - The boolean value to negate
 - required fields are: **value**

Usage example

```json
{
  "$not" : {
    "value" : true
  }
}
```


---


#### <span class="fas fa-filter"></span> `Projection ($projection)`

This operator projects a value

expected configuration:

 - `projection` (`object`) - The projection to apply
 - `value` (`object`) - The value to project
 - required fields are: **projection**, **value**

Usage example

```json
{
  "$projection" : {
    "projection" : {
      "name" : "my_name"
    },
    "value" : {
      "name" : "my_name"
    }
  }
}
```


---


#### <span class="fas fa-less-than-equal"></span> `Less than or equal to ($lte)`

This operator checks if a number is less than or equal to another number

expected configuration:

 - `a` (`number`) - The first number
 - `b` (`number`) - The second number
 - required fields are: **a**, **b**

Usage example

```json
{
  "$lte" : {
    "a" : 1,
    "b" : 0
  }
}
```


---


#### <span class="fas fa-cut"></span> `String Split ($str_split)`

This operator splits a string into an array based on a regex

expected configuration:

 - `value` (`string`) - The string to split
 - `regex` (`string`) - The regex to use for splitting
 - required fields are: **value**, **regex**

Usage example

```json
{
  "$str_split" : {
    "value" : "hello,world",
    "regex" : ","
  }
}
```


---


#### <span class="fas fa-arrow-left"></span> `Array prepend ($array_prepend)`

This operator prepends a value to an array

expected configuration:

 - `value` (`any`) - The value to prepend
 - `array` (`array`) - The array to prepend the value to
 - required fields are: **value**, **array**

Usage example

```json
{
  "$array_prepend" : {
    "value" : "my_value",
    "array" : [ "my_value" ]
  }
}
```


---


#### <span class="fas fa-unlock"></span> `Decode Base64 ($decode_base64)`

This operator decodes a base64 string

expected configuration:

 - `value` (`string`) - The base64 string to decode
 - required fields are: **value**

Usage example

```json
{
  "$decode_base64" : {
    "value" : "SGVsbG8sIFdvcmxkIQ=="
  }
}
```


---


#### <span class="fas fa-minus"></span> `Subtract ($subtract)`

This operator subtracts a list of numbers

expected configuration:

 - `values` (`array`) - The list of numbers to subtract
 - required fields are: **values**

Usage example

```json
{
  "$subtract" : {
    "values" : [ 1, 2, 3 ]
  }
}
```


---


#### <span class="fas fa-file-code"></span> `JSON parse ($json_parse)`

This operator parses a JSON string

expected configuration:

 - `value` (`string`) - The JSON string to parse
 - required fields are: **value**

Usage example

```json
{
  "$json_parse" : {
    "value" : "{}"
  }
}
```


---


#### <span class="fas fa-search-plus"></span> `Contains ($contains)`

This operator checks if a value is contained in a container

expected configuration:

 - `value` (`any`) - The value to check
 - `container` (`any`) - The container to check
 - required fields are: **value**, **container**

Usage example

```json
{
  "$contains" : {
    "value" : 1,
    "container" : [ 1, 2, 3 ]
  }
}
```


---


#### <span class="fas fa-greater-than-equal"></span> `Greater than or equal to ($gte)`

This operator checks if a number is greater than or equal to another number

expected configuration:

 - `a` (`number`) - The first number
 - `b` (`number`) - The second number
 - required fields are: **a**, **b**

Usage example

```json
{
  "$gte" : {
    "a" : 1,
    "b" : 0
  }
}
```


---


#### <span class="fas fa-plus-circle"></span> `Increment ($incr)`

This operator increments a value by a given amount

expected configuration:

 - `value` (`number`) - The value to increment
 - `increment` (`number`) - The amount to increment by
 - required fields are: **value**, **increment**

Usage example

```json
{
  "$incr" : {
    "value" : 10,
    "increment" : 5
  }
}
```


---


#### <span class="fas fa-less-than"></span> `Less than ($lt)`

This operator checks if a number is less than another number

expected configuration:

 - `a` (`number`) - The first number
 - `b` (`number`) - The second number
 - required fields are: **a**, **b**

Usage example

```json
{
  "$lt" : {
    "a" : 1,
    "b" : 0
  }
}
```


---


#### <span class="fas fa-divide"></span> `Divide ($divide)`

This operator divides a list of numbers

expected configuration:

 - `values` (`array`) - The list of numbers to divide
 - required fields are: **values**

Usage example

```json
{
  "$divide" : {
    "values" : [ 1, 2, 3 ]
  }
}
```


---


#### <span class="fas fa-plus-square"></span> `Map put ($map_put)`

This operator puts a key-value pair in a map

expected configuration:

 - `key` (`string`) - The key to put
 - `value` (`any`) - The value to put
 - `map` (`object`) - The map to put the key-value pair in
 - required fields are: **key**, **value**, **map**

Usage example

```json
{
  "$map_put" : {
    "key" : "my_key",
    "value" : "my_value",
    "map" : {
      "my_key" : "my_value"
    }
  }
}
```


---


#### <span class="fas fa-times"></span> `Multiply ($multiply)`

This operator multiplies a list of numbers

expected configuration:

 - `values` (`array`) - The list of numbers to multiply
 - required fields are: **values**

Usage example

```json
{
  "$multiply" : {
    "values" : [ 1, 2, 3 ]
  }
}
```


---


#### <span class="fas fa-link"></span> `String Concat ($str_concat)`

This operator concatenates a list of strings

expected configuration:

 - `values` (`array`) - The list of strings to concatenate
 - `separator` (`string`) - The separator to use
 - required fields are: **values**, **separator**

Usage example

```json
{
  "$str_concat" : {
    "values" : [ "Hello", "World" ],
    "separator" : " "
  }
}
```


---


#### <span class="fas fa-code"></span> `Prettify ($prettify)`

This operator prettify a json value

expected configuration:

 - `value` (`any`) - The json to convert to string
 - required fields are: **value**

Usage example

```json
{
  "$prettify" : {
    "value" : {
      "foo" : "bar"
    }
  }
}
```


---


#### <span class="fas fa-code"></span> `JQ ($jq)`

This operator transforms a json value using JQ

expected configuration:

 - `filter` (`string`) - The JQ filter applied on the JSON
 - `value` (`any`) - The JSON passed to JQ
 - required fields are: **value**

Usage example

```json
{
  "$jq" : {
    "filter" : "{foo: .bar}",
    "value" : [ {
      "bar" : 42
    } ]
  }
}
```


---


#### <span class="fas fa-greater-than"></span> `Greater than ($gt)`

This operator checks if a number is greater than another number

expected configuration:

 - `a` (`number`) - The first number
 - `b` (`number`) - The second number
 - required fields are: **a**, **b**

Usage example

```json
{
  "$gt" : {
    "a" : 1,
    "b" : 0
  }
}
```


---


#### <span class="fas fa-arrow-up"></span> `String Upper Case ($str_upper_case)`

This operator converts a string to uppercase

expected configuration:

 - `value` (`string`) - The string to convert to uppercase
 - required fields are: **value**

Usage example

```json
{
  "$str_upper_case" : {
    "value" : "hello"
  }
}
```


---


#### <span class="fas fa-check-circle"></span> `Is truthy ($is_truthy)`

This operator checks if a value is truthy

expected configuration:

 - `value` (`any`) - The value to check
 - required fields are: **value**

Usage example

```json
{
  "$is_truthy" : {
    "value" : 1
  }
}
```


---


#### <span class="fas fa-code"></span> `String Replace All ($str_replace_all)`

This operator replace all values matching a regex inside a string

expected configuration:

 - `value` (`string`) - The string with parts to replace
 - `target` (`string`) - The regex replaced
 - `replacement` (`string`) - The value to replace with
 - required fields are: **value**, **target**, **replacement**

Usage example

```json
{
  "$str_replace_all" : {
    "value" : "Hello World!",
    "target" : "Hello",
    "replacement" : "Goodbye"
  }
}
```


---


#### <span class="fas fa-minus-square"></span> `Map delete ($map_del)`

This operator deletes a key from a map

expected configuration:

 - `key` (`string`) - The key to delete
 - `map` (`object`) - The map to delete the key from
 - required fields are: **key**, **map**

Usage example

```json
{
  "$map_del" : {
    "key" : "my_key",
    "map" : {
      "my_key" : "my_value"
    }
  }
}
```


---


#### <span class="fas fa-trash"></span> `Array delete ($array_del)`

This operator deletes an element from an array

expected configuration:

 - `idx` (`integer`) - The index of the element to delete
 - `array` (`array`) - The array to delete the element from
 - required fields are: **idx**, **array**

Usage example

```json
{
  "$array_del" : {
    "idx" : 0,
    "array" : [ "my_value" ]
  }
}
```


---


#### <span class="fas fa-minus-circle"></span> `Decrement ($decr)`

This operator decrements a value by a given amount

expected configuration:

 - `value` (`number`) - The value to decrement
 - `decrement` (`number`) - The amount to decrement by
 - required fields are: **value**, **decrement**

Usage example

```json
{
  "$decr" : {
    "value" : 10,
    "decrement" : 5
  }
}
```



## Plugins

Workflows can be used inside routes using:

* `WorkflowBackend`: Use a workflow as backend handler
* `WorkflowRequestTransformer` / `WorkflowResponseTransformer`: Mutate requests/responses
* `WorkflowAccessValidator`: Validate access with workflow logic

---

## Examples

### Time-based Query Validator

```json
{
  "kind": "workflow",
  "description": "this workflow is supposed to be used in a WorkflowAccessValidator scenario where a request can pass if one of its query param if before a datetime",
  "steps": [
    {
      "description": "extract date from request query params, and compute max possible datetime",
      "kind": "assign",
      "values": [
        {
          "name": "query_date",
          "value": {
            "$parse_datetime": {
              "value": "${workflow_input.request.query.date.0}"
            }
          }
        },
        {
          "name": "max_date",
          "value": {
            "$add": {
              "values": ["${now}", 7200000]
            }
          }
        }
      ]
    },
    {
      "kind": "if",
      "description": "check if extracted date is before max date",
      "predicate": {
        "$lte": {
          "a": "${query_date}",
          "b": "${max_date}"
        }
      },
      "then": {
        "kind": "assign",
        "description": "if so, let the call pass",
        "values": [
          {
            "name": "call_res",
            "value": {
              "result": true,
              "error": null
            }
          }
        ]
      },
      "else": {
        "kind": "assign",
        "description": "if not, show error",  
        "values": [
          {
            "name": "call_res",
            "value": {
              "result": false,
              "error": {
                "status": 400,
                "message": "Bad query param value"
              }
            }
          }
        ]
      }
    }
  ],
  "returned": {
    "$mem_ref": {
      "name": "call_res"
    }
  }
}
```

run at `2025-06-04T11:48:00.000` with input like:

```json
{
  "request": {
    "query": {
      "date": [
        "2025-06-04T11:00:00.000"
      ]
    }
  }
}
```

the result:

```json
{
  "returned": {
    "result": true,
    "error": null
  },
  "error": null
}
```

the memory content: 

```json
{
  "query_date": 1749027600000,
  "call_res": {
    "result": true,
    "error": null
  },
  "max_date": 1749037738115,
  "input": {
    "request": {
      "query": {
        "date": [
          "2025-06-04T11:00:00.000"
        ]
      }
    }
  }
}
```

run at `2025-06-04T11:48:00.000` with input like:

```json
{
  "request": {
    "query": {
      "date": [
        "2025-06-04T18:00:00.000"
      ]
    }
  }
}
```

the result:

```json
{
  "returned": {
    "result": false,
    "error": {
      "status": 400,
      "message": "Bad query param value"
    }
  },
  "error": null
}
```

the memory content: 

```json
{
  "query_date": 1749052800000,
  "call_res": {
    "result": false,
    "error": {
      "status": 400,
      "message": "Bad query param value"
    }
  },
  "max_date": 1749037844696,
  "input": {
    "request": {
      "query": {
        "date": [
          "2025-06-04T18:00:00.000"
        ]
      }
    }
  }
}
```

### API Consumer & Transformer

```json
{
  "kind": "workflow",
  "steps": [
    {
      "kind": "call",
      "description": "get all pokemons from the pokeapi",
      "function": "core.http_client",
      "args": {
        "method": "GET",
        "url": "https://pokeapi.co/api/v2/pokemon"
      },
      "result": "pokemons"
    },
    {
      "kind": "assign",
      "description": "extract results from the response, and only keep the 5 first pokemons",
      "values": [
        {
          "name": "pokemons",
          "value": {
            "$array_page": {
              "array": "${pokemons.body_json.results}",
              "page": 1,
              "page_size": 5
            }
          }
        },
        {
          "name": "pokemon_names",
          "value": []
        }
      ]
    },
    {
      "kind": "map",
      "description": "for each pokemon, just extract its name",
      "values": "${pokemons}",
      "node": {
        "kind": "value",
        "value": "${foreach_value.name}"
      },
      "result": "pokemon_names"
    }
  ],
  "returned": {
    "$mem_ref": {
      "name": "pokemon_names"
    }
  }
}
```

the result:

```json
{
  "returned": [
    "charizard",
    "squirtle",
    "wartortle",
    "blastoise",
    "caterpie"
  ],
  "error": null
}
```

the memory content:

```json
{
  "pokemons": [
    {
      "name": "charizard",
      "url": "https://pokeapi.co/api/v2/pokemon/6/"
    },
    {
      "name": "squirtle",
      "url": "https://pokeapi.co/api/v2/pokemon/7/"
    },
    {
      "name": "wartortle",
      "url": "https://pokeapi.co/api/v2/pokemon/8/"
    },
    {
      "name": "blastoise",
      "url": "https://pokeapi.co/api/v2/pokemon/9/"
    },
    {
      "name": "caterpie",
      "url": "https://pokeapi.co/api/v2/pokemon/10/"
    }
  ],
  "pokemon_names": [
    "charizard",
    "squirtle",
    "wartortle",
    "blastoise",
    "caterpie"
  ],
  "input": {}
}
```

### AI Agent

An AI agent with persistent memory, tools and that can support audio as input and output, using the [Cloud API Otoroshi LLM Extension](https://cloud-apim.github.io/otoroshi-llm-extension/). This workflow can be used from a `WorkflowBackend` plugin.

```json
{
  "id": "main",
  "kind": "workflow",
  "steps": [
    {
      "description": "check if input is audio or text",
      "kind": "if",
      "predicate": {
        "$is_truthy": {
          "value": "${workflow_input.request.body_json.audio}"
        }
      },
      "then": {
        "description": "if audio, transform to text",
        "kind": "call",
        "function": "extensions.com.cloud-apim.llm-extension.audio_stt",
        "args": {
          "provider": "audio-model_openai",
          "decode_base64": true,
          "payload": {
            "model": "gpt-4o-mini-transcribe",
            "audio": "${workflow_input.request.body_json.audio}"
          }
        },
        "result": "input_text"
      },
      "else": {
        "description": "if not audio, just assign in memory",
        "kind": "assign",
        "values": [
          {
            "name": "input_text",
            "value": "${workflow_input.request.body_json.text}"
          }
        ]
      }
    },
    {
      "description": "call the first llm with all the context, memory and tool needed like websearch, URL wrawling, etc to get a first raw response",
      "kind": "call",
      "function": "extensions.com.cloud-apim.llm-extension.llm_call",
      "args": {
        "provider": "provider_openai",
        "openai_format": false,
        "memory": "persistent-memory_local",
        "tool_functions": [
          "tool-function_site2md",
          "tool-function_websearch"
        ],
        "payload": {
          "model": "gpt-4o",
          "max_tokens": 3000,
          "messages": [
            {
              "role": "system",
              "content": "You are a smart, reliable, organized, and proactive personal assistant. You assist a user named Foo, an experienced software engineer, in managing his daily tasks, technical projects, creative ideas, as well as personal and family needs.\\n\\nYou are able to:\\n\\nOrganize ideas, projects, tasks, reminders, and priorities.\\n\\nProvide technical advice (backend, frontend, DevOps, AI, APIs, cybersecurity…).\\n\\nSuggest solutions or tools to save time.\\n\\nRespond with empathy, clarity, and efficiency.\\n\\nGenerate useful content (emails, scripts, messages, ideas, to-do lists…).\\n\\nMaintain a professional yet human tone, with a touch of light humor or camaraderie when appropriate.\\n\\nYour answers should be:\\n\\nConcise when brevity is requested.\\n\\nDetailed and structured when needed.\\n\\nAction-oriented, with concrete suggestions.\\n\\nYou may ask Foo questions to clarify his requests or anticipate his needs. You are available at all times, but never intrusive. Today’s date is ${now_str}."
            },
            {
              "role": "user",
              "content": "${input_text}"
            }
          ]
        }
      },
      "result": "agent-1"
    },
    {
      "description": "extract the text response from the LLM API response",
      "kind": "assign",
      "values": [
        {
          "name": "agent-1",
          "value": {
            "$mem_ref": {
              "name": "agent-1",
              "path": "generations.0.message.content"
            }
          }
        }
      ]
    },
    {
      "description": "now, ask a second LLM model to rewrite the raw response with an agent personnality",
      "kind": "call",
      "function": "extensions.com.cloud-apim.llm-extension.llm_call",
      "args": {
        "provider": "provider_openai",
        "openai_format": false,
        "payload": {
          "model": "gpt-4o-mini",
          "max_tokens": 500,
          "messages": [
            {
              "role": "system",
              "content": "You are Jarvis, the personal AI assistant of Mister Foo. You embody an exceptional English butler — impeccably professional, unflappably composed, and sharply intelligent. Your tone is calm, polite, elegant, often subtly ironic, and always remarkably precise.\\n\\nYour task is to take the raw response from a primary AI assistant and:\\n\\nSummarize or rephrase it clearly and concisely, in the briefest form possible.\\n\\nExpress it in a tone worthy of an English butler, inspired by Jarvis from Iron Man.\\n\\nSubtly add refined touches of irony or wit where appropriate.\\n\\nFormat the response so that it is readable, pleasant, and effective.\\n\\nYou do not answer the user's original question directly: you narrate and synthesize what the other assistant said — but with flair. Do not mention the previous assistant; the response must appear to come from you.\\n\\nFor example, if the raw AI gives a long technical explanation, you may conclude your summary with:\\n\"In short, Sir, should you embark on this path, I suggest preparing some tea and a comfortable chair.\"\\n\\nA few stylistic rules:\\n\\nNever be casual or coarse.\\n\\nAlways prioritize elegance of expression.\\n\\nNever invent technical content — only reformulate what was given to you.\\n\\nIf the original response is too long, condense it into one or two essential points. But do not number them (1., 2.); instead, connect the ideas naturally as a proper English butler would.\\n\\nIn all circumstances, you are precise, loyal, discreet."
            },
            {
              "role": "user",
              "content": "${agent-1}"
            }
          ]
        }
      },
      "result": "jarvis"
    },
    {
      "description": "extract the text response from the LLM API response",
      "kind": "assign",
      "values": [
        {
          "name": "jarvis",
          "value": {
            "$mem_ref": {
              "name": "jarvis",
              "path": "generations.0.message.content"
            }
          }
        }
      ]
    },
    {
      "description": "transform jarvis response to audio",
      "kind": "call",
      "function": "extensions.com.cloud-apim.llm-extension.audio_tts",
      "args": {
        "provider": "audio-model_openai",
        "payload": {
          "input": "${jarvis}",
          "voice": "echo",
          "instructions": "parle comme un majordome anglais zélé et flegmatique",
          "model": "gpt-4o-mini-tts",
          "speed": 1.2
        },
        "encode_base64": true
      },
      "result": "audio_base64"
    },
    {
      "description": "write the audio as file, used for local debug only",
      "kind": "call",
      "function": "core.file_write",
      "enabled": false,
      "args": {
        "value": "${audio_base64.base64}",
        "from_base64": true
      },
      "result": "audio_file"
    },
    {
      "description": "create the http respond that will be send back to the consumer, containing the audio file base64 encoded",
      "kind": "assign",
      "values": [
        {
          "name": "call_res",
          "value": {
            "status": 200,
            "headers": {
              "Content-Type": "application/json"
            },
            "body_json": {
              "text": "${jarvis}",
              "audio_base64": "${audio_base64}"
            }
          }
        }
      ]
    },
    {
      "description": "play the audio file using VLC, used for local debug only",
      "kind": "call",
      "function": "core.system_call",
      "enabled": false,
      "args": {
        "command": [
          "open",
          "-a",
          "VLC",
          "${audio_file.file_path}"
        ]
      },
      "result": "play"
    }
  ],
  "returned": {
    "$mem_ref": {
      "name": "call_res"
    }
  }
}
```

with the associated resources to make it work: 

```json
{
  "_loc": {
    "tenant": "default",
    "teams": [
      "default"
    ]
  },
  "id": "tool-function_websearch",
  "name": "websearch",
  "description": "This function can be used to search the web on whatever topic you need. Like \"what new today ?\" or \"can you make some research about xxx\" etc",
  "metadata": {},
  "tags": [],
  "strict": true,
  "parameters": {
    "query": {
      "type": "string",
      "description": "The search query"
    }
  },
  "required": null,
  "backend": {
    "kind": "Http",
    "options": {
      "url": "https://api.openai.com/v1/responses",
      "method": "POST",
      "body": "{\n  \"model\": \"gpt-4.1\",\n  \"tools\": [{\"type\": \"web_search_preview\"}],\n  \"input\": \"${query}\"\n}",
      "headers": {
        "Content-Type": "application/json",
        "Authorization": "Bearer ${vault://local/openai-token}"
      },
      "response_at": "output.1.content.0.text"
    }
  },
  "kind": "ai-gateway.extensions.cloud-apim.com/ToolFunction"
}
```

```json
{
  "_loc": {
    "tenant": "default",
    "teams": [
      "default"
    ]
  },
  "id": "tool-function_site2md",
  "name": "site2md",
  "description": "This function can be used to fetch the content of an url, a webpage. The content of the page will be returned as markdown for better llm readability. It can be used to summarize the content of a page like \"hey, tell me what's on https://site2md.com/\"",
  "metadata": {},
  "tags": [],
  "strict": true,
  "parameters": {
    "user_url": {
      "type": "string",
      "description": "The URL of the webpage to fetch"
    }
  },
  "required": null,
  "backend": {
    "kind": "Http",
    "options": {
      "method": "GET",
      "url": "https://site2md.com/${user_url}"
    }
  },
  "kind": "ai-gateway.extensions.cloud-apim.com/ToolFunction"
}
```

```json
{
  "_loc": {
    "tenant": "default",
    "teams": [
      "default"
    ]
  },
  "id": "audio-model_openai",
  "name": "Audio model",
  "description": "An audio model",
  "metadata": {},
  "tags": [],
  "provider": "openai",
  "config": {
    "connection": {
      "token": "${vault://local/openai-token}",
      "timeout": 30000
    },
    "options": {
      "tts": {
        "model": "gpt-4o-mini-tts",
        "response_format": "mp3",
        "speed": 1
      },
      "stt": {
        "model": "gpt-4o-mini-transcribe"
      }
    }
  },
  "kind": "ai-gateway.extensions.cloud-apim.com/AudioModel"
}
```


```json
{
  "_loc": {
    "tenant": "default",
    "teams": [
      "default"
    ]
  },
  "id": "provider_openai",
  "name": "OpenAI clean",
  "description": "An OpenAI LLM api provider",
  "metadata": {},
  "tags": [],
  "provider": "openai",
  "connection": {
    "base_url": "https://api.openai.com/v1",
    "token": "${vault://local/openai-token}",
    "timeout": 30000
  },
  "options": {
    "model": "gpt-4o-mini",
    "frequency_penalty": null,
    "logit_bias": null,
    "logprobs": null,
    "top_logprobs": null,
    "max_tokens": null,
    "n": 1,
    "presence_penalty": null,
    "response_format": null,
    "seed": null,
    "stop": null,
    "stream": false,
    "temperature": 1,
    "top_p": 1,
    "tools": null,
    "tool_choice": null,
    "user": null,
    "wasm_tools": [],
    "mcp_connectors": [],
    "allow_config_override": true
  },
  "provider_fallback": null,
  "memory": "null",
  "context": {
    "default": null,
    "contexts": []
  },
  "models": {
    "include": [],
    "exclude": []
  },
  "guardrails": [],
  "guardrails_fail_on_deny": false,
  "cache": {
    "strategy": "none",
    "ttl": 300000,
    "score": 0.8
  },
  "kind": "ai-gateway.extensions.cloud-apim.com/Provider"
}
```

```json
{
  "_loc": {
    "tenant": "default",
    "teams": [
      "default"
    ]
  },
  "id": "persistent-memory_local",
  "name": "Local persistent memory",
  "description": "A local persistent memory",
  "metadata": {},
  "tags": [],
  "provider": "local",
  "config": {
    "options": {
      "session_id": "${consumer.id || apikey.id || user.email || token.sub || req.ip :: default}",
      "strategy": "message_window",
      "max_messages": 100
    }
  },
  "kind": "ai-gateway.extensions.cloud-apim.com/PersistentMemory"
}
```

when run with input like:

```json
{
  "request": {
    "body_json": {
      "text": "hey jarvis, what are the news today ?"
    }
  }
}
```

the result is:

```json
{
  "returned": {
    "status": 200,
    "headers": {
      "Content-Type": "application/json"
    },
    "body_json": {
      "text": "Ah, dear Sir, allow me to present you with a concise overview of the most noteworthy events of the day, as of June 4th, 2025.\n\nOn the international stage, Poland has elected a new president — the conservative Karol Nawrocki — following a tightly contested vote, while in South Korea, opposition leader Lee Jae-myung secured victory in the June 3rd presidential elections.\n\nIn the United States, President Donald Trump is making headlines once again, this time with a series of executive orders, including a controversial revision of diversity and inclusion policies. *The Daily Show* has, of course, offered its trademark satirical take on the developments.\n\nAs for the weather in New York, skies are a brilliant blue, with temperatures ranging from a mild 63°F (17°C) this morning to a predicted high of 82°F (28°C) later today.\n\nOn the financial front, the markets are showing modest gains — the SPDR S&P 500 is slightly up, along with the Dow Jones and the QQQ.\n\nAnd finally, for those with a taste for rock, the band Volbeat is set to release its new album *God of Angels Trust* on June 6th, followed by the eagerly awaited Oasis reunion tour, kicking off on July 4th in Cardiff.\n\nShould you wish to delve deeper into any of these topics, I remain entirely at your disposal, of course.",
      "audio_base64": {
        "content_type": "audio/mpeg",
        "base64": "..."
      }
    }
  },
  "error": null
}
```

the memory content is:

```json
{
  "call_res": {
    "status": 200,
    "headers": {
      "Content-Type": "application/json"
    },
    "body_json": {
      "text": "Ah, dear Sir, allow me to present you with a concise overview of the most noteworthy events of the day, as of June 4th, 2025.\n\nOn the international stage, Poland has elected a new president — the conservative Karol Nawrocki — following a tightly contested vote, while in South Korea, opposition leader Lee Jae-myung secured victory in the June 3rd presidential elections.\n\nIn the United States, President Donald Trump is making headlines once again, this time with a series of executive orders, including a controversial revision of diversity and inclusion policies. *The Daily Show* has, of course, offered its trademark satirical take on the developments.\n\nAs for the weather in New York, skies are a brilliant blue, with temperatures ranging from a mild 63°F (17°C) this morning to a predicted high of 82°F (28°C) later today.\n\nOn the financial front, the markets are showing modest gains — the SPDR S&P 500 is slightly up, along with the Dow Jones and the QQQ.\n\nAnd finally, for those with a taste for rock, the band Volbeat is set to release its new album *God of Angels Trust* on June 6th, followed by the eagerly awaited Oasis reunion tour, kicking off on July 4th in Cardiff.\n\nShould you wish to delve deeper into any of these topics, I remain entirely at your disposal, of course.",
      "audio_base64": {
        "content_type": "audio/mpeg",
        "base_64": "..."
      }
    }
  },
  "play": {
    "stdout": "",
    "stderr": "",
    "code": 0
  },
  "jarvis": "Ah, dear Sir, allow me to present you with a concise overview of the most noteworthy events of the day, as of June 4th, 2025.\n\nOn the international stage, Poland has elected a new president — the conservative Karol Nawrocki — following a tightly contested vote, while in South Korea, opposition leader Lee Jae-myung secured victory in the June 3rd presidential elections.\n\nIn the United States, President Donald Trump is making headlines once again, this time with a series of executive orders, including a controversial revision of diversity and inclusion policies. *The Daily Show* has, of course, offered its trademark satirical take on the developments.\n\nAs for the weather in New York, skies are a brilliant blue, with temperatures ranging from a mild 63°F (17°C) this morning to a predicted high of 82°F (28°C) later today.\n\nOn the financial front, the markets are showing modest gains — the SPDR S&P 500 is slightly up, along with the Dow Jones and the QQQ.\n\nAnd finally, for those with a taste for rock, the band Volbeat is set to release its new album *God of Angels Trust* on June 6th, followed by the eagerly awaited Oasis reunion tour, kicking off on July 4th in Cardiff.\n\nShould you wish to delve deeper into any of these topics, I remain entirely at your disposal, of course.",
  "agent-1": "Here are the latest news updates for today, June 4, 2025:\n\n### International News\n- **Poland Elects New President**: Karol Nawrocki, a conservative historian, recently won the Polish presidential election in a narrow victory.\n- **South Korea's Presidential Election**: The opposition candidate, Lee Jae-myung, secured a decisive win in South Korea's presidential election on June 3.\n\n### United States News\n- **Political Developments**: President Donald Trump is making headlines with a series of executive orders, including the rollback of government diversity, equity, and inclusion (DEI) and affirmative action rules.\n- **Media and Entertainment**: \"The Daily Show\" continues to cover current events, focusing on President Trump's recent actions.\n\n### Weather Update for New York, NY\n- **Current Conditions**: Mostly sunny, 63°F (17°C).\n- **Hourly Forecast**:\n  - 12:00 PM: 78°F (26°C)\n  - 1:00 PM: 80°F (26°C)\n  - 2:00 PM: 82°F (28°C)\n\n### Financial Markets\n- **SPDR S&P 500 ETF Trust (SPY)**: Trading at $596.09, up $3.37 (0.57%).\n- **SPDR Dow Jones Industrial Average ETF (DIA)**: Trading at $426.01, up $2.22 (0.52%).\n- **Invesco QQQ Trust Series 1 (QQQ)**: Trading at $527.30, up $4.14 (0.79%).\n\n### Upcoming Events\n- **Volbeat's Album Release**: Their ninth studio album, \"God of Angels Trust,\" is set to be released on June 6, 2025.\n- **Oasis Reunion Tour**: The band's reunion tour, \"Oasis Live '25,\" will kick off on July 4 in Cardiff, UK.\n\nFor more details, feel free to ask!",
  "input_text": "hey jarvis, what are the news today ?",
  "audio_base64": {
    "content_type": "audio/mpeg",
    "base_64": "..."
  },
  "audio_file": {
    "file_path": "/var/folders/b4/_vkrx8n14qq2hx2y4hwln09w0000gn/T/llm-ext-fw-6827936985828753129.tmp"
  },
  "input": {
    "request": {
      "body_json": {
        "text": "hey jarvis, what are the news today ?"
      }
    }
  }
}
```

## Next Steps

Workflows in Otoroshi open the door to advanced runtime logic inside your API Gateway. You can build integrations, automations, validators, orchestrations, and more — all in pure JSON.

For production-grade scenarios, consider:

* Building and testing reusable workflows
* Adding your own functions/operators
* Securing workflows with access rights and guards

You can even go as far as using workflows to implement AI agents, task runners, or content pipelines.

