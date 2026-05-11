# Otoroshi Plugins Reference

This document provides a comprehensive reference of all available plugins in Otoroshi's new engine (`app/next/plugins`) and Kubernetes plugins.

## Table of Contents

- [Access Control & Authentication](#access-control--authentication)
- [JWT & Token Management](#jwt--token-management)
- [OAuth2 & OIDC](#oauth2--oidc)
- [Client Certificates](#client-certificates)
- [Authorization & RBAC](#authorization--rbac)
- [Request/Response Headers](#requestresponse-headers)
- [Cookies](#cookies)
- [Traffic Control & Throttling](#traffic-control--throttling)
- [Compression & Encoding](#compression--encoding)
- [CORS & Protocol](#cors--protocol)
- [Caching](#caching)
- [Geolocation](#geolocation)
- [IP & Network Security](#ip--network-security)
- [Security Headers & HMAC](#security-headers--hmac)
- [Data Transformation](#data-transformation)
- [Static Content & Files](#static-content--files)
- [Mock & Test Responses](#mock--test-responses)
- [Backend Routing & Discovery](#backend-routing--discovery)
- [Service Endpoints](#service-endpoints)
- [GraphQL & gRPC](#graphql--grpc)
- [WebSocket](#websocket)
- [WebAssembly (WASM)](#webassembly-wasm)
- [Tunnel & Protocol](#tunnel--protocol)
- [Chaos Engineering](#chaos-engineering)
- [Canary & Traffic Management](#canary--traffic-management)
- [Integrations](#integrations)
- [Vulnerability Protection](#vulnerability-protection)
- [Miscellaneous](#miscellaneous)
- [Workflow Engine](#workflow-engine)
- [GreenScore](#greenscore)
- [Coraza WAF](#coraza-waf)
- [Remote Tunnels](#remote-tunnels)
- [Admin Extensions](#admin-extensions)
- [Kubernetes Plugins](#kubernetes-plugins)

---

## Access Control & Authentication

**File**: `apikey.scala`

| Plugin | Description |
|--------|-------------|
| `NgLegacyApikeyCall` | Legacy API key validation (compatibility mode) |
| `ApikeyCalls` | Modern API key extraction and validation |
| `ApikeyAuthModule` | Basic auth using API keys as credentials |
| `NgApikeyMandatoryTags` | Enforces API keys must have specific tags |
| `NgApikeyMandatoryMetadata` | Enforces API keys must have specific metadata |

**File**: `auth.scala`

| Plugin | Description |
|--------|-------------|
| `NgLegacyAuthModuleCall` | Legacy authentication module wrapper |
| `MultiAuthModule` | Chains multiple authentication modules |
| `AuthModule` | Main authentication module orchestrator |
| `NgAuthModuleUserExtractor` | Extracts user info from auth modules |
| `NgAuthModuleExpectedUser` | Validates user expectations |
| `BasicAuthCaller` | Calls external basic auth |
| `SimpleBasicAuth` | Built-in basic auth validator |
| `NgExpectedConsumer` | Validates expected consumers/principals |
| `BasicAuthWithAuthModule` | Basic auth integrated with auth modules |

---

## JWT & Token Management

**File**: `jwt.scala`

| Plugin | Description |
|--------|-------------|
| `JwtVerification` | JWT token verification |
| `JwtVerificationOnly` | JWT verification without transformation |
| `JwtSigner` | Signs JWT tokens |
| `JweSigner` | Creates JWE (encrypted JWT) tokens |
| `JweExtractor` | Extracts and decrypts JWE tokens |
| `OIDCJwtVerifier` | OIDC-specific JWT verification |

---

## OAuth2 & OIDC

**File**: `oauth.scala`

| Plugin | Description |
|--------|-------------|
| `OAuth1Caller` | OAuth 1.0 caller |
| `OAuth2Caller` | OAuth 2.0 caller |

**File**: `oidc.scala`

| Plugin | Description |
|--------|-------------|
| `OIDCHeaders` | Injects OIDC user info into headers |
| `OIDCAccessTokenValidator` | Validates OIDC access tokens |
| `OIDCAccessTokenAsApikey` | Converts OIDC tokens to API keys |
| `OIDCAuthToken` | OIDC authentication token handler |

**File**: `auth0passwordless.scala`

| Plugin | Description |
|--------|-------------|
| `Auth0PasswordlessStartFlowEndpoint` | Auth0 passwordless flow start |
| `Auth0PasswordlessEndFlowEndpoint` | Auth0 passwordless flow completion |
| `Auth0PasswordlessStartEndFlowEndpoints` | Combined Auth0 flow endpoints |
| `Auth0PasswordlessFlow` | Complete Auth0 passwordless flow |

**File**: `clientcredentials.scala`

| Plugin | Description |
|--------|-------------|
| `NgClientCredentials` | Client credentials OAuth2 grant handler |
| `NgClientCredentialTokenEndpoint` | Token endpoint for client credentials |

---

## Client Certificates

**File**: `clientcert.scala`

| Plugin | Description |
|--------|-------------|
| `NgHasClientCertValidator` | Checks for client certificate presence |
| `NgHasClientCertMatchingApikeyValidator` | Matches cert to API key |
| `NgHasClientCertMatchingValidator` | Matches cert to stored certificate |
| `NgClientCertChainHeader` | Injects cert chain into headers |
| `NgCertificateAsApikey` | Uses client cert as API key |
| `NgHasClientCertMatchingHttpValidator` | HTTP-based cert validation |

---

## Authorization & RBAC

**File**: `rbac.scala`

| Plugin | Description |
|--------|-------------|
| `RBAC` | Role-based access control |

**File**: `paths.scala`

| Plugin | Description |
|--------|-------------|
| `PublicPrivatePaths` | Path-based access control (public/private routing) |

**File**: `restrictions.scala`

| Plugin | Description |
|--------|-------------|
| `RoutingRestrictions` | Restricts access based on routing rules |

**File**: `users.scala`

| Plugin | Description |
|--------|-------------|
| `NgHasAllowedUsersValidator` | User whitelist validation |
| `NgJwtUserExtractor` | JWT user extraction |

---

## Request/Response Headers

**File**: `headers.scala`

| Plugin | Description |
|--------|-------------|
| `OverrideHost` | Replaces Host header with backend hostname |
| `OverrideLocationHeader` | Rewrites Location header URLs |
| `HeadersValidation` | Validates incoming headers |
| `OtoroshiHeadersIn` | Injects Otoroshi internal headers |
| `AdditionalHeadersOut` | Adds custom response headers |
| `AdditionalHeadersIn` | Adds custom request headers |
| `MissingHeadersIn` | Adds missing request headers |
| `MissingHeadersOut` | Adds missing response headers |
| `RemoveHeadersOut` | Removes response headers |
| `RemoveHeadersIn` | Removes request headers |
| `SendOtoroshiHeadersBack` | Echoes Otoroshi headers in response |
| `XForwardedHeaders` | Handles X-Forwarded-* headers |
| `ForwardedHeader` | Handles RFC 7239 Forwarded header |
| `RejectHeaderInTooLong` | Rejects overly long request headers |
| `RejectHeaderOutTooLong` | Rejects overly long response headers |
| `LimitHeaderInTooLong` | Truncates long request headers |
| `LimitHeaderOutTooLong` | Truncates long response headers |

---

## Cookies

**File**: `cookies.scala`

| Plugin | Description |
|--------|-------------|
| `AdditionalCookieIn` | Adds request cookies |
| `AdditionalCookieOut` | Adds response cookies |
| `RemoveCookiesIn` | Removes request cookies |
| `RemoveCookiesOut` | Removes response cookies |
| `MissingCookieIn` | Adds missing request cookies |
| `MissingCookieOut` | Adds missing response cookies |
| `CookiesValidation` | Validates cookies |

---

## Traffic Control & Throttling

**File**: `quotas.scala`

| Plugin | Description |
|--------|-------------|
| `GlobalPerIpAddressThrottling` | IP-based rate limiting |
| `GlobalThrottling` | Global rate limiting |
| `ApikeyQuotas` | Per API key quotas |
| `NgServiceQuotas` | Per service/route quotas |
| `NgCustomQuotas` | Custom quota implementation |
| `NgCustomThrottling` | Custom throttling rules |

**File**: `bodysize.scala`

| Plugin | Description |
|--------|-------------|
| `RequestBodyLengthLimiter` | Limits request body size |
| `ResponseBodyLengthLimiter` | Limits response body size |
| `RequestBandwidthThrottling` | Throttles request bandwidth |
| `ResponseBandwidthThrottling` | Throttles response bandwidth |

---

## Compression & Encoding

**File**: `gzip.scala`

| Plugin | Description |
|--------|-------------|
| `GzipResponseCompressor` | Compresses responses with gzip |

**File**: `brotli.scala`

| Plugin | Description |
|--------|-------------|
| `BrotliResponseCompressor` | Compresses responses with Brotli |

---

## CORS & Protocol

**File**: `cors.scala`

| Plugin | Description |
|--------|-------------|
| `Cors` | Cross-Origin Resource Sharing (CORS) handler |

**File**: `http.scala`

| Plugin | Description |
|--------|-------------|
| `ReadOnlyCalls` | Restricts to safe HTTP methods (GET, HEAD, etc.) |
| `AllowHttpMethods` | Restricts to specific HTTP methods |

**File**: `https.scala`

| Plugin | Description |
|--------|-------------|
| `ForceHttpsTraffic` | Redirects HTTP to HTTPS |
| `BlockHttpTraffic` | Blocks HTTP traffic |

**File**: `protocol.scala`

| Plugin | Description |
|--------|-------------|
| `DisableHttp10` | Disables HTTP/1.0 |

**File**: `http3.scala`

| Plugin | Description |
|--------|-------------|
| `Http3Switch` | HTTP/3 protocol switching |

---

## Caching

**File**: `cache.scala`

| Plugin | Description |
|--------|-------------|
| `NgHttpClientCache` | HTTP client-side caching |
| `NgResponseCache` | Response caching |
| `NgResponseCacheCleanupJob` | Cache maintenance job |

---

## Geolocation

**File**: `geoloc.scala`

| Plugin | Description |
|--------|-------------|
| `NgMaxMindGeolocationInfoExtractor` | MaxMind geolocation lookup |
| `NgIpStackGeolocationInfoExtractor` | IPStack geolocation lookup |
| `NgGeolocationInfoHeader` | Injects geolocation into headers |
| `NgGeolocationInfoEndpoint` | Geolocation info endpoint |

---

## IP & Network Security

**File**: `ipaddress.scala`

| Plugin | Description |
|--------|-------------|
| `IpAddressAllowedList` | IP whitelist |
| `IpAddressBlockList` | IP blacklist |
| `EndlessHttpResponse` | Debug/test endpoint for streaming |

**File**: `alloweddomains.scala`

| Plugin | Description |
|--------|-------------|
| `NgIncomingRequestValidatorAllowedDomainNames` | Domain whitelist |
| `NgIncomingRequestValidatorDeniedDomainNames` | Domain blacklist |

---

## Security Headers & HMAC

**File**: `security.scala`

| Plugin | Description |
|--------|-------------|
| `NgSecurityTxt` | Serves .well-known/security.txt |
| `SecurityHeadersPlugin` | Injects security headers (HSTS, CSP, X-Frame-Options, etc.) |

**File**: `hmac.scala`

| Plugin | Description |
|--------|-------------|
| `HMACValidator` | HMAC signature validation |
| `HMACCaller` | HMAC request signing |

**File**: `biscuit.scala`

| Plugin | Description |
|--------|-------------|
| `NgBiscuitExtractor` | Biscuit token extraction |
| `NgBiscuitValidator` | Biscuit authorization token validation |

---

## Data Transformation

**File**: `xml.scala`

| Plugin | Description |
|--------|-------------|
| `XmlToJsonRequest` | Converts XML requests to JSON |
| `JsonToXmlRequest` | Converts JSON requests to XML |
| `XmlToJsonResponse` | Converts XML responses to JSON |
| `JsonToXmlResponse` | Converts JSON responses to XML |
| `SOAPAction` | SOAP action handling |

**File**: `regex.scala`

| Plugin | Description |
|--------|-------------|
| `RegexResponseBodyRewriter` | Regex-based response rewriting |
| `RegexRequestBodyRewriter` | Regex-based request rewriting |
| `RegexRequestHeadersRewriter` | Regex-based header rewriting |
| `RegexResponseHeadersRewriter` | Regex-based response header rewriting |

**File**: `jq.scala`

| Plugin | Description |
|--------|-------------|
| `JQ` | jq JSON query processor |
| `JQRequest` | jq on requests |
| `JQResponse` | jq on responses |

**File**: `query.scala`

| Plugin | Description |
|--------|-------------|
| `QueryTransformer` | Query string transformation |

---

## Static Content & Files

**File**: `asset.scala`

| Plugin | Description |
|--------|-------------|
| `StaticAssetEndpoint` | Serves HTTP static assets |

**File**: `files.scala`

| Plugin | Description |
|--------|-------------|
| `StaticBackend` | File system static content backend |
| `S3Backend` | Amazon S3 backend |

**File**: `polyfill.scala`

| Plugin | Description |
|--------|-------------|
| `PolyfillIoReplacer` | Rewrites polyfill.io URLs |
| `PolyfillIoDetector` | Detects polyfill usage |

**File**: `zip.scala`

| Plugin | Description |
|--------|-------------|
| `ZipFileBackend` | Serve ZIP file contents |
| `ZipBombBackend` | ZIP bomb detection/protection |

---

## Mock & Test Responses

**File**: `response.scala`

| Plugin | Description |
|--------|-------------|
| `StaticResponse` | Returns static responses |
| `MockResponses` | Mocks backend responses |
| `NgErrorRewriter` | Rewrites error responses |

**File**: `echo.scala`

| Plugin | Description |
|--------|-------------|
| `EchoBackend` | Echo request details |
| `RequestBodyEchoBackend` | Echo request body |

---

## Backend Routing & Discovery

**File**: `discovery.scala`

| Plugin | Description |
|--------|-------------|
| `NgDiscoverySelfRegistrationSink` | Service self-registration |
| `NgDiscoverySelfRegistrationTransformer` | Registration transformer |
| `NgDiscoveryTargetsSelector` | Service discovery target selection |

**File**: `eureka.scala`

| Plugin | Description |
|--------|-------------|
| `EurekaServerSink` | Eureka server interaction |
| `EurekaTarget` | Eureka target selection |
| `ExternalEurekaTarget` | External Eureka integration |

---

## Service Endpoints

**File**: `identity.scala`

| Plugin | Description |
|--------|-------------|
| `UserProfileEndpoint` | User profile endpoint |
| `ConsumerEndpoint` | Consumer info endpoint |
| `UserLogoutEndpoint` | User logout endpoint |

**File**: `otoroshi.scala`

| Plugin | Description |
|--------|-------------|
| `OtoroshiChallenge` | Otoroshi challenge/verification |
| `OtoroshiInfos` | Otoroshi metadata endpoint |
| `OtoroshiOCSPResponderEndpoint` | OCSP responder |
| `OtoroshiAIAEndpoint` | AIA (Authority Info Access) endpoint |
| `OtoroshiJWKSEndpoint` | JWKS (JSON Web Key Set) endpoint |
| `OtoroshiHealthEndpoint` | Health check endpoint |
| `OtoroshiMetricsEndpoint` | Metrics export endpoint |

**File**: `swagger.scala`

| Plugin | Description |
|--------|-------------|
| `SwaggerUIPlugin` | Swagger UI documentation |

---

## GraphQL & gRPC

**File**: `graphql.scala`

| Plugin | Description |
|--------|-------------|
| `GraphQLQuery` | GraphQL query proxy |
| `GraphQLBackend` | GraphQL backend integration |
| `GraphQLProxy` | Full GraphQL proxy |

**File**: `grpc.scala`

| Plugin | Description |
|--------|-------------|
| `GrpcWebProxyPlugin` | gRPC-Web proxy |

---

## WebSocket

**File**: `websocket.scala`

| Plugin | Description |
|--------|-------------|
| `WebsocketContentValidatorIn` | Validates incoming WS messages |
| `WebsocketTypeValidator` | Type validation for WS messages |
| `WebsocketJsonFormatValidator` | JSON format validation |
| `WebsocketSizeValidator` | Message size limits |
| `JqWebsocketMessageTransformer` | jq transformation on WS |
| `WasmWebsocketTransformer` | WASM transformation on WS |
| `WorkflowWebsocketTransformer` | Workflow-based WS transformation |
| `WebsocketMirrorBackend` | WS traffic mirroring |
| `YesWebsocketBackend` | Test WS echo backend |

---

## WebAssembly (WASM)

**File**: `wasm.scala`

| Plugin | Description |
|--------|-------------|
| `WasmRouteMatcher` | WASM-based route matching |
| `WasmPreRoute` | WASM pre-routing logic |
| `WasmBackend` | WASM backend calls |
| `WasmAccessValidator` | WASM access validation |
| `WasmRequestTransformer` | WASM request transformation |
| `WasmResponseTransformer` | WASM response transformation |
| `WasmSink` | WASM request sink |
| `WasmRequestHandler` | WASM request handler |
| `WasmOPA` | Open Policy Agent (OPA) integration |
| `WasmRouter` | WASM router plugin |
| `WasmJobsLauncher` | WASM job execution |

---

## Tunnel & Protocol

**File**: `tunnels.scala`

| Plugin | Description |
|--------|-------------|
| `TcpTunnel` | TCP tunneling |
| `UdpTunnel` | UDP tunneling |

---

## Chaos Engineering

**File**: `chaos.scala`

| Plugin | Description |
|--------|-------------|
| `SnowMonkeyChaos` | Chaos engineering plugin (SnowMonkey integration) |

---

## Canary & Traffic Management

**File**: `canary.scala`

| Plugin | Description |
|--------|-------------|
| `CanaryMode` | Canary deployment with traffic splitting |
| `TimeControlledCanaryMode` | Time-based canary releases |

**File**: `mirror.scala`

| Plugin | Description |
|--------|-------------|
| `NgTrafficMirroring` | Mirrors traffic to secondary backend |

---

## Integrations

**File**: `izanami.scala`

| Plugin | Description |
|--------|-------------|
| `NgIzanamiV1Proxy` | Izanami feature flags (v1) |
| `NgIzanamiV1Canary` | Izanami canary features |
| `IzanamiV2Proxy` | Izanami v2 proxy |

**File**: `tailscale.scala`

| Plugin | Description |
|--------|-------------|
| `TailscaleSelectTargetByName` | Tailscale VPN integration |
| `TailscaleTargetsJob` | Tailscale targets refresh |
| `TailscaleCertificatesFetcherJob` | Tailscale cert fetching |

**File**: `openfga.scala`

| Plugin | Description |
|--------|-------------|
| `OpenFGAValidator` | OpenFGA authorization |

---

## Vulnerability Protection

**File**: `lo4shell.scala`

| Plugin | Description |
|--------|-------------|
| `NgLog4ShellFilter` | Log4Shell vulnerability protection |

**File**: `react2shell.scala`

| Plugin | Description |
|--------|-------------|
| `React2SShellDetector` | React2 shell detection |

**File**: `fail2ban.scala`

| Plugin | Description |
|--------|-------------|
| `Fail2BanPlugin` | Fail2ban integration |

---

## Miscellaneous

**File**: `tracing.scala`

| Plugin | Description |
|--------|-------------|
| `W3CTracing` | W3C distributed tracing |

**File**: `robot.scala`

| Plugin | Description |
|--------|-------------|
| `Robots` | robots.txt handling |

**File**: `time.scala`

| Plugin | Description |
|--------|-------------|
| `TimeRestrictedAccessPlugin` | Time-based access control |

**File**: `ua.scala`

| Plugin | Description |
|--------|-------------|
| `NgUserAgentExtractor` | User-agent parsing and injection |
| `NgUserAgentInfoHeader` | User-agent info header |
| `NgUserAgentInfoEndpoint` | User-agent info endpoint |

**File**: `html.scala`

| Plugin | Description |
|--------|-------------|
| `NgHtmlPatcher` | HTML patching/rewriting |

**File**: `img.scala`

| Plugin | Description |
|--------|-------------|
| `ImageReplacer` | Image content rewriting |

**File**: `defer.scala`

| Plugin | Description |
|--------|-------------|
| `DeferPlugin` | Async response deferral |

**File**: `redirection.scala`

| Plugin | Description |
|--------|-------------|
| `Redirection` | HTTP redirects |

**File**: `modes.scala`

| Plugin | Description |
|--------|-------------|
| `MaintenanceMode` | Maintenance mode |
| `GlobalMaintenanceMode` | Global maintenance |
| `BuildMode` | Build mode indication |

**File**: `context.scala`

| Plugin | Description |
|--------|-------------|
| `ContextValidation` | Request context validation |

**File**: `request.scala`

| Plugin | Description |
|--------|-------------|
| `NgDefaultRequestBody` | Default request body handling |

**File**: `external.scala`

| Plugin | Description |
|--------|-------------|
| `NgExternalValidator` | External validation calls |

**File**: `wrappers.scala`

| Plugin | Description |
|--------|-------------|
| `PreRoutingWrapper` | Pre-routing plugin wrapper |
| `AccessValidatorWrapper` | Access validator wrapper |
| `RequestSinkWrapper` | Request sink wrapper |
| `RequestTransformerWrapper` | Request transformer wrapper |
| `CompositeWrapper` | Composite plugin wrapper |

**File**: `hello.scala`

| Plugin | Description |
|--------|-------------|
| `HelloBackend` | Simple hello world backend (demo/test) |

---

## Workflow Engine

**Location**: `app/next/workflow/`

The Workflow Engine allows you to create complex request processing pipelines using a visual designer or JSON configuration.

### Workflow Plugins

**File**: `plugins.scala`

| Plugin | Type | Description |
|--------|------|-------------|
| `WorkflowBackend` | NgBackendCall | Uses a workflow as a backend to process requests |
| `WorkflowRequestTransformer` | NgRequestTransformer | Transforms request content using a workflow |
| `WorkflowResponseTransformer` | NgRequestTransformer | Transforms response content using a workflow |
| `WorkflowAccessValidator` | NgAccessValidator | Delegates route access control to a workflow |
| `WorkflowResumeBackend` | NgBackendCall | Resumes a paused workflow |

### Workflow Node Types

**File**: `nodes.scala`

| Node | Name | Description |
|------|------|-------------|
| `WorkflowNode` | workflow | Executes a sequence of nodes sequentially |
| `CallNode` | call | Calls a function and returns its result |
| `AssignNode` | assign | Executes memory assignation operations |
| `ParallelFlowsNode` | parallel | Executes multiple nodes in parallel |
| `SwitchNode` | switch | Executes the first path matching a predicate |
| `IfThenElseNode` | if | Conditional node execution |
| `ForEachNode` | foreach | Iterates over array elements |
| `MapNode` | map | Transforms array by applying a node on each value |
| `FilterNode` | filter | Filters array values based on a predicate |
| `FlatMapNode` | flatmap | Flattens an array by applying a node |
| `WaitNode` | wait | Waits a certain amount of time |
| `ErrorNode` | error | Returns an error and stops the workflow |
| `ValueNode` | value | Returns a static value |
| `PauseNode` | pause | Pauses the current workflow |
| `EndNode` | end | Ends the workflow at this point |
| `WhileNode` | while | Loops while a predicate is true |
| `JumpNode` | jump | Jumps to another node by ID |
| `TryNode` | try | Catches errors and can return alternatives |
| `AsyncNode` | async | Runs a node asynchronously |
| `BreakPointNode` | breakpoint | Pauses workflow in designer mode |
| `NoopNode` | noop | Does nothing (fallback) |

### Workflow Functions

**File**: `functions.scala`

| Function | Name | Description |
|----------|------|-------------|
| `LogFunction` | core.log | Logs messages |
| `HelloFunction` | core.hello | Hello world function |
| `HttpClientFunction` | core.http_client | Makes HTTP requests |
| `WasmCallFunction` | core.wasm_call | Calls a WASM function |
| `WorkflowCallFunction` | core.workflow_call | Calls another workflow |
| `SystemCallFunction` | core.system_call | Executes system commands |
| `StoreKeysFunction` | core.store_keys | Gets all keys from data store |
| `StoreMgetFunction` | core.store_mget | Multiple get from data store |
| `StoreGetAllFunction` | core.store_get_all | Gets all entries from data store |
| `StoreMatchFunction` | core.store_match | Matches keys in data store |
| `StoreGetFunction` | core.store_get | Gets a single value |
| `StoreSetFunction` | core.store_set | Sets a value |
| `StoreDelFunction` | core.store_del | Deletes a key |
| `EmitEventFunction` | core.emit_event | Emits an analytics event |
| `FileReadFunction` | core.file_read | Reads file contents |
| `FileWriteFunction` | core.file_write | Writes file contents |
| `FileDeleteFunction` | core.file_del | Deletes a file |
| `StateGetAllFunction` | core.state_get_all | Gets all state entries |
| `StateGetOneFunction` | core.state_get | Gets a single state entry |
| `SendMailFunction` | core.send_mail | Sends email messages |
| `EnvGetFunction` | core.env_get | Gets environment variables |
| `ConfigReadFunction` | core.config_read | Reads configuration values |
| `ComputeResumeTokenFunction` | core.compute_resume_token | Generates resume token |
| `MemoryGetFunction` | core.memory_get | Gets workflow memory value |
| `MemorySetFunction` | core.memory_set | Sets workflow memory value |
| `MemoryDelFunction` | core.memory_del | Deletes workflow memory value |
| `MemoryRenameFunction` | core.memory_rename | Renames workflow memory key |

### Workflow Operators

**File**: `operators.scala`

| Operator | Name | Description |
|----------|------|-------------|
| `MemRefOperator` | $mem_ref | Memory reference |
| `ArrayAppendOperator` | $array_append | Appends to array |
| `ArrayPrependOperator` | $array_prepend | Prepends to array |
| `MergeObjectsOperator` | $merge_objects | Merges objects |
| `ArrayAtOperator` | $array_at | Gets element at index |
| `ArrayDelOperator` | $array_del | Deletes element |
| `ArrayPageOperator` | $array_page | Paginates array |
| `ArrayHeadOperator` | $array_head | Gets first element |
| `ArrayTailOperator` | $array_tail | Gets all but first |
| `ArrayInitOperator` | $array_init | Gets all but last |
| `ArrayLengthOperator` | $array_length | Gets array length |
| `ArrayJoinOperator` | $array_join | Joins array elements |
| `ArrayDropOperator` | $array_drop | Drops first N elements |
| `ArrayTakeOperator` | $array_take | Takes first N elements |
| `ArrayReverseOperator` | $array_reverse | Reverses array |
| `ArrayDistinctOperator` | $array_distinct | Gets distinct elements |
| `ArrayIsEmptyOperator` | $array_is_empty | Checks if empty |
| `ProjectionOperator` | $projection | Projects fields |
| `MapPutOperator` | $map_put | Adds/updates map entry |
| `MapGetOperator` | $map_get | Gets map value |
| `MapDelOperator` | $map_del | Deletes map entry |
| `MapRenameOperator` | $map_rename | Renames map key |
| `MapIsEmptyOperator` | $map_is_empty | Checks if map empty |
| `MapLengthOperator` | $map_length | Gets map size |
| `JsonParseOperator` | $json_parse | Parses JSON strings |
| `StrConcatOperator` | $str_concat | Concatenates strings |
| `IsTruthyOperator` | $is_truthy | Checks if truthy |
| `IsFalsyOperator` | $is_falsy | Checks if falsy |
| `ContainsOperator` | $contains | Checks containment |
| `EqOperator` | $eq | Equality comparison |
| `NeqOperator` | $neq | Not equal |
| `GtOperator` | $gt | Greater than |
| `LtOperator` | $lt | Less than |
| `GteOperator` | $gte | Greater or equal |
| `LteOperator` | $lte | Less or equal |
| `EncodeBase64Operator` | $encode_base64 | Encodes to Base64 |
| `DecodeBase64Operator` | $decode_base64 | Decodes from Base64 |
| `BasicAuthOperator` | $basic_auth | Generates Basic Auth |
| `NowOperator` | $now | Gets current timestamp |
| `NotOperator` | $not | Logical NOT |
| `AndOperator` | $and | Logical AND |
| `OrOperator` | $or | Logical OR |
| `ParseDateTimeOperator` | $parse_datetime | Parses datetime |
| `ParseDateOperator` | $parse_date | Parses date |
| `ParseTimeOperator` | $parse_time | Parses time |
| `AddOperator` | $add | Addition |
| `SubtractOperator` | $subtract | Subtraction |
| `MultiplyOperator` | $multiply | Multiplication |
| `DivideOperator` | $divide | Division |
| `RemainderOperator` | $remainder | Remainder |
| `IncrementOperator` | $incr | Increments value |
| `DecrementOperator` | $decr | Decrements value |
| `UppercaseOperator` | $str_upper_case | Converts to uppercase |
| `LowercaseOperator` | $str_lower_case | Converts to lowercase |
| `StringSplitOperator` | $str_split | Splits strings |
| `ExpressionLanguageOperator` | $expression_language | Evaluates EL |
| `StringifyOperator` | $stringify | Converts to JSON string |
| `PrettifyOperator` | $prettify | Pretty-prints JSON |
| `StringReplaceOperator` | $str_replace | Replaces first match |
| `StringReplaceAllOperator` | $str_replace_all | Replaces all matches |
| `JqOperator` | $jq | JQ expression |
| `RoundOperator` | $round | Rounds numbers |
| `ParseNumberOperator` | $parse_number | Parses numbers |
| `FirstTruthyOperator` | $first_truthy | First truthy value |
| `ContainsIgnoreCaseOperator` | $contains_ignore_case | Case-insensitive contains |

---

## GreenScore

**Location**: `app/greenscore/`

GreenScore is an ecological scoring system for API routes based on defined rules and metrics.

### GreenScore Extension

**File**: `extension.scala`

| Plugin | Type | Description |
|--------|------|-------------|
| `GreenScoreExtension` | AdminExtension | Main extension managing GreenScore feature with REST API routes for scores, efficiency, and templates |
| `OtoroshiEventListener` | Actor | Listens to gateway events and updates route ecological metrics |

### GreenScore Rules Configuration

**File**: `greenrules.scala`

| Plugin | Type | Description |
|--------|------|-------------|
| `RulesRouteConfiguration` | NgPluginConfig | Configuration for rule states on routes |

### Ecological Rules

GreenScore includes 23 predefined ecological rules:
- **Architecture (AR01-AR04)**: Architecture-related rules
- **Design (DE01-DE11)**: Design-related rules
- **Usage (US01-US07)**: Usage-related rules
- **Log (LO01)**: Logging-related rules

---

## Coraza WAF

**Location**: `app/wasm/`

Coraza is a Web Application Firewall (WAF) integrated via WebAssembly.

**File**: `coraza.scala`

| Plugin | Type | Description |
|--------|------|-------------|
| `NgCorazaWAF` | NgRequestTransformer | Coraza WAF plugin for web application firewall protection |
| `NgIncomingRequestValidatorCorazaWAF` | NgIncomingRequestValidator | Coraza WAF - Incoming request validator |
| `CorazaWafAdminExtension` | AdminExtension | Admin UI and configuration management for Coraza WAF |

---

## Remote Tunnels

**Location**: `app/next/tunnel/`

Remote tunnels allow Otoroshi to communicate with services behind tunnels via WebSocket protocol.

**File**: `tunnel.scala`

| Plugin | Type | Description |
|--------|------|-------------|
| `TunnelPlugin` | NgBackendCall | Contacts remote services using tunnels |

### Supporting Components

- `TunnelAgent` - Manages tunnel connections to remote servers
- `TunnelManager` - Manages multiple tunnel instances and request routing
- `LeaderConnection` - Handles WebSocket connections to leader nodes
- `TunnelController` - REST API controller for tunnel endpoints

---

## Admin Extensions

**Location**: `app/next/extensions/`

Admin Extensions allow extending Otoroshi's administration UI and API with custom plugins.

### Framework Classes

**File**: `extension.scala`

| Class | Description |
|-------|-------------|
| `AdminExtension` | Base trait for all admin extensions |
| `AdminExtensions` | Manager for all registered admin extensions |
| `AdminExtensionRoute` | Base interface for extension routes |
| `AdminExtensionBackofficeAuthRoute` | Authenticated backoffice routes |
| `AdminExtensionBackofficePublicRoute` | Public backoffice routes |
| `AdminExtensionAdminApiRoute` | Admin API routes with ApiKey auth |
| `AdminExtensionPrivateAppAuthRoute` | Private app routes with user auth |
| `AdminExtensionWellKnownRoute` | .well-known extension endpoints |

### HTTP Listener Extension

**File**: `listeners.scala`

| Plugin | Type | Description |
|--------|------|-------------|
| `HttpListenerAdminExtension` | AdminExtension | Dynamic HTTP listener management with multi-protocol support (HTTP/1, HTTP/2, HTTP/3, H2C) and TLS |

### Example Extension

**File**: `example.scala`

| Plugin | Type | Description |
|--------|------|-------------|
| `FooAdminExtension` | AdminExtension | Complete example template for creating custom extensions |

---

## Kubernetes Plugins

**Location**: `app/plugins/jobs/kubernetes/`

### Job Plugins

| Plugin | File | Description |
|--------|------|-------------|
| `KubernetesIngressControllerJob` | `ingress.scala` | Enables Otoroshi to function as a Kubernetes Ingress Controller. Syncs Kubernetes Ingress resources to Otoroshi routes/service descriptors. |
| `KubernetesOtoroshiCRDsControllerJob` | `crds.scala` | Enables Otoroshi CRDs (Custom Resource Definitions) Controller for managing Otoroshi resources via Kubernetes CRDs. |
| `KubernetesToOtoroshiCertSyncJob` | `certs.scala` | Syncs TLS secrets from Kubernetes to Otoroshi certificates. |
| `OtoroshiToKubernetesCertSyncJob` | `certs.scala` | Syncs Otoroshi certificates to Kubernetes TLS secrets. |

### Webhook Plugins (RequestSink)

| Plugin | File | Description |
|--------|------|-------------|
| `KubernetesAdmissionWebhookCRDValidator` | `webhooks.scala` | Exposes a webhook to Kubernetes for handling CRD manifest validation (admission controller). |
| `KubernetesAdmissionWebhookSidecarInjector` | `webhooks.scala` | Kubernetes sidecar injector webhook for automatically injecting sidecars into pods. |

### New Engine Kubernetes Plugin

| Plugin | File | Description |
|--------|------|-------------|
| `KubernetesNamespaceScanBackend` | `next/plugins/kubernetes.scala` | Kubernetes service discovery - scans namespaces for backend targets. |

### Supporting Components

**Core Classes:**
- `KubernetesClient` - HTTP client for communicating with Kubernetes API
- `KubernetesConfig` - Configuration object for Kubernetes settings
- `KubernetesEntity` - Base trait for Kubernetes resources

**Entity Models** (in `entities.scala`):
- `KubernetesNamespace`
- `KubernetesService`
- `KubernetesConfigMap`
- `KubernetesValidatingWebhookConfiguration`
- `KubernetesMutatingWebhookConfiguration`
- `KubernetesOpenshiftDnsOperator`
- `KubernetesEndpoint`
- `KubernetesOtoroshiResource`
- `KubernetesIngressClass`
- `KubernetesIngress`
- `KubernetesPod`
- `KubernetesDeployment`
- `KubernetesCertSecret`
- `KubernetesSecret`

---

## Plugin Categories Summary

| Category | Count |
|----------|-------|
| Access Control & Authentication | 14 |
| JWT & Token Management | 6 |
| OAuth2 & OIDC | 12 |
| Client Certificates | 6 |
| Authorization & RBAC | 5 |
| Request/Response Headers | 17 |
| Cookies | 7 |
| Traffic Control & Throttling | 10 |
| Compression & Encoding | 2 |
| CORS & Protocol | 6 |
| Caching | 3 |
| Geolocation | 4 |
| IP & Network Security | 5 |
| Security Headers & HMAC | 6 |
| Data Transformation | 13 |
| Static Content & Files | 6 |
| Mock & Test Responses | 5 |
| Backend Routing & Discovery | 6 |
| Service Endpoints | 10 |
| GraphQL & gRPC | 4 |
| WebSocket | 9 |
| WebAssembly (WASM) | 11 |
| Tunnel & Protocol | 2 |
| Chaos Engineering | 1 |
| Canary & Traffic Management | 3 |
| Integrations | 6 |
| Vulnerability Protection | 3 |
| Miscellaneous | 17 |
| **Workflow Engine** | 5 plugins + 21 nodes + 24 functions + 60 operators |
| **GreenScore** | 3 |
| **Coraza WAF** | 3 |
| **Remote Tunnels** | 1 |
| **Admin Extensions** | 3 |
| Kubernetes | 7 |

**Total: ~210+ plugins** (plus ~100 workflow components)

---

*This document provides a reference for all available plugins. For detailed configuration options, refer to the official Otoroshi documentation or inspect the source files directly.*
