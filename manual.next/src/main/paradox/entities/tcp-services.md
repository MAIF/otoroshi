# TCP services

## Global information

* `Id`: generated unique identifier
* `TCP service name`: the name of your TCP service
* `Enabled`: enable and disable the service
* `TCP service port`: the listening port
* `TCP service interface`: network interface listen by the service
* `Tags`: list of tags associated to the service
* `Metadata`: list of metadata associated to the service

## TLS

* `TLS mode`: disabled / PassThrough / Enabled
* `Client Auth.`: None / Want / Need

## Server Name Indication (SNI)

* `SNI routing enabled`: if enabled, the server will use the SNI hostname to determine which certificate to present to the client
* `Forward to target if no SNI match`: if enabled, a call without any SNI match will be forward to the target
* `Target host`: host of the target
* `Target ip address`: ip of the target
* `Target port`: port of the target
* `TLS call`: encrypt the communication with TLS

## Rules

* `Matching domain name`: regex used to filter the list of domains where the rule will be applied
* `Target host`: host of the target
* `Target ip address`: ip of the target
* `Target port`: port of the target
* `TLS call`: encrypt the communication with TLS
