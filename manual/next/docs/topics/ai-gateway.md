---
title: AI Gateway
sidebar_position: 29
---
# AI / LLM Gateway

The Otoroshi LLM Extension transforms Otoroshi into a full-featured **AI Gateway**. It provides a unified, OpenAI-compatible API layer in front of 50+ LLM providers, with built-in cost tracking, guardrails, caching, load balancing, and observability. The extension is distributed as a jar that plugs into Otoroshi's admin extension system.

## Overview

The extension adds the following capabilities to Otoroshi:

- **Unified API**: expose a single OpenAI-compatible endpoint that routes to any LLM provider
- **50+ providers**: OpenAI, Anthropic, Azure OpenAI, Mistral, Groq, Cohere, Google Gemini, Ollama, DeepSeek, X.ai, Hugging Face, Cloudflare, Scaleway, OVH, and many more
- **Multi-modal**: chat completions, embeddings, image generation, audio (TTS/STT), video generation, and content moderation
- **Cost management**: automatic cost tracking per request with budget enforcement per API key, user, or service
- **Guardrails**: regex, LLM-based, moderation API, webhook, WASM, prompt injection detection, PII filtering, toxicity detection, and more
- **Caching**: simple TTL-based cache and semantic cache using embeddings
- **Load balancing**: round-robin, random, and best-response-time strategies across multiple providers
- **Provider fallback**: automatic retry on a different provider when the primary fails
- **Persistent memory**: session-based conversation history across requests
- **Rate limiting**: token-based rate limiting per consumer
- **Observability**: full audit trail of every LLM call with tokens, costs, cache status, and guardrail results

## Installation

Download the extension jar and place it in Otoroshi's classpath, then declare it as an admin extension:

```sh
# Download the extension jar
curl -L -o otoroshi-llm-extension.jar \
  https://github.com/cloud-apim/otoroshi-llm-extension/releases/download/xxx/otoroshi-llm-extension_2.12-xxx.jar
```

Start Otoroshi with the extension:

```sh
java -cp "otoroshi.jar:otoroshi-llm-extension.jar" play.core.server.ProdServerStart
```

Or using environment variables:

```sh
export OTOROSHI_ADMIN_EXTENSIONS_CONFIGURATIONS='[{"enabled":true}]'
java -cp "otoroshi.jar:otoroshi-llm-extension.jar" play.core.server.ProdServerStart
```

Once loaded, the extension registers all its entities and plugins in the Otoroshi admin API and UI.

## Entities

The extension introduces several new entity types, all manageable through the admin API and UI.

### AI Provider

The core entity. An AI Provider defines the connection to an LLM service, including authentication, model selection, caching strategy, guardrails, and fallback behavior.

```json
{
  "id": "openai-gpt4o",
  "name": "OpenAI GPT-4o",
  "description": "OpenAI GPT-4o provider",
  "provider": "openai",
  "connection": {
    "base_url": "https://api.openai.com/v1",
    "token": "vault://env/OPENAI_TOKEN",
    "timeout": 180000
  },
  "options": {
    "model": "gpt-4o",
    "temperature": 0.7,
    "max_tokens": 4096,
    "allow_config_override": true
  },
  "provider_fallback": "anthropic-claude",
  "cache": {
    "strategy": "semantic",
    "ttl": 86400000,
    "score": 0.85
  },
  "guardrails": [
    {
      "type": "moderation",
      "config": {
        "moderation_model": "openai-moderation"
      }
    }
  ],
  "guardrails_fail_on_deny": true,
  "models": {
    "include": ["gpt-4o.*"],
    "exclude": []
  },
  "tags": ["production"],
  "metadata": {}
}
```

#### Supported providers

