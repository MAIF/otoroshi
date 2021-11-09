# Global config

The global config, named `Danger zone` in Otoroshi, is the place to configure Otoroshi globally. 

> Warning: In this page, the configuration is really sensitive and affect the global behaviour of Otoroshi.


### Misc. Settings

| Key | Description |
|---|---|
| Maintenance mode | It pass every single service in maintenance mode. If an user calls a service, the maintenance page will be displayed |
| No OAuth login for BackOffice | Forces admins to login only with user/password or user/password/u2F device | 
| API Read Only | Freeze the Otoroshi datastore in read only mode. Only people with access to the actual underlying datastore will be able to disable this.|
| Auto link default | When no group is specified on a service, it will be assigned to default one |
| Use circuit breakers | Use circuit breaker on all services |
| Use new http client as the default Http client |All http calls will use the new http client client by default |
| Enable live metrics | Enable live metrics in the Otoroshi cluster. Performs a lot of writes in the datastore |
| Digitus medius | Use middle finger emoji (🖕) as a response character for endless HTTP responses. |
| Limit conc. req. | Limit the number of concurrent request processed by Otoroshi to a certain amount. Highly recommended for resilience.|
| Use X-Forwarded-* headers for routing | When evaluating routing of a request X-Forwarded-* headers will be used if presents |
| Max conc. req. | Maximum number of concurrent request processed by otoroshi. |
| Max HTTP/1.0 resp. size | Maximum size of an HTTP/1.0 response in bytes. After this limit, response will be cut and sent as is. The best value here should satisfy (maxConcurrentRequests * maxHttp10ResponseSize) < process.memory for worst case scenario. |
| Max local events | Maximum number of events stored. |
| Lines | *deprecated* |

### IP address filtering settings

| Key | Description |
|---|---|
| IP allowed list | Only IP addresses that will be able to access Otoroshi exposed services |
| IP blocklist | IP addresses that will be refused to access Otoroshi exposed services |
| Endless HTTP Responses | IP addresses for which each request will return around 128 Gb of 0s |

### Quotas settings
| Key | Description |
|---|---|
| Global throttling|  The max. number of requests allowed per seconds globally on Otoroshi |
| Throttling per IP| The max. number of requests allowed per seconds per IP address globally on Otoroshi |
### Analytics: Elastic dashboard datasource (read)
| Key | Description |
|---|---|
| Cluster URI | Elastic cluster URI |
| Index |Elastic index  |
| Type | Event type (not needed for elasticsearch above 6.x)|
| User | Elastic User (optional)|
| Password | Elastic password (optional)|
| Version | Elastic version (optional, if none provided it will be fetched from cluster) |
| Apply template | Automatically apply index template|
| Check Connection | Button to test the configuration. It will displayed a modal with checked point, and if the case of it's successfull, it will displayed the found version of the Elasticsearch and the index used |
| Manually apply index template | try to put the elasticsearch template by calling the api of elasticsearch |
| Show index template | try to retrieve the current index template presents in elasticsearch |
| Client side temporal indexes handling | When enabled, Otoroshi will manage the creation of indexes. When it's disabled, Otoroshi will push in the same index |
| One index per | When the previous field is enabled, you can choose the interval of time between the creation of a new index in elasticsearch  |
| Custom TLS Settings | Enable the TLS configuration for the communication with Elasticsearch |
| TLS loose | if enabled, will block all untrustful ssl configs |
| TrustAll | allows any server certificates even the self-signed ones |
| Client certificates | list of client certificates used to communicate with elasticsearch |
| Trusted certificates | list of trusted certificates received from elasticsearch |
### Statsd settings
| Key | Description |
|---|---|
| Datadog agent | The StatsD agent is a Datadog agent |
| StatsD agent host | The host on which StatsD agent is listening |
| StatsD agent port | The port on which StatsD agent is listening (default is 8125) |
### Backoffice auth. settings
| Key | Description |
|---|---|
| Backoffice auth. config | the authentication module used in front of Otoroshi. It will be used to connect to Otoroshi on the login page |
### Let's encrypt settings
| Key | Description |
|---|---|
| Enabled | when enabled, Otoroshi will have the possiblity to sign certificate from let's encrypt notably in the SSL/TSL Certificates page | 
| Server URL | ACME endpoint of let's encrypt | 
| Email addresses | (optional) list of addresses used to order the certificates | 
| Contact URLs | (optional) list of addresses used to order the certificates | 
| Public Key | used to ask a certificate to let's encrypt, generated by Otoroshi | 
| Private Key | used to ask a certificate to let's encrypt, generated by Otoroshi | 
### CleverCloud settings

