# Service descriptors

Services or service descriptor, let you declare how to proxy a call from a domain name to another domain name (or multiple domain names). 

@@@ div { .centered-img }
<img src="../imgs/models-service.png" />
@@@

Let’s say you have an API exposed on http://192.168.0.42 and I want to expose it on https://my.api.foo. Otoroshi will proxy all calls to https://my.api.foo and forward them to http://192.168.0.42. While doing that, it will also log everyhting, control accesses, etc.


* `Id`: a unique random string to identify your service
* `Groups`: each service descriptor is attached to a group. A group can have one or more services. Each API key is linked to a group and allow access to every service in the group.
* `Create a new group`: you can create a new group to host this descriptor
* `Create dedicated group`: you can create a new group with an auto generated name to host this descriptor
* `Name`: the name of your service. Only for debug and human readability purposes.
* `Description`: the description of your service. Only for debug and human readability purposes.
* `Service enabled`: activate or deactivate your service. Once disabled, users will get an error page saying the service does not exist.
* `Read only mode`: authorize only GET, HEAD, OPTIONS calls on this service
* `Maintenance mode`: display a maintainance page when a user try to use the service
* `Construction mode`: display a construction page when a user try to use the service
* `Log analytics`: Log analytics events for this service on the servers
* `Use new http client`: will use Akka Http Client for every request
* `Detect apikey asap`: If the service is public and you provide an apikey, otoroshi will detect it and validate it. Of course this setting may impact performances because of useless apikey lookups.
* `Send Otoroshi headers back`: when enabled, Otoroshi will send headers to consumer like request id, client latency, overhead, etc ...
* `Override Host header`: when enabled, Otoroshi will automatically set the Host header to corresponding target host
* `Send X-Forwarded-* headers`: when enabled, Otoroshi will send X-Forwarded-* headers to target
* `Force HTTPS`: will force redirection to https:// if not present
* `Allow HTTP/1.0 requests`: will return an error on HTTP/1.0 request
* `Use new WebSocket client`: will use the new websocket client for every websocket request
* `TCP/UDP tunneling`: with this setting enabled, otoroshi will not proxy http requests anymore but instead will create a secured tunnel between a cli on your machine and otoroshi to proxy any tcp connection with all otoroshi security features enabled

### Service exposition settings

* `Exposed domain`: the domain used to expose your service. Should follow pattern: (http|https)://subdomain?.env?.domain.tld?/root? or regex (http|https):\/\/(.*?)\.?(.*?)\.?(.*?)\.?(.*)\/?(.*)
* `Legacy domain`: use 'domain', 'subdomain', 'env' and 'matchingRoot' for routing in addition to hosts, or just use hosts.
* `Strip path`: when matching, strip the matching prefix from the upstream request URL. Defaults to true
* `Issue Let's Encrypt cert.`: automatically issue and renew let's encrypt certificate based on domain name. Only if Let's Encrypt enabled in global config.
* `Issue certificate`: automatically issue and renew a certificate based on domain name
* `Possible hostnames`: all the possible hostnames for your service
* `Possible matching paths`: all the possible matching paths for your service

### Redirection

* `Redirection enabled`: enabled the redirection. If enabled, a call to that service will redirect to the chosen URL
* `Http redirection code`: type of redirection used
* `Redirect to`: URL used to redirect user when the service is called

### Service targets

* `Redirect to local`: if you work locally with Otoroshi, you may want to use that feature to redirect one specific service to a local host. For example, you can relocate https://foo.preprod.bar.com to http://localhost:8080 to make some tests
* `Load balancing`: the load balancing algorithm used
* `Targets`: the list of target that Otoroshi will proxy and expose through the subdomain defined before. Otoroshi will do round-robin load balancing between all those targets with circuit breaker mecanism to avoid cascading failures
* `Targets root`: Otoroshi will append this root to any target choosen. If the specified root is '/api/foo', then a request to https://yyyyyyy/bar will actually hit https://xxxxxxxxx/api/foo/bar

### URL Patterns