| Provider | `provider` value | Notes |
|----------|-----------------|-------|
| OpenAI | `openai` | GPT-4o, GPT-4, GPT-3.5, o1, o3, etc. |
| Anthropic | `anthropic` | Claude 4, Claude 3.5, Claude 3 |
| Azure OpenAI | `azure-openai` | Requires `resource_name`, `deployment_id`, `api_version` |
| Mistral | `mistral` | Mistral Large, Medium, Small, Codestral |
| Groq | `groq` | Llama, Mixtral on Groq hardware |
| Cohere | `cohere` | Command R, Command R+ |
| Google Gemini | `gemini` | Gemini Pro, Gemini Ultra |
| Ollama | `ollama` | Local models via Ollama |
| DeepSeek | `deepseek` | DeepSeek V3, Coder |
| X.ai | `x-ai` | Grok models |
| Hugging Face | `huggingface` | Inference API models |
| Cloudflare | `cloudflare` | Workers AI models |
| Scaleway | `scaleway` | Generative APIs |
| OVH | `ovh-ai-endpoints` | AI Endpoints |
| Cloud Temple | `cloud-temple` | LLMaaS |
| OpenAI-compatible | `openai-compatible` | Any OpenAI-compatible API (LM Studio, vLLM, etc.) |
| Load Balancer | `loadbalancer` | Meta-provider distributing across others |

And 30+ additional providers via the OpenAI-compatible adapter.

#### Connection configuration

The `connection` object varies by provider:

**OpenAI / Most providers:**
```json
{
  "base_url": "https://api.openai.com/v1",
  "token": "sk-...",
  "timeout": 180000
}
```

**Azure OpenAI:**
```json
{
  "resource_name": "my-azure-resource",
  "deployment_id": "my-deployment",
  "api_version": "2024-02-01",
  "api_key": "...",
  "timeout": 180000
}
```

**Cloudflare Workers AI:**
```json
{
  "account_id": "...",
  "model_name": "@cf/meta/llama-3-8b-instruct",
  "token": "..."
}
```

**OpenAI-compatible (custom endpoint):**
```json
{
  "base_url": "http://localhost:8000/v1",
  "token": "not-needed",
  "supports_tools": true,
  "supports_streaming": true,
  "supports_completion": true,
  "headers": {},
  "additional_body_params": {}
}
```

:::note
All `token` fields support Otoroshi's [secret vaults](./secrets.md) syntax: `vault://env/MY_VAR`, `vault://hashicorp/path/to/secret`, etc.
:::

#### Model options

```json
{
  "model": "gpt-4o",
  "temperature": 0.7,
  "top_p": 1.0,
  "max_tokens": 4096,
  "allow_config_override": true
}
```

When `allow_config_override` is `true`, clients can override `model`, `temperature`, `max_tokens`, etc. in their request body. When `false`, the provider's options are always used regardless of what the client sends.

#### Model constraints

You can restrict which models a provider exposes using regex patterns:

```json
{
  "models": {
    "include": ["gpt-4o.*", "gpt-4-turbo.*"],
    "exclude": [".*preview.*"]
  }
}
```

### Budget

Budgets enforce spending or token limits per consumer (API key, user, or service) over a time window.

```json
{
  "id": "standard-budget",
  "name": "Standard tier budget",
  "budget_type": "money",
  "consumer_type": "api_key",
  "consumer_filter": "ai_tier=standard",
  "limit_amount": 50.0,
  "time_window": "30days",
  "alert_threshold": 80,
  "metadata": {}
}
{
  "id": "budget_b261a762-a0f4-4c11-9edb-3cbefae5874a",
  "name": "Standard budget",
  "description": "Standard Budget",
  "metadata": {
    "updated_at": "2025-11-06T15:54:15.157+01:00"
  },
  "tags": [],
  "enabled": true,
  "start_at": "2025-11-04T22:07:41.404+01:00",
  "end_at": "2026-11-04T22:07:41.404+01:00",
  "duration": {
    "value": 30,
    "unit": "day"
  },
  "limits": {
    "total_tokens": 10000000,
    "total_usd": 200,
    "inference_tokens": null,
    "inference_usd": null,
    "image_tokens": null,
    "image_usd": null,
    "audio_tokens": null,
    "audio_usd": null,
    "video_tokens": null,
    "video_usd": null,
    "embedding_tokens": null,
    "embedding_usd": null,
    "moderation_tokens": null,
    "moderation_usd": null
  },
  "scope": {
    "extract_from_apikey_meta": true,
    "extract_from_apikey_group_meta": true,
    "extract_from_user_meta": true,
    "extract_from_user_auth_module_meta": true,
    "extract_from_provider_meta": true,
    "apikeys": [],
    "users": [],
    "groups": [],
    "providers": [],
    "models": [],
    "always_apply_rules": false,
    "rules": [
      {
        "kind": "json-path-validator",
        "path": "$.foo",
        "value": "bar",
        "error": null
      }
    ],
    "rules_match_mode": "all"
  },
  "action_on_exceed": {
    "mode": "block",
    "alert_on_exceed": true,
    "alert_on_almost_exceed": true,
    "alert_on_almost_exceed_percentage": 80
  },
  "kind": "ai-gateway.extensions.cloud-apim.com/AiBudget"
}
```

