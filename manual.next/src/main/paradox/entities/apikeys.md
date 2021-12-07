# Apikeys

An API key is linked to one or more service group and service descriptor to allow you to access any service descriptor linked or contained in one of the linked service group. You can, of course, create multiple API key for given service groups/service descriptors.

You can found a concrete example @ref:[here](../how-to-s/secure-with-apikey.md)

* `ApiKey Id`: the id is a unique random key that will represent this API key
* `ApiKey Secret`: the secret is a random key used to validate the API key
* `ApiKey Name`: a name for the API key, used for debug purposes
* `ApiKey description`: a useful description for this apikey
* `Valid until`: auto disable apikey after this date
* `Enabled`: if the API key is disabled, then any call using this API key will fail
* `Read only`: if the API key is in read only mode, every request done with this api key will only work for GET, HEAD, OPTIONS verbs
* `Allow pass by clientid only`: here you allow client to only pass client id in a specific header in order to grant access to the underlying api
* `Constrained services only`: this apikey can only be used on services using apikey routing constraints
* `Authorized on`: the groups/services linked to this api key

### Metadata and tags

* `Tags`: tags attached to the api key
* `Metadata`: metadata attached to the api key

### Automatic secret rotation

API can handle automatic secret rotation by themselves. When enabled, the rotation changes the secret every `Rotation every` duration. During the `Grace period` both secret will be usable.
 
* `Enabled`: enabled automatic apikey secret rotation
* `Rotation every`: rotate secrets every
* `Grace period`: period when both secrets can be used
* `Next client secret`: display the next generated client secret

### Restrictions

* `Enabled`: enable restrictions
* `Allow last`: Otoroshi will test forbidden and notFound paths before testing allowed paths
* `Allowed`: allowed paths
* `Forbidden`: forbidden paths
* `Not Found`: not found paths

### Call examples

* `Curl Command`: simple request with the api key passed by header
* `Basic Auth. Header`: authorization Header with the api key as base64 encoded format
* `Curl Command with Basic Auth. Header`: simple request with api key passed in the Authorization header as base64 format

### Quotas

* `Throttling quota`: the authorized number of calls per second
* `Daily quota`: the authorized number of calls per day
* `Monthly quota`: the authorized number of calls per month

@@@ warning

Daily and monthly quotas are based on the following rules :

* daily quota is computed between 00h00:00.000 and 23h59:59.999 of the current day
* monthly qutoas is computed between the first day of the month at 00h00:00.000 and the last day of the month at 23h59:59.999
@@@

### Quotas consumption

* `Consumed daily calls`: the number of calls consumed today
* `Remaining daily calls`: the remaining number of calls for today
* `Consumed monthly calls`: the number of calls consumed this month
* `Remaining monthly calls`: the remaining number of calls for this month