* `Make service a 'public ui'`: add a default pattern as public routes
* `Make service a 'private api'`: add a default pattern as private routes
* `Public patterns`: by default, every services are private only and you'll need an API key to access it. However, if you want to expose a public UI, you can define one or more public patterns (regex) to allow access to anybody. For example if you want to allow anybody on any URL, just use '/.*'
* `Private patterns`: if you define a public pattern that is a little bit too much, you can make some of public URL private again

### Restrictions

* `Enabled`: enable restrictions
* `Allow last`: Otoroshi will test forbidden and notFound paths before testing allowed paths
* `Allowed`: allowed paths
* `Forbidden`: forbidden paths
* `Not Found`: not found paths

### Otoroshi exchange protocol

* `Enabled`: when enabled, Otoroshi will try to exchange headers with downstream service to ensure no one else can use the service from outside.
* `Send challenge`: when disbaled, Otoroshi will not check if target service respond with sent random value.
* `Send info. token`: when enabled, Otoroshi add an additional header containing current call informations
* `Challenge token version`: version the otoroshi exchange protocol challenge. This option will be set to V2 in a near future.
* `Info. token version`: version the otoroshi exchange protocol info token. This option will be set to Latest in a near future.
* `Tokens TTL`: the number of seconds for tokens (state and info) lifes
* `State token header name`: the name of the header containing the state token. If not specified, the value will be taken from the configuration (otoroshi.headers.comm.state)
* `State token response header name`: the name of the header containing the state response token. If not specified, the value will be taken from the configuration (otoroshi.headers.comm.stateresp)
* `Info token header name`: the name of the header containing the info token. If not specified, the value will be taken from the configuration (otoroshi.headers.comm.claim)
* `Excluded patterns`: by default, when security is enabled, everything is secured. But sometimes you need to exlude something, so just add regex to matching path you want to exlude.
* `Use same algo.`: when enabled, all JWT token in this section will use the same signing algorithm. If `use same algo.` is disabled, three more options will be displayed to select an algorithm for each step of the calls :
    * Otoroshi to backend
    * Backend to otoroshi
    * Info. token

* `Algo.`: What kind of algorithm you want to use to verify/sign your JWT token with
* `SHA Size`: Word size for the SHA-2 hash function used
* `Hmac secret`: used to verify the token
* `Base64 encoded secret`: if enabled, the extracted token will be base64 decoded before it is verifier

### Authentication

* `Enforce user authentication`: when enabled, user will be allowed to use the service (UI) only if they are registered users of the chosen authentication module.
* `Auth. config`: authentication module used to protect the service
* `Create a new auth config.`: navigate to the creation of authentication module page
* `all auth config.`: navigate to the authentication pages

* `Excluded patterns`: by default, when security is enabled, everything is secured. But sometimes you need to exlude something, so just add regex to matching path you want to exlude.
* `Strict mode`: strict mode enabled

### Api keys constraints

* `From basic auth.`: you can pass the api key in Authorization header (ie. from 'Authorization: Basic xxx' header)
* `Allow client id only usage`: you can pass the api key using client id only (ie. from Otoroshi-Token header)
* `From custom headers`: you can pass the api key using custom headers (ie. Otoroshi-Client-Id and Otoroshi-Client-Secret headers)
* `From JWT token`: you can pass the api key using a JWT token (ie. from 'Authorization: Bearer xxx' header)

#### Basic auth. Api Key

* `Custom header name`: the name of the header to get Authorization
* `Custom query param name`: the name of the query param to get Authorization

#### Client ID only Api Key

* `Custom header name`: the name of the header to get the client id
* `Custom query param name`: the name of the query param to get the client id

#### Custom headers Api Key

* `Custom client id header name`: the name of the header to get the client id
* `Custom client secret header name`: the name of the header to get the client secret

#### JWT Token Api Key

* `Secret signed`: JWT can be signed by apikey secret using HMAC algo.
* `Keypair signed`: JWT can be signed by an otoroshi managed keypair using RSA/EC algo.
* `Include Http request attrs.`: if enabled, you have to put the following fields in the JWT token corresponding to the current http call (httpPath, httpVerb, httpHost)
* `Max accepted token lifetime`: the maximum number of second accepted as token lifespan
* `Custom header name`: the name of the header to get the jwt token
* `Custom query param name`: the name of the query param to get the jwt token
* `Custom cookie name`: the name of the cookie to get the jwt token

