# 🛡️ Circuit Breaker in Otoroshi

In Otoroshi, the circuit breaker is built into the gateway to protect both your users and your infrastructure. When a backend starts failing, timing out, or returning too many errors, Otoroshi doesn’t endlessly retry: it tracks errors over time, and if thresholds are exceeded, it “opens the circuit.” While the circuit is open, requests are blocked before reaching the failing backend, preventing overload. After a cooldown, Otoroshi will automatically test the backend again to decide whether to close the circuit and resume traffic

## Full Request Lifecycle with Otoroshi Circuit Breaker

```text
[ Start Request ]
      |
      |-- Try to connect ----------------------------X if > 10s [⏱ connectionTimeout]
      |
      |-- Send request ------------------------------|
      |                                              |
      |   |-- Wait response ------------------X if > 30s [⏱ callTimeout]
      |   |
      |   |-- If streaming --------------------------X if >120s inactivity [⏱ callAndStreamTimeout]
      |
      |-- If failure (❌) → retry with delays:
      |     Retry1 after 50ms
      |     Retry2 after 100ms
      |     Retry3 after 200ms ...
      |
      |-- All retries + calls cannot exceed --------X [⏱ globalTimeout = 30s]
      |
      |-- If connection stays open but idle --------X after 60s [⏱ idleTimeout]
      |
      |-- Errors tracked in 2s windows
      |   If >20 consecutive ❌ errors → Circuit opens 🔒
      |
[ End Request or Circuit Opened ]

```

## Parameters Overview
|:--|:--:|:--|
|Parameter|	Example Value |	Meaning |
|retries |	2	|Number of extra attempts after a failure|
|retryInitialDelay |	50 ms|	Delay before the first retry|
|backoffFactor |	2|	Exponential growth of retry delays (50ms → 100ms → 200ms …)|
|maxErrors |	20	|Consecutive errors tolerated before opening the circuit|
|callTimeout |	30 000 ms	|Max time to receive a full response (non-streaming)|
|callAndStreamTimeout |	120 000 ms|	Max time to handle a streaming response (SSE, gRPC, WebSocket)|
|connectionTimeout |	10 000 ms|	Max time to establish TCP/SSL connection|
|idleTimeout |	60 000 ms|	Max idle time before closing a keep-alive connection|
|globalTimeout |	30 000 ms|	Max total request duration including retries|
|sampleInterval |	2000 ms	|Observation window for errors/successes|

---

### Retries
- **Definition:** Number of additional attempts after a failure.  
- **Example:** If `retries = 1` and your call fails once (timeout, 5xx, etc.), Otoroshi retries **one more time**.  
- **Use case:** Helps absorb temporary errors (like a short backend overload).  

```text
Parameters:
  retries = 2
  retryInitialDelay = 50ms
  backoffFactor = 2
  globalTimeout = 30 000ms

Timeline:
t=0ms        50ms          100ms          200ms                    30 000ms
|--- Req1 ❌ --|             |--- Req2 ❌ --|             |--- Req3 ❌ --|   [⏱ Global Timeout]

Explanation:
Req1 fails → wait 50ms → Req2 fails → wait 100ms → Req3 fails
Even with retries, the total time cannot exceed 30s.

```

---

### ❌ Max Errors
- **Definition:** Maximum number of consecutive errors before Otoroshi opens the circuit.  
- **Example:** If your backend keeps returning `500`, after 20 such errors, Otoroshi will stop calling it for a while → the circuit is **opened** to protect your users and infrastructure.  

---

### Retry Initial Delay
- **Definition:** Time to wait before the first retry attempt.  
- **Example:** With `50 ms`, if a request fails, the retry is triggered **after 50 ms**, not immediately.  

---

### Backoff Factor
- **Definition:** Exponential growth factor for retry delays.  
- **Example with initial delay = 50 ms:**  
  - Retry 1 → after **50 ms**  
  - Retry 2 → after **100 ms**  
  - Retry 3 → after **200 ms** …  
- **Purpose:** Prevents hammering a backend that’s already in trouble.  

---

### Call Timeout
- **Definition:** Maximum time to receive a full response from the backend (non-streaming).  
- **Example:** If your API takes more than **30s** for a `GET`, Otoroshi will terminate the request.  

```text
Parameters:
  callTimeout = 30 000ms

Timeline:
t=0ms                                     30 000ms
|---- Waiting for full response ----------X [⏱ Call Timeout]

Explanation:
If the backend does not send the complete response within 30s → Otoroshi closes the request.

```

---

### Call and Stream Timeout
- **Definition:** Maximum time for handling a **streaming response** (SSE, WebSocket, gRPC stream).  
- **Example:** If a log streaming API runs continuously, the connection is cut after **2 minutes** of inactivity.  