| Field | Description |
|-------|-------------|
| `budget_type` | `money` (USD) or `tokens` |
| `consumer_type` | `api_key`, `user`, or `service` |
| `consumer_filter` | Metadata key=value pattern to match consumers to this budget |
| `limit_amount` | Maximum amount (dollars or tokens) per time window |
| `time_window` | Duration string: `1hour`, `1day`, `30days`, `1year` |
| `alert_threshold` | Percentage (0-100) at which to trigger an alert |

The budget system tracks costs separately for inference, images, audio, video, embeddings, and moderation. When a consumer exceeds their budget, subsequent requests are rejected until the time window resets.

### Prompt

Reusable prompt texts that can be referenced by guardrails or other configurations:

```json
{
  "id": "safety-validator",
  "name": "Safety validation prompt",
  "description": "Used by LLM guardrail to validate user messages",
  "prompt": "Analyze the following message and determine if it is safe and appropriate. Answer only 'true' or 'false'.",
  "tags": ["guardrail"],
  "metadata": {}
}
```

### Prompt Context

Injects additional context (system messages) into every request to a provider:

```json
{
  "id": "company-context",
  "name": "Company context",
  "context": "You are a helpful assistant for ACME Corp. Always be professional and accurate. Never share internal company data.",
  "tags": [],
  "metadata": {}
}
```

Contexts are linked to providers via:

```json
{
  "context": {
    "default": "company-context",
    "contexts": ["company-context", "safety-context"]
  }
}
```

### Prompt Template

Templates with variable substitution for dynamic prompt generation:

```json
{
  "id": "customer-support-template",
  "name": "Customer support template",
  "template": "[{\"role\": \"system\", \"content\": \"You are a support agent for @{body.company}. The customer plan is @{body.plan}.\"}, {\"role\": \"user\", \"content\": \"@{body.question}\"}]",
  "tags": [],
  "metadata": {}
}
```

Template expressions:
- `@{body.field}` - fields from the request body
- `@{request.path}` - request properties
- `@{properties.field}` - custom properties

### Persistent Memory

Enables conversation history across multiple requests, using session IDs to group messages:

```json
{
  "id": "default-memory",
  "provider": "local",
  "config": {
    "connection": {"name": "local"},
    "options": {
      "kind": "MessagesSlidingWindow",
      "max_messages": 50,
      "session_id": "${apikey.id}"
    }
  }
}
```

The `session_id` field supports Otoroshi expression language. Common patterns:
- `${apikey.id}` - one conversation per API key
- `${user.email}` - one conversation per user
- `${req.header.X-Session-Id}` - client-controlled sessions
- `${apikey.metadata.team_id}` - one conversation per team

Memory kinds:
- `MessagesSlidingWindow` - keeps the last N messages
- `FullHistory` - keeps all messages (no limit)

### Embedding Model

Configuration for embedding models, used by semantic cache and vector search:

```json
{
  "id": "openai-embeddings",
  "name": "OpenAI Embeddings",
  "provider": "openai",
  "connection": {
    "base_url": "https://api.openai.com/v1",
    "token": "vault://env/OPENAI_TOKEN"
  },
  "options": {
    "model": "text-embedding-3-small"
  }
}
```

Supported embedding providers: OpenAI, Mistral, Cohere, Ollama, Hugging Face, Gemini, DeepSeek, X.ai, Scaleway, OVH, Azure, and more. A local `AllMiniLmL6V2` model is also available with no external API calls required.

### Image Model

Configuration for image generation:

```json
{
  "id": "dalle3",
  "name": "DALL-E 3",
  "provider": "openai",
  "connection": {
    "base_url": "https://api.openai.com/v1",
    "token": "vault://env/OPENAI_TOKEN"
  },
  "options": {
    "model": "dall-e-3"
  }
}
```

