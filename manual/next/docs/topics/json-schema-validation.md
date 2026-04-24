---
title: JSON Schema body validation
sidebar_position: 32
---

# JSON Schema body validation

Otoroshi ships two `NgRequestTransformer` plugins that validate HTTP bodies against a user-provided [JSON Schema](https://json-schema.org/). They let you reject malformed traffic at the gateway, before it reaches your backend, and protect clients from non-conforming responses.

| Plugin                         | Step                | When validation fails                              |
| ------------------------------ | ------------------- | -------------------------------------------------- |
| `JsonSchemaRequestValidator`   | `TransformRequest`  | Returns `422 Unprocessable Entity` to the client   |
| `JsonSchemaResponseValidator`  | `TransformResponse` | Returns `502 Bad Gateway` to the client            |

Validation only runs when the body's `Content-Type` contains `json`. Anything else passes through untouched.

## Configuration

Both plugins share the same configuration shape:

| Field                       | Type      | Default                              | Description                                                                 |
| --------------------------- | --------- | ------------------------------------ | --------------------------------------------------------------------------- |
| `schema`                    | `string`  | `null`                               | The JSON Schema as a string. If empty, validation is skipped.               |
| `specification`             | `string`  | `"https://json-schema.org/draft/2020-12/schema"` | JSON Schema draft to use. See supported versions below.       |
| `fail_on_validation_error`  | `boolean` | `true`                               | When `false`, validation errors are logged but the request/response passes. |

### Supported specifications

The plugins use [`com.networknt.json-schema-validator`](https://github.com/networknt/json-schema-validator). Pass the spec id (e.g. `V202012`, `V201909`, `V7`, `V6`, `V4`) — anything accepted by `SpecVersion.VersionFlag.fromId(...)`. Default is `V202012` (Draft 2020-12).

## Validating incoming requests

Add `JsonSchemaRequestValidator` to a route's plugin chain to enforce a schema on the incoming body:

```json
{
  "plugin": "cp:otoroshi.next.plugins.JsonSchemaRequestValidator",
  "enabled": true,
  "config": {
    "schema": "{ \"type\": \"object\", \"required\": [\"name\", \"age\"], \"properties\": { \"name\": { \"type\": \"string\" }, \"age\": { \"type\": \"integer\", \"minimum\": 0 } } }",
    "specification": "https://json-schema.org/draft/2020-12/schema",
    "fail_on_validation_error": true
  }
}
```

A request with `{"name":"Alice"}` (missing `age`) is answered with:

```http
HTTP/1.1 422 Unprocessable Entity
Content-Type: application/json

{
  "error": "request body does not match the json schema",
  "validation_errors": [
    "$.age: is missing but it is required"
  ]
}
```

## Validating outgoing responses

`JsonSchemaResponseValidator` works the same way on the backend's response. When the backend returns a body that does not match the schema, the client receives a `502 Bad Gateway` instead of the malformed payload.

```json
{
  "plugin": "cp:otoroshi.next.plugins.JsonSchemaResponseValidator",
  "enabled": true,
  "config": {
    "schema": "{ \"type\": \"object\", \"required\": [\"id\", \"email\"], \"properties\": { \"id\": { \"type\": \"string\" }, \"email\": { \"type\": \"string\", \"format\": \"email\" } } }",
    "fail_on_validation_error": true
  }
}
```

If you want to monitor backend conformance without breaking traffic, set `fail_on_validation_error` to `false`. Errors are logged under the `otoroshi-plugins-ng-jsonschema-validator` logger and the original response is returned.

## Composing both plugins

Stacking `JsonSchemaRequestValidator` and `JsonSchemaResponseValidator` on the same route lets you contract-test a route from both ends — typically with a request schema derived from your OpenAPI/JSON Schema spec for the operation, and a response schema for the success body.

```json
{
  "plugins": {
    "slots": [
      { "plugin": "cp:otoroshi.next.plugins.JsonSchemaRequestValidator",  "config": { "schema": "..." } },
      { "plugin": "cp:otoroshi.next.plugins.JsonSchemaResponseValidator", "config": { "schema": "..." } }
    ]
  }
}
```

## Notes & limitations

- The body is fully buffered in memory before validation. Avoid these plugins on very large or streamed bodies.
- The schema string is parsed and compiled on every request. For high-throughput routes consider caching at the JVM level (future improvement) or move validation to your backend.
- Only bodies whose `Content-Type` contains `json` (e.g. `application/json`, `application/vnd.api+json`) are validated. Other content types pass through.
- Format assertions (`format: "email"`, `format: "date-time"`, …) are enabled by default through `SchemaValidatorsConfig.setFormatAssertionsEnabled(true)`.
