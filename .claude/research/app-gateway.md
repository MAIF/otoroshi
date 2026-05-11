# Directory `app/gateway/`

## Overview

This directory contains the **HTTP reverse proxy core** - the logic for processing incoming requests, routing them to backends, and handling responses.

**Note**: The new proxy engine is in `app/next/proxy/`. This directory mainly contains legacy code and shared utilities.

## Files

### `handlers.scala` (~1500+ lines)
**Role**: Main HTTP handlers and proxy entry point.

**Main classes**:

```scala
// Successful proxy result
case class ProxyDone(
  status: Int,
  isChunked: Boolean,
  upstreamLatency: Long,
  headersOut: Seq[Header],
  otoroshiHeadersOut: Seq[Header],
  otoroshiHeadersIn: Seq[Header]
)

// HTTP error handler
class ErrorHandler extends HttpErrorHandler {
  def onClientError(...)   // 4xx errors
  def onServerError(...)   // 5xx errors
}

// Actor for async metrics processing
class AnalyticsQueue extends Actor {
  // Receives AnalyticsQueueEvent
  // Updates metrics and quotas
}
```

**Responsibilities**:
- HTTP request entry point
- Play Framework error handling
- Dispatch to correct handler (admin API, BackOffice, Private Apps, Proxy)
- Session cookie management
- Analytics event generation

---

### `errors.scala` (~500+ lines)
**Role**: Formatted error response generation.

**Main class**: `Errors`

**Features**:
- Customizable error templates
- Error event generation (GatewayEvent)
- Custom HTML template support
- i18n error messages

**Main method**:
```scala
def craftResponseResult(
  message: String,
  status: Status,
  req: RequestHeader,
  maybeDescriptor: Option[ServiceDescriptor],
  maybeCauseId: Option[String],
  sendEvent: Boolean,
  attrs: TypedMap
): Future[Result]
```

---

### `circuitbreakers.scala` (~500+ lines)
**Role**: Circuit Breaker pattern implementation for resilience.

**Main classes**:

```scala
// Retry utility
object Retry {
  def retry[T](
    times: Int,          // Number of attempts
    delay: Long,         // Initial delay
    factor: Long,        // Multiplier factor
    ctx: String          // Context for logs
  )(f: Int => Future[T]): Future[T]
}

// Timeout utility
object Timeout {
  def timeout[A](message: => A, duration: FiniteDuration): Future[A]
}

// Circuit breakers holder
class CircuitBreakersHolder {
  // Circuit breakers cache by service/target
  // States: Closed, Open, HalfOpen
}
```

**Features**:
- Circuit breaker per backend target
- Automatic retry with exponential backoff
- Alerts on circuit open/close
- Backend health metrics

---

### `snowmonkey.scala` (~300+ lines)
**Role**: **Chaos engineering** tool for testing resilience.

**Main class**: `SnowMonkey`

**Fault injection types**:

| Type | Description |
|------|-------------|
| `LatencyInjection` | Adds random delay |
| `LargeRequest` | Adds data to request body |
| `LargeResponse` | Adds data to response body |
| `BadResponse` | Returns random error response |

**Configuration**:
```scala
ChaosConfig(
  enabled: Boolean,
  largeRequestFaultConfig: Option[LargeFaultConfig],
  largeResponseFaultConfig: Option[LargeFaultConfig],
  latencyInjectionFaultConfig: Option[LatencyConfig],
  badResponsesFaultConfig: Option[BadResponseConfig]
)

LatencyConfig(
  ratio: Double,    // % of affected requests
  from: Duration,   // Minimum delay
  to: Duration      // Maximum delay
)
```

---

### `generic.scala` (~2000+ lines)
**Role**: Generic routing logic and route matching.

**Features**:
- Hostname matching (exact, wildcard)
- Path matching (exact, regex, prefix)
- Headers and query params matching
- Backend selection (load balancing)
- Redirect handling

---

### `http.scala`
**Role**: HTTP protocol specifics.

**Features**:
- HTTP request parsing
- Special headers handling
- Request/response transformation
- Body streaming

---

### `websockets.scala`
**Role**: WebSocket connection proxy.

**Features**:
- HTTP to WebSocket upgrade
- Bidirectional message proxy
- Close frame handling
- Ping/pong support

---

### `requests.scala`
**Role**: Request representation and manipulation.

**Classes**:
- Wrappers around Play requests
- Routing information extraction
- Request attributes management

---

## Request Flow Architecture

```
HTTP Request
    │
    ▼
┌─────────────────┐
│  ErrorHandler   │◄─── Global errors
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ GatewayRequest  │ ← Route matching
│    Handler      │ ← Rights verification
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ CircuitBreaker  │ ← Backend state
│     Check       │
└────────┬────────┘
         │
         ├──── Open ──► 503 Error
         │
         ▼ Closed/HalfOpen
┌─────────────────┐
│  SnowMonkey     │ ← Fault injection
│  (if enabled)   │   (chaos engineering)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   Retry Loop    │ ← Multiple attempts
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Upstream Call   │ ← Backend call
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│   Response      │ ← Transformation
│  Processing     │ ← Otoroshi headers
└────────┬────────┘
         │
         ▼
    HTTP Response
```

## Key Points

1. **Resilience**: Circuit breakers + automatic retry
2. **Chaos Engineering**: SnowMonkey for resilience testing
3. **Observability**: Analytics events for each request
4. **Extensibility**: Plugin hooks at each stage
5. **Performance**: Non-blocking streaming with Akka Streams

## Relationships with Other Components

```
gateway/
    │
    ├── Uses → next/proxy/ (new engine)
    ├── Uses → plugins/ (plugin chain)
    ├── Uses → ssl/ (TLS termination)
    │
    ├── Called by → OtoroshiLoader (bootstrap)
    ├── Called by → netty/ (Netty server)
    │
    └── Emits to → events/ (analytics, alerts)
```