Once configured, you can register one clever cloud app of your organization directly as an Otoroshi service.

| Key | Description |
|---|---|
| CleverCloud consumer key | consumer key of your clever cloud OAuth 1.0 app |
| CleverCloud consumer secret|consumer secret of your clever cloud OAuth 1.0 app |
| OAuth Token| oauth token of your clever cloud OAuth 1.0 app |
| OAuth Secret| oauth token secret of your clever cloud OAuth 1.0 app | 
| CleverCloud orga. Id| id of your clever cloud organization |
###  Global scripts

Global scripts will be deprecated soon, please use global plugins instead (see the next section)!

###  Global plugins

| Key | Description |
|---|---|
| Enabled | enabled all global plugins |
| Plugins | list of added plugins to your instance |
| Plugin configuration | each added plugin have a configuration that you can override from this field |

###  Proxies

In this section, you can add a list of proxies for :
* Proxy for alert emails (mailgun)
* Proxy for alert webhooks
* Proxy for Clever-Cloud API access
* Proxy for services access
* Proxy for auth. access (OAuth, OIDC)
* Proxy for client validators
* Proxy for JWKS access
* Proxy for elastic access

Each proxy has the following fields 

| Key | Description |
|---|---|
| Proxy host | host of proxy |
| Proxy port | port of proxy |
| Proxy principal | user of proxy |
| Proxy password | password of proxy |
| Non proxy host | IP address that can access the service|

###  Quotas alerting settings
| Key | Description |
|---|---|
| Enable quotas exceeding alerts | When apikey quotas is almost exceeded, an alert will be sent | 
| Daily quotas threshold | The percentage of daily calls before sending alerts |
| Monthly quotas threshold| The percentage of monthly calls before sending alerts |
###  User-Agent extraction settings
| Key | Description |
|---|---|
| User-Agent extraction | Allow user-agent details extraction. Can have impact on consumed memory. | 

###  Geolocation extraction settings
Extract an geolocation for each call to Otoroshi.

###  Tls Settings
| Key | Description |
|---|---|
|Use random cert. | Use the first available cert none matches the current domain |
| Default domain |When the SNI domain cannot be found, this one will be used to find the matching certificate | 
| Trust JDK CAs (server) | Trust JDK CAs. The CAs from the JDK CA bundle will be proposed in the certificate request when performing TLS handshake| 
| Trust JDK CAs (trust) |Trust JDK CAs. The CAs from the JDK CA bundle will be used as trusted CAs when calling HTTPS resources | 
| Trusted CAs (server) | Select the trusted CAs you want for TLS terminaison. Those CAs only will be proposed in the certificate request when performing TLS handshake| 
###  Auto Generate Certificates
| Key | Description |
|---|---|
| Enabled | Generate certificates on the fly when they not exist|
| Reply Nicely | When not allowed domain name, accept connection and display a nice error message| 
| CA | certificate CA used to generate missing certificate |
| Allowed domains | Allowed domains|
| Not allowed domains | Allowed domains|
###  Global metadata
| Key | Description |
|---|---|
| Tags | tags attached to the global config |
| Metadata | metadata attached to the global config |

### Actions at the bottom of the page

| Key | Description |
|---|---|
| Recover from a full export file | Load global configuration from a previous export |
| Full export | Export with all created entities |
| Full export (ndjson) | Export your full state of database to ndjson format |
| JSON | Get the global config at JSON format | 
| YAML | Get the global config at YAML format | 
| Enable Panic Mode | Log out all users from UI and prevent any changes to the database by setting the admin Otoroshi api to read-only. The only way to exit of this mode is to disable this mode directly in the database. | 