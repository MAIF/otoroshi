---
title: Running a plugin conditionally
sidebar_label: "Conditional plugin"
sidebar_position: 4
---

# Running a plugin conditionally

The **Conditional plugin** wraps another plugin and runs it only when a set of JSONPath predicates
matches. It is the way to express things like "only apply this rate limiter to the free tier", "only
check this JWT when the call comes from outside" or "only serve the static backend for a given
header", without writing a plugin of your own.

Otoroshi already lets you enable a plugin instance for a subset of the paths of a route, with the
`include` and `exclude` fields of the plugin slot. The Conditional plugin picks up where those stop:
it can look at the apikey, the authenticated user, the route metadata, the request, the response and
the request attributes.

## Configuration

```json
{
  "predicates": [
    { "path": "$.apikey.metadata.tier", "value": "gold" }
  ],
  "invert": false,
  "evaluation_mode": "per_phase",
  "plugin": "cp:otoroshi.next.plugins.ApikeyCalls",
  "plugin_config": {
    "validate": true
  }
}
```

| Field | Description |
| --- | --- |
| `predicates` | The JSONPath validators, in the same format as the [Context validator](./built-in-plugins.mdx) plugin. **All** of them must match. An empty list always matches. |
| `invert` | Runs the wrapped plugin when the predicates do **not** match. |
| `evaluation_mode` | `per_phase` (default), `once` or `latch`. See below. |
| `plugin` | The id of the plugin to run, for instance `cp:otoroshi.next.plugins.ApikeyCalls`. |
| `plugin_config` | The configuration handed to the wrapped plugin, exactly as if it were declared on its own in the route. |

The predicate values support the whole expected-value language of `JsonPathValidator`:
`Contains(...)`, `Regex(...)`, `Wildcard(...)`, `Not(...)`, `IsDefined()`, `NotDefined()`,
`Size(...)`, `StartsWith(...)`, and so on. Both `path` and `value` go through the Otoroshi
expression language first, so `${req.headers.x-tier}` works in either of them.

## Which plugins can be wrapped

The plugins of the core phases: pre-routing, access validation, request transformation, response
transformation, error transformation and backend call.

Route matchers, request sinks, tunnel handlers, websocket plugins and incoming request validators
are **not** supported. Wrapping one of them has no effect: the wrapper stays a no-op for the phases
it does not handle.

## The context seen by the predicates

The predicates are evaluated against the native context of the phase currently running, completed so
that a few keys are always there whatever the phase:

* `request` — always present. The backend call phase natively exposes only `raw_request`; it is
  aliased so that a predicate written once keeps working on every phase.
* `apikey` and `user` — always present, `null` when they are not known yet.

Every native key of the phase stays reachable on top of that: `raw_request` and `otoroshi_request`
during request transformation, `response`, `raw_response` and `otoroshi_response` during response
transformation, `backend` during the backend call, and `snowflake`, `config`, `global_config` and
`attrs` everywhere.

The whole route is available under `$.attrs['otoroshi.next.core.Route']`, so predicates on route
metadata or tags are written as `$.attrs['otoroshi.next.core.Route'].metadata.tier`.

One thing to keep in mind: `$.apikey` and `$.user` are only filled in from the access validation
phase onwards. A predicate on the apikey is always false during pre-routing, and during
`beforeRequest`.

## Evaluation modes

A single plugin often takes part in several phases, and a predicate can change value in between two
of them. `evaluation_mode` decides what happens:

* **`per_phase`** (default) — the predicates are evaluated again on every phase. The most reactive
  mode, but a plugin can run on some phases and not on others.
* **`once`** — the predicates are evaluated on the first phase the wrapper is called on, and the
  decision is reused for the rest of the request. Always consistent, at the price of a poorer
  context: the first call is `beforeRequest`, where no apikey and no user are known yet.
* **`latch`** — the predicates are evaluated on every phase until they match once, after which the
  wrapped plugin runs on every remaining phase.

### beforeRequest and afterRequest

Some plugins acquire a resource in `beforeRequest` and release it in `afterRequest` — the Coraza WAF
starts a WASM VM there, for instance. Those two are handled whatever the mode:

* if the predicates blocked `beforeRequest` but a later phase is delegated, `beforeRequest` is run
  first, exactly once;
* if `beforeRequest` ran, `afterRequest` always runs at the end of the request, whatever the
  predicates say by then.

### The limitation to keep in mind

With `per_phase` and `latch`, a phase that was skipped cannot be replayed afterwards. A plugin whose
request transformation was skipped but whose response transformation runs will see a request it
never modified. `beforeRequest` is the only exception, because it produces no output.

For a plugin that spans several phases, either use `once`, or write predicates over data that does
not change during a request: route metadata, request headers, the client IP, the HTTP method.

## Examples

### Check the apikey only for write calls

```json
{
  "predicates": [
    { "path": "$.request.method", "value": "Not(GET)" }
  ],
  "evaluation_mode": "once",
  "plugin": "cp:otoroshi.next.plugins.ApikeyCalls",
  "plugin_config": {}
}
```

### Serve a maintenance page to everyone but the internal network

```json
{
  "predicates": [
    { "path": "$.request.remote", "value": "RegexNot(10\\..*)" }
  ],
  "evaluation_mode": "once",
  "plugin": "cp:otoroshi.next.plugins.MaintenanceMode",
  "plugin_config": {}
}
```

### Express an OR

Only `AND` is supported inside one instance, so an `OR` is written by nesting a Conditional plugin
inside another one and inverting both, or by declaring two instances of the wrapper. Nesting is
capped at 5 levels.

## Other limitations

* The flow report of a route shows the Conditional plugin, not the wrapped one: the report is built
  per slot of the plugin chain, and the wrapper occupies the slot. Enable `debugFlow` on the route
  to see the wrapper being entered.
* A plain dotted path such as `$.apikey.metadata.tier`, or a bracket one such as
  `$.attrs['otoroshi.next.core.Route'].metadata.tier`, is read straight off the context: no
  serialisation, no JSON parsing. Anything richer — a recursive descent `$..tier`, a wildcard, an
  array index, a filter expression — falls back to a full JSONPath evaluation. That fallback
  serialises and parses the whole context, which holds the full route and every request attribute,
  once per phase, however many such predicates there are. If you need one of those and care about
  the cost, `once` brings it down to a single evaluation for the whole request.