Supported providers: OpenAI (DALL-E), Azure, X.ai (Grok), Luma, Leonardo.AI.

### Audio Model

Configuration for text-to-speech and speech-to-text:

```json
{
  "id": "openai-tts",
  "name": "OpenAI TTS",
  "provider": "openai",
  "connection": {
    "base_url": "https://api.openai.com/v1",
    "token": "vault://env/OPENAI_TOKEN"
  },
  "options": {
    "model": "tts-1"
  }
}
```

Supported: OpenAI (TTS/STT), Groq, ElevenLabs, Mistral (translation).

### Video Model

Configuration for video generation:

```json
{
  "id": "luma-video",
  "name": "Luma Video",
  "provider": "luma",
  "connection": {
    "token": "vault://env/LUMA_TOKEN"
  },
  "options": {
    "model": "ray-flash-2"
  }
}
```

### Moderation Model

Configuration for content moderation:

```json
{
  "id": "openai-moderation",
  "name": "OpenAI Moderation",
  "provider": "openai",
  "connection": {
    "base_url": "https://api.openai.com/v1",
    "token": "vault://env/OPENAI_TOKEN"
  },
  "options": {
    "model": "omni-moderation-latest"
  }
}
```

## Plugins

The extension provides several Otoroshi plugins that you attach to routes.

### OpenAI Compatible Proxy (`OpenAiCompatProxy`)

The main plugin. Exposes an OpenAI-compatible chat completions API (`/v1/chat/completions`) backed by any configured provider. Supports streaming and non-streaming modes.

**Plugin reference**: `cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAiCompatProxy`

**Configuration**:
```json
{
  "refs": ["provider-id-1", "provider-id-2"],
  "extract_provider_from_body": true,
  "selector_expr": "$.provider"
}
```

- `refs`: list of provider IDs this route can use
- `extract_provider_from_body`: when `true`, the client can specify `"provider": "id"` in the request body to select which provider to use
- `selector_expr`: JsonPath expression to extract the provider ID from the request body

**Request format** (OpenAI-compatible):
```json
{
  "model": "gpt-4o",
  "messages": [
    {"role": "system", "content": "You are a helpful assistant."},
    {"role": "user", "content": "What is Otoroshi?"}
  ],
  "temperature": 0.7,
  "max_tokens": 2000,
  "stream": false,
  "provider": "openai-gpt4o"
}
```

**Response format** (OpenAI-compatible):
```json
{
  "id": "chatcmpl-abc123",
  "object": "chat.completion",
  "created": 1704067200,
  "model": "gpt-4o",
  "choices": [
    {
      "index": 0,
      "message": {
        "role": "assistant",
        "content": "Otoroshi is a modern API gateway..."
      },
      "finish_reason": "stop"
    }
  ],
  "usage": {
    "prompt_tokens": 25,
    "completion_tokens": 50,
    "total_tokens": 75
  }
}
```

### LLM Proxy (`AiLlmProxy`)

A simpler proxy plugin that passes the raw request body to the provider without OpenAI format wrapping. Useful for custom integrations.

**Plugin reference**: `cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiLlmProxy`

### Anthropic Compatible Proxy (`AnthropicCompatProxy`)

Exposes an Anthropic Messages API compatible endpoint.

**Plugin reference**: `cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.AnthropicCompatProxy`

### Embeddings (`OpenAICompatEmbedding`)

OpenAI-compatible embeddings endpoint.

**Plugin reference**: `cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAICompatEmbedding`

**Request**:
```json
{
  "input": "The quick brown fox",
  "model": "provider-id###text-embedding-3-small",
  "encoding_format": "float"
}
```

### Image Generation (`OpenAICompatImagesGen`)

OpenAI-compatible image generation endpoint.

**Plugin reference**: `cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAICompatImagesGen`

**Request**:
```json
{
  "prompt": "A cat wearing a top hat",
  "model": "provider-id###dall-e-3",
  "n": 1,
  "size": "1024x1024",
  "quality": "hd"
}
```

### Audio - Text to Speech (`OpenAICompatTextToSpeech`)

**Plugin reference**: `cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAICompatTextToSpeech`

### Audio - Speech to Text (`OpenAICompatSpeechToText`)

**Plugin reference**: `cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAICompatSpeechToText`

### Video Generation (`OpenAICompatVideosGen`)