### Routing constraints

* `All Tags in` : have all of the following tags
* `No Tags in` : not have one of the following tags
* `One Tag in` : have at least one of the following tags
* `All Meta. in` : have all of the following metadata entries
* `No Meta. in` : not have one of the following metadata entries
* `One Meta. in` : have at least one of the following metadata entries
* `One Meta key in` : have at least one of the following key in metadata
* `All Meta key in` : have all of the following keys in metadata
* `No Meta key in` : not have one of the following keys in metadata

### CORS support

* `Enabled`: if enabled, CORS header will be check for each incoming request
* `Allow credentials`: if enabled, the credentials will be sent. Credentials are cookies, authorization headers, or TLS client certificates.
* `Allow origin`: if enabled, it will indicates whether the response can be shared with requesting code from the given
* `Max age`: response header indicates how long the results of a preflight request (that is the information contained in the Access-Control-Allow-Methods and Access-Control-Allow-Headers headers) can be cached.
* `Expose headers`: response header allows a server to indicate which response headers should be made available to scripts running in the browser, in response to a cross-origin request.
* `Allow headers`: response header is used in response to a preflight request which includes the Access-Control-Request-Headers to indicate which HTTP headers can be used during the actual request.
* `Allow methods`: response header specifies one or more methods allowed when accessing a resource in response to a preflight request.
* `Excluded patterns`: by default, when cors is enabled, everything has cors. But sometimes you need to exlude something, so just add regex to matching path you want to exlude.

#### Related documentations

* @link[Access-Control-Allow-Credentials](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Allow-Credentials)
* @link[Access-Control-Allow-Origin](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Allow-Origin)
* @link[Access-Control-Max-Age](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Max-Age)
* @link[Access-Control-Allow-Methods](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Allow-Methods)
* @link[Access-Control-Allow-Headers](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Access-Control-Allow-Headers)

### JWT tokens verification

* `Verifiers`: list of selected verifiers to apply on the service
* `Enabled`: if enabled, Otoroshi will enabled each verifier of the previous list
* `Excluded patterns`: list of routes where the verifiers will not be apply

### Pre Routing

This part has been deprecated and moved to the plugin section.

### Access validation
This part has been deprecated and moved to the plugin section.

### Gzip support

* `Mimetypes allowed list`: gzip only the files that are matching to a format in the list
* `Mimetypes blocklist`: will not gzip files matching to a format in the list. A possible way is to allowed all format by default by setting a `*` on the `Mimetypes allowed list` and to add the unwanted format in this list.
* `Compression level`: the compression level where 9 gives us maximum compression but at the slowest speed. The default compression level is 5 and is a good compromise between speed and compression ratio.
* `Buffer size`: chunking up a stream of bytes into limited size
* `Chunk threshold`: if the content type of a request reached over the threshold, the response will be chunked
* `Excluded patterns`: by default, when gzip is enabled, everything has gzip. But sometimes you need to exlude something, so just add regex to matching path you want to exlude.

### Client settings

* `Use circuit breaker`: use a circuit breaker to avoid cascading failure when calling chains of services. Highly recommended !
* `Cache connections`: use a cache at host connection level to avoid reconnection time
* `Client attempts`: specify how many times the client will retry to fetch the result of the request after an error before giving up.
* `Client call timeout`: specify how long each call should last at most in milliseconds.
* `Client call and stream timeout`: specify how long each call should last at most in milliseconds for handling the request and streaming the response.
* `Client connection timeout`: specify how long each connection should last at most in milliseconds.
* `Client idle timeout`: specify how long each connection can stay in idle state at most in milliseconds.
* `Client global timeout`: specify how long the global call (with retries) should last at most in milliseconds.
* `C.breaker max errors`: specify how many errors can pass before opening the circuit breaker
* `C.breaker retry delay`: specify the delay between two retries. Each retry, the delay is multiplied by the backoff factor
* `C.breaker backoff factor`: specify the factor to multiply the delay for each retry
* `C.breaker window`: specify the sliding window time for the circuit breaker in milliseconds, after this time, error count will be reseted