```text
Parameters:
  callAndStreamTimeout = 120 000ms

Timeline:
0ms     20s     45s     70s     90s                        120s
|Msg 🟢| |Msg 🟢| |Msg 🟢| |Msg 🟢| |Msg 🟢| ---- inactivity ----X [⏱ Stream Timeout]

Explanation:
If no data is received for 120s → Otoroshi stops the stream.
```
---

### Connection Timeout
- **Definition:** Maximum time to establish a TCP/SSL connection.  
- **Example:** If the backend takes longer than **10s** to accept, Otoroshi gives up.  

```text
Parameters:
  connectionTimeout = 10 000ms

Timeline:
t=0ms                                  10 000ms
|---- Trying to connect ---------------X [⏱ Connection Timeout]

Explanation:
If the backend does not accept the TCP/SSL connection within 10s → Otoroshi aborts.

```

---

### Idle Timeout (Akka http client only)
- **Definition:** Maximum time a connection can stay open **without activity**.  
- **Example:** If a keep-alive connection has no traffic for **60s**, Otoroshi closes it.  

```text
Parameters:
  idleTimeout = 60 000ms

Timeline:
0ms       12s                                      60s
|--- Activity 🟢 ---| ....... no activity .........X [⏱ Idle Timeout]

Explanation:
A keep-alive connection with no data for 60s is closed → prevents zombie sockets.

```
---

### Global Timeout
- **Definition:** Maximum total time a request (including retries) can take.  

Distinction between `callTimeout` and `globalTimeout`

```text
Parameters:
  retries = 2
  retryInitialDelay = 50ms
  backoffFactor = 2
  callTimeout = 10 000ms
  globalTimeout = 25 000ms

Timeline:
t=0ms          10 000ms          20 000ms          25 000ms
|-- Attempt1 ❌ |-- Attempt2 ❌ |-- Attempt3 ❌ | [⏱ Global Timeout reached]
```

Explanation:

- Each attempt cannot exceed callTimeout (10s) → X marks when a single call fails.
- All retries combined cannot exceed globalTimeout (25s).
- After 25s, no further attempts are made, even if retries remain.

@@@note 
- callTimeout stops a single request attempt if it takes too long.
- globalTimeout stops all retries combined, enforcing a hard upper limit on the total request duration.
@@@
---

### Sample Interval
- **Definition:** Specify the sliding window time for the circuit breaker in milliseconds, after this time, error count will be reseted

```text
Parameters:
  sampleInterval = 2000ms
  maxErrors = 20

Timeline:
Errors: ❌ ❌ ❌ ❌ ❌ ❌ ❌ ❌ ❌ ❌ ❌ ❌ ❌ ❌ ❌ ❌ ❌ ❌ ❌ ❌
Time →  |-- 2s window --|-- 2s window --|-- 2s window --|

Explanation:
After 20 consecutive errors in observed intervals → Circuit opens
Otoroshi stops calling the backend temporarily to protect users & infra.
```

## Healthcheck on routes

Otoroshi continuously checks the health of backend services to decide whether they are safe to receive traffic. The circuit breaker uses these results to move between healthy (green), degraded (yellow), and unhealthy (red) states.

### Parameters

* **Enabled**: Enable or disable health checks.
* **URL**: The endpoint on the backend that Otoroshi will call to verify health.
* **Timeout**: Maximum time allowed for the health check request (**default: 5 seconds**).
* **Healthy statuses**: By default, HTTP response codes in the range **200–499** (excluding 500) are considered healthy.
* **Unhealthy statuses**: HTTP status codes **<199** or **>500** are considered unhealthy.
* **Per-route overrides**: Both healthy and unhealthy status code lists can be overridden for each route, allowing fine-grained control depending on the service behavior.

### Custom Header Validation

For each health check, Otoroshi sends a header:

```
Otoroshi-Health-Check-Logic-Test: <numeric_value>
```

where `<numeric_value>` is a number dynamically generated by Otoroshi for that specific request.

Your backend must respond with:

```
Otoroshi-Health-Check-Logic-Test-Result: <numeric_value> + 42
```

Example:

* Request header → `Otoroshi-Health-Check-Logic-Test: 735`
* Expected response header → `Otoroshi-Health-Check-Logic-Test-Result: 777`

### Outcomes

#### Green (healthy)

- Backend responds within the timeout.
- Status code is in the healthy range (200–499).
- Header `Otoroshi-Health-Check-Logic-Test-Result` is present and correct.

#### Yellow (degraded)

Backend responds, but the response header is missing, malformed, or contains an incorrect value.

#### Red (unhealthy)

Backend times out or returns a status code **<199** or **>500**.

When a backend is marked **red**, the circuit breaker will **open**, cutting traffic to that service until it recovers.