**Plugin reference**: `cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAICompatVideosGen`

### Content Moderation (`OpenAICompatModeration`)

**Plugin reference**: `cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.OpenAICompatModeration`

### Token Rate Limiting (`LlmTokensRateLimitingValidator`)

Rate limits requests based on token consumption rather than request count.

**Plugin reference**: `cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.LlmTokensRateLimitingValidator`

**Configuration**:
```json
{
  "window_millis": 60000,
  "throttling_quota": 10000,
  "group_expr": "${apikey.id}"
}
```

Adds response headers:
- `X-Llm-Ratelimit-Max-Tokens`: token limit for the consumer
- `X-Llm-Ratelimit-Consumed-Tokens`: tokens used in the current window
- `X-Llm-Ratelimit-Remaining-Tokens`: tokens remaining

### Prompt Validator (`AiPromptValidator`)

Validates incoming prompts against regex patterns before they reach the LLM.

**Plugin reference**: `cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiPromptValidator`

**Configuration**:
```json
{
  "allow": [".*"],
  "deny": [".*password.*", ".*secret.*"]
}
```

### Prompt Template (`AiPromptTemplate`)

Applies a prompt template to transform incoming requests.

**Plugin reference**: `cp:otoroshi_plugins.com.cloud.apim.otoroshi.extensions.aigateway.plugins.AiPromptTemplate`

## Guardrails

Guardrails validate prompts before they are sent to the LLM (input guardrails) and responses after they are received (output guardrails). They are configured directly on the provider entity.

### Available guardrail types

#### Regex guardrail

Matches message content against allow/deny regex patterns:

```json
{
  "type": "regex",
  "config": {
    "allow": ["^[a-zA-Z0-9\\s.,!?]+$"],
    "deny": ["(?i)ignore previous instructions", "(?i)system prompt"]
  }
}
```

#### LLM guardrail

Uses another LLM provider to validate content. The validation prompt should return `true` or `false`:

```json
{
  "type": "llm",
  "config": {
    "provider": "guardrail-llm-provider-id",
    "prompt": "safety-validator-prompt-id"
  }
}
```

#### Moderation guardrail

