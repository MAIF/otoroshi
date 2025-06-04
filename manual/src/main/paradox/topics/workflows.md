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
        "name": "${input.name}"
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

At any moment you can access the memory using an expression language like `${input.name}` given a memory containing something like:

```json
{
  "input": {
    "name": "foo"
  }
}
```

---

## Nodes

Each node must declare a `kind` field and can optionally define:

* `id`: Node identifier
* `description`: A comment for readability/debugging
* `enabled`: Boolean to toggle the node
* `result`: Name of memory variable to store output
* `returned`: Immediate result to return from the workflow

### Available Node Kinds and Prototypes

#### `workflow`

```json
{
  "kind": "workflow",
  "steps": [ <Node> ],
  "returned": <Value>
}
```

#### `call`

```json
{
  "kind": "call",
  "function": "<function_name>",
  "args": { ... },
  "result": "<memory_var_name>"
}
```

#### `assign`

```json
{
  "kind": "assign",
  "values": [
    {
      "name": "<memory_var>",
      "value": <any>
    }
  ]
}
```

#### `parallel`

```json
{
  "kind": "parallel",
  "paths": [ <workflow>, ... ]
}
```

#### `switch`

```json
{
  "kind": "switch",
  "paths": [
    {
      "predicate": <bool_expr>,
      "node": <workflow>
    }
  ]
}
```

#### `if`

```json
{
  "kind": "if",
  "predicate": <bool_expr>,
  "then": <Node>,
  "else": <Node>
}
```

#### `foreach` / `map` / `filter` / `flatmap`

```json
{
  "kind": "foreach",
  "values": <array_expr>,
  "node": <workflow>
}
```

#### `wait`

```json
{
  "kind": "wait",
  "duration": 1000
}
```

#### `error`

```json
{
  "kind": "error",
  "message": "<error_message>",
  "details": { ... }
}
```

---

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

Built-in core functions include:

* `core.log`: { message: string, params?: array }
* `core.hello`: { name: string }
* `core.http_client`: { method, url, headers, body, timeout, tls\_config }
* `core.system_call`: { command: \[string] }
* `core.workflow_call`: { workflow\_id: string, input: object }
* `core.wasm_call`: { wasm\_plugin, function?, params }
* `core.file_read`: { path: string, parse\_json?, encode\_base64? }
* `core.file_write`: { path?, value, prettify?, from\_base64? }
* `core.send_mail`: { from, to, subject, html, mailer\_config }
* `core.store_get`, `store_set`, `store_del`: { key, value?, ttl? }
* `core.emit_event`: { event: object }

---

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

* `$mem_ref`
* `$array_append`
* `$array_prepend`
* `$array_at`
* `$array_del`
* `$array_page`
* `$projection`
* `$map_put`
* `$map_get`
* `$map_del`
* `$json_parse`
* `$str_concat`
* `$is_truthy`
* `$is_falsy`
* `$contains`
* `$eq`
* `$neq`
* `$gt`
* `$lt`
* `$gte`
* `$lte`
* `$encode_base64`
* `$decode_base64`
* `$basic_auth`
* `$now`
* `$not`
* `$parse_datetime`
* `$parse_date`
* `$parse_time`
* `$add`
* `$subtract`
* `$multiply`
* `$divide`
* `$expression_language`

---

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
              "value": "${input.request.query.date.0}"
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
      "kind": "foreach",
      "description": "for each pokemon, just extract its name",
      "values": "${pokemons}",
      "node": {
        "kind": "assign",
        "values": [
          {
            "name": "pokemon_names",
            "value": {
              "$array_append": {
                "array": "${pokemon_names}",
                "value": "${foreach_value.name}"
              }
            }
          }
        ]
      }
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
          "value": "${input.request.body_json.audio}"
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
            "audio": "${input.request.body_json.audio}"
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
            "value": "${input.request.body_json.text}"
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

