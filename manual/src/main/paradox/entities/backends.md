# Backends

A backend represent a list of server to target in a route and its client settings, load balancing, etc.

The backends can be define directly on the route designer or on their dedicated page in order to be reusable.

## UI page

You can find all backends [here](http://otoroshi.oto.tools:8080/bo/dashboard/backend)

## Global Properties

* `Targets root path`: the path to add to each request sent to the downstream service 
* `Full path rewrite`: When enabled, the incoming uri will be forwarded to the downstream service
````sh
if enabled: $scheme://$host$root$uri
else: $scheme://$host$root
````

## Targets

The list of target that Otoroshi will proxy and expose through the subdomain defined before. Otoroshi will do round-robin load balancing between all those targets with circuit breaker mecanism to avoid cascading failures.

* `id`: unique id of the target
* `Hostname`: the hostname of the target without scheme
* `Port`:  the port of the target
* `TLS`: call the target via https
* `Weight`: the weight of the target. This valus is used by the load balancing strategy to dispatch the traffic between all targets
* `Predicate`: ???
* `Protocol`:  protocol used to call the target, can be only equals to HTTP/1.0, HTTP/1.1, HTTP/2.0 or HTTP/3.0
* `IP address`: the ip address of the target
* `TLS Settings`:
    * `Enabled`: enable this section
    * `TLS loose`: if enabled, will block all untrustful ssl configs
    * `TrustAll`: allows any server certificates even the self-signed ones
    * `Client certificates`: list of client certificates used to communicate with the downstream service
    * `Trusted certificates`: list of trusted certificates received from the downstream service


## Heatlh check

* `Enabled`: if enabled, the health check URL will be called at regular intervals
* `URL`: the URL to call to run the health check

## Load balancing

* `Type`: the load balancing algorithm used

## Client settings

* `backoff factor`:  specify the factor to multiply the delay for each retry (default value 2)
* `retries`: specify how many times the client will retry to fetch the result of the request after an error before giving up. (default value 1)
* `max errors`: specify how many errors can pass before opening the circuit breaker (default value 20)
* `global timeout`: specify how long the global call (with retries) should last at most in milliseconds. (default value 30000)
* `connection timeout`: specify how long each connection should last at most in milliseconds. (default value 10000)
* `idle timeout`: specify how long each connection can stay in idle state at most in milliseconds (default value 60000)
* `call timeout`: Specify how long each call should last at most in milliseconds. (default value 30000)
* `call and stream timeout`: specify how long each call should last at most in milliseconds for handling the request and streaming the response. (default value 120000)
* `initial delay`: ??? (default value 50)
* `sample interval`: specify the delay between two retries. Each retry, the delay is multiplied by the backoff factor (default value 2000)
* `cache connection`: ??? (default value false)
* `cache connection queue size`: ??? (default value 2048)
* `custom timeouts` (list): 
    * `Path`: the path on which the timeout will be active
    * `Client connection timeout`: specify how long each connection should last at most in milliseconds.
    * `Client idle timeout`: specify how long each connection can stay in idle state at most in milliseconds.
    * `Client call and stream timeout`: specify how long each call should last at most in milliseconds for handling the     request and streaming the response.
    * `Call timeout`: Specify how long each call should last at most in milliseconds.
    * `Client global timeout`: specify how long the global call (with retries) should last at most in milliseconds.

## Proxy

* `host`: host of proxy behind the identify provider
* `port`: port of proxy behind the identify provider
* `protocol`: protocol of proxy behind the identify provider
* `principal`: user of proxy 
* `password`: password of proxy
* `NTLM domain`:  ???
* `encoding`: ???
* `non proxy hosts`: ???