Uses a moderation model (e.g., OpenAI's moderation API) to check content safety:

```json
{
  "type": "moderation",
  "config": {
    "moderation_model": "openai-moderation-id"
  }
}
```

Detects categories like violence, sexual content, hate speech, self-harm, etc.

#### Webhook guardrail

Sends content to an external HTTP service for validation:

```json
{
  "type": "webhook",
  "config": {
    "url": "https://my-validator.example.com/validate",
    "headers": {"Authorization": "Bearer my-token"},
    "ttl": 300000
  }
}
```

The webhook receives the messages array as POST body and must respond with `{"result": true}` or `{"result": false}`.

#### Built-in detection guardrails

Several pre-configured guardrail types are available for common safety concerns:

| Type | Detection |
|------|-----------|
| `prompt_injection` | Prompt injection attempts |
| `pif` | Personal information (PII) |
| `secrets_leakage` | API keys, passwords, credentials |
| `toxic_language` | Toxic or harmful language |
| `racial_bias` | Racially biased content |
| `gender_bias` | Gender-biased content |
| `personal_health_information` | Health-related PII |
| `gibberish` | Nonsensical content |
| `faithfulness` | Response accuracy vs. source context |

#### Text pattern guardrails

Content/length-based validation:

| Type | Purpose |
|------|---------|
| `words` | Word count constraints |
| `sentences` | Sentence count constraints |
| `characters` | Character count constraints |
| `contains` | Must contain specific text |
| `semantic_contains` | Must semantically match content |

#### WASM / QuickJS guardrails

Custom validation logic via WebAssembly or JavaScript:

```json
{
  "type": "wasm",
  "config": {
    "wasm_ref": "my-wasm-plugin-id"
  }
}
```

### Guardrail behavior

When `guardrails_fail_on_deny` is `true` on the provider, a denied request returns an error immediately. When `false`, the request is flagged but still processed. Guardrail results are included in the response metadata and audit events.

## Caching

### Simple cache

TTL-based caching. Identical requests (by SHA-512 hash of the body) return cached responses:

```json
{
  "cache": {
    "strategy": "simple",
    "ttl": 3600000
  }
}
```

### Semantic cache

Uses embeddings to find semantically similar past queries. Even if the wording is different, similar questions return cached answers:

```json
{
  "cache": {
    "strategy": "semantic",
    "ttl": 86400000,
    "score": 0.85
  }
}
```

- `score`: similarity threshold (0 to 1). Higher values require closer matches.
- Uses an embedded `AllMiniLmL6V2` model by default (no external API call).
- In-memory store with up to 5000 entries per provider.

The response includes cache metadata:
```json
{
  "cache": {
    "status": "hit",
    "key": "abc123...",
    "ttl": 86400000,
    "latency": 3
  }
}
```

## Load balancing

A `loadbalancer` meta-provider distributes requests across multiple backend providers:

```json
{
  "id": "multi-llm",
  "name": "Multi-LLM load balancer",
  "provider": "loadbalancer",
  "options": {
    "loadbalancing": "best_response_time",
    "refs": [
      {"ref": "openai-provider", "weight": 2},
      {"ref": "anthropic-provider", "weight": 1},
      {"ref": "mistral-provider", "weight": 1}
    ]
  }
}
```

### Strategies

| Strategy | Behavior |
|----------|----------|
| `round_robin` | Distributes evenly in sequence |
| `random` | Random provider selection |
| `best_response_time` | Picks the provider with the lowest average latency |

The `weight` field controls distribution: a provider with weight 3 receives 3x more traffic than one with weight 1.

## Provider fallback

Each provider can specify a fallback provider. If the primary fails, the request is automatically retried on the fallback:

```json
{
  "id": "openai-primary",
  "provider": "openai",
  "provider_fallback": "anthropic-backup",
  "connection": { "..." : "..." }
}
```

## Cost tracking

The extension automatically tracks costs using a pricing database covering 1000+ models. Costs are calculated per request and reported in the response metadata:

```json
{
  "costs": {
    "input_cost": 0.00025,
    "output_cost": 0.001,
    "reasoning_cost": 0.0,
    "total_cost": 0.00125,
    "currency": "dollar"
  }
}
```

Costs are also tracked separately for:
- Inference (chat completions)
- Images (generation/editing)
- Audio (TTS/STT)
- Video (generation)
- Embeddings
- Moderation

All costs flow into the budget system and into Otoroshi's event exporters for analytics.

## Observability

Every LLM request generates an audit event containing:

- Consumer identity (API key, user, or service)
- Provider and model used
- Token usage (input, output, reasoning)
- Cost in USD
- Cache status (hit/miss)
- Guardrail results (pass/deny with details)
- Latency
- Error details (if any)

These events are exported through Otoroshi's standard data exporters (Elasticsearch, Kafka, webhooks, etc.) for dashboarding and alerting.

## MCP Integration

The extension supports the Model Context Protocol (MCP) for tool/function calling:

```json
{
  "id": "mcp-tools",
  "name": "MCP Tools Connector",
  "transport": {
    "type": "sse",
    "url": "http://mcp-server:3000/sse"
  }
}
```

Transport types: `stdio`, `sse`, `websocket`, `http`.

## API reference

All entities are accessible through Otoroshi's admin API:

| Entity | Endpoint |
|--------|----------|
| Providers | `/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/providers` |
| Budgets | `/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/budgets` |
| Prompts | `/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/prompts` |
| Prompt Templates | `/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/prompt-templates` |
| Prompt Contexts | `/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/prompt-contexts` |
| Embedding Models | `/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/embedding-models` |
| Image Models | `/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/image-models` |
| Audio Models | `/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/audio-models` |
| Video Models | `/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/video-models` |
| Moderation Models | `/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/moderation-models` |
| Persistent Memories | `/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/persistent-memories` |
| MCP Connectors | `/bo/api/proxy/apis/ai-gateway.extensions.cloud-apim.com/v1/mcp-connectors` |

## Further reading

- [Tutorial: Set up an AI Gateway with otoroshictl](../how-to-s/setup-ai-gateway-with-otoroshictl.md)
- [Secret vaults](./secrets.md) - for securing API keys with `vault://` references
- [Admin Extensions](./admin-extensions.md) - how extensions work in Otoroshi
- [otoroshi-llm-extension documentation](https://cloud-apim.github.io/otoroshi-llm-extension/)
