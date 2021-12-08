# TCP services

TCP service are special kind of otoroshi services meant to proxy pure TCP connections (ssh, database, http, etc)

## Global information

* `Id`: generated unique identifier
* `TCP service name`: the name of your TCP service
* `Enabled`: enable and disable the service
* `TCP service port`: the listening port
* `TCP service interface`: network interface listen by the service
* `Tags`: list of tags associated to the service
* `Metadata`: list of metadata associated to the service

## TLS

this section controls the TLS exposition of the service

* `TLS mode`
    * `Disabled`: no TLS
    * `PassThrough`: as the target exposes TLS, the call will pass through otoroshi and use target TLS
    * `Enabled`: the service will be exposed using TLS and will chose certificate based on SNI
* `Client Auth.`
    * `None` no mTLS needed to pass
    * `Want` pass with or without mTLS
    * `Need` need mTLS to pass

## Server Name Indication (SNI)

this section control how SNI should be treated

* `SNI routing enabled`: if enabled, the server will use the SNI hostname to determine which certificate to present to the client
* `Forward to target if no SNI match`: if enabled, a call without any SNI match will be forward to the target
* `Target host`: host of the target called if no SNI
* `Target ip address`: ip of the target called if no SNI
* `Target port`: port of the target called if no SNI
* `TLS call`: encrypt the communication with TLS

## Rules

for any listening TCP proxy, it is possible to route to multiple targets based on SNI or extracted http host (if proxying http)

* `Matching domain name`: regex used to filter the list of domains where the rule will be applied
* `Target host`: host of the target
* `Target ip address`: ip of the target
* `Target port`: port of the target
* `TLS call`: enable this flag if the target is exposed using TLS