#### Custom timeout settings (list)

* `Path`: the path on which the timeout will be active
* `Client connection timeout`: specify how long each connection should last at most in milliseconds.
* `Client idle timeout`: specify how long each connection can stay in idle state at most in milliseconds.
* `Client call and stream timeout`: specify how long each call should last at most in milliseconds for handling the request and streaming the response.
* `Call timeout`: Specify how long each call should last at most in milliseconds.
* `Client global timeout`: specify how long the global call (with retries) should last at most in milliseconds.

#### Proxy settings

* `Proxy host`: host of proxy behind the identify provider
* `Proxy port`: port of proxy behind the identify provider
* `Proxy principal`: user of proxy 
* `Proxy password`: password of proxy

### HTTP Headers

* `Additional Headers In`: specify headers that will be added to each client request (from Otoroshi to target). Useful to add authentication.
* `Additional Headers Out`: specify headers that will be added to each client response (from Otoroshi to client).
* `Missing only Headers In`: specify headers that will be added to each client request (from Otoroshi to target) if not in the original request.
* `Missing only Headers Out`: specify headers that will be added to each client response (from Otoroshi to client) if not in the original response.
* `Remove incoming headers`: remove headers in the client request (from client to Otoroshi).
* `Remove outgoing headers`: remove headers in the client response (from Otoroshi to client).
* `Security headers`:
* `Utility headers`:
* `Matching Headers`: specify headers that MUST be present on client request to route it (pre routing). Useful to implement versioning.
* `Headers verification`: verify that some headers has a specific value (post routing)

### Additional settings 

* `OpenAPI`: specify an open API descriptor. Useful to display the documentation
* `Tags`: specify tags for the service
* `Metadata`: specify metadata for the service. Useful for analytics
* `IP allowed list`: IP address that can access the service
* `IP blocklist`: IP address that cannot access the service

### Canary mode

* `Enabled`: Canary mode enabled
* `Traffic split`: Ratio of traffic that will be sent to canary targets. For instance, if traffic is at 0.2, for 10 request, 2 request will go on canary targets and 8 will go on regular targets.
* `Targets`: The list of target that Otoroshi will proxy and expose through the subdomain defined before. Otoroshi will do round-robin load balancing between all those targets with circuit breaker mecanism to avoid cascading failures
  * `Target`:
  * `Targets root`: Otoroshi will append this root to any target choosen. If the specified root is '/api/foo', then a request to https://yyyyyyy/bar will actually hit https://xxxxxxxxx/api/foo/bar
* `Campaign stats`:
* `Use canary targets as standard targets`:

### Healthcheck settings

* `HealthCheck enabled`: to help failing fast, you can activate healthcheck on a specific URL.
* `HealthCheck url`: the URL to check. Should return an HTTP 200 response. You can also respond with an 'Opun-Health-Check-Logic-Test-Result' header set to the value of the 'Opun-Health-Check-Logic-Test' request header + 42. to make the healthcheck complete.

### Fault injection

* `User facing app.`: if service is set as user facing, Snow Monkey can be configured to not being allowed to create outage on them.
* `Chaos enabled`: activate or deactivate chaos setting on this service descriptor.

### Custom errors template

* `40x template`: html template displayed when 40x error occurred
* `50x template`: html template displayed when 50x error occurred
* `Build mode template`:  html template displayed when the build mode is enabled
* `Maintenance mode template`: html template displayed when the maintenance mode is enabled
* `Custom messages`: override error message one by one

### Request transformation

This part has been deprecated and moved to the plugin section.

### Plugins

* `Plugins`:
  
    * `Inject default config`: injects, if present, the default configuration of a selected plugin in the configuration object
    * `Documentation`: link to the documentation website of the plugin
    * `show/hide config. panel`: shows and hides the plugin panel which contains the plugin description and configuration
* `Excluded patterns`: by default, when plugins are enabled, everything pass in. But sometimes you need to exclude something, so just add regex to matching path you want to exlude.
* `Configuration`: the configuration of each enabled plugin, split by names and grouped in the same configuration object.