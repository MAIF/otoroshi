package controllers

import env.Env
import play.api.Logger
import play.api.libs.json._
import play.api.mvc._

object Implicits {
  implicit class EnhancedJsValue(val value: JsValue) extends AnyVal {
    def ~~>(description: String): JsValue =
      value.as[JsObject] ++ Json.obj(
        "description" -> description
      )
  }
}

class SwaggerController(cc: ControllerComponents)(implicit env: Env) extends AbstractController(cc) {

  import Implicits._

  implicit lazy val ec = env.otoroshiExecutionContext

  lazy val logger = Logger("otoroshi-swagger-controller")

  def swagger = Action { req =>
    Ok(Json.prettyPrint(swaggerDescriptor())).as("application/json").withHeaders("Access-Control-Allow-Origin" -> "*")
  }

  def swaggerUi = Action { req =>
    Ok(views.html.otoroshi.documentationframe(s"${env.exposedRootScheme}://${env.backOfficeHost}/api/swagger.json"))
  }

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // Types definitions

  def SimpleObjectType =
    Json.obj("type" -> "object",
             //"required"             -> Json.arr(),
             "example"              -> Json.obj("key"  -> "value"),
             "additionalProperties" -> Json.obj("type" -> "string"))
  def SimpleStringType   = Json.obj("type" -> "string", "example"  -> "a string value")
  def SimpleDoubleType   = Json.obj("type" -> "integer", "format"  -> "double", "example" -> 42.2)
  def OptionalStringType = Json.obj("type" -> "string", "required" -> false, "example" -> "a string value")
  def SimpleBooleanType  = Json.obj("type" -> "boolean", "example" -> true)
  def SimpleDateType     = Json.obj("type" -> "string", "format"   -> "date", "example" -> "2017-07-21")
  def SimpleDateTimeType = Json.obj("type" -> "string", "format"   -> "date-time", "example" -> "2017-07-21T17:32:28Z")
  def SimpleTimeType     = Json.obj("type" -> "string", "format"   -> "time", "example" -> "17:32:28.000")
  def SimpleLongType     = Json.obj("type" -> "integer", "format"  -> "int64", "example" -> 123)
  def SimpleIntType      = Json.obj("type" -> "integer", "format"  -> "int32", "example" -> 123123)
  def SimpleHostType     = Json.obj("type" -> "string", "format"   -> "hostname", "example" -> "www.google.com")
  def SimpleIpv4Type     = Json.obj("type" -> "string", "format"   -> "ipv4", "example" -> "192.192.192.192")
  def SimpleUriType      = Json.obj("type" -> "string", "format"   -> "uri", "example" -> "http://www.google.com")
  def SimpleEmailType    = Json.obj("type" -> "string", "format"   -> "email", "example" -> "admin@otoroshi.io")
  def SimpleUuidType =
    Json.obj("type" -> "string", "format" -> "uuid", "example" -> "110e8400-e29b-11d4-a716-446655440000")
  def Ref(name: String): JsObject = Json.obj("$ref" -> s"#/definitions/$name")
  def ArrayOf(ref: JsValue) = Json.obj(
    "type"  -> "array",
    "items" -> ref
  )
  def OneOf(refs: JsValue*) = Json.obj(
    "oneOf" -> JsArray(refs.toSeq)
  )

  def RequestBody(typ: JsValue) = Json.obj(
    "required" -> true,
    "content" -> Json.obj(
      "application/json" -> Json.obj(
        "schema" -> typ
      )
    )
  )

  def FormBody(typ: JsValue) = Json.obj(
    "required" -> true,
    "content" -> Json.obj(
      "application/x-www-form-urlencoded" -> Json.obj(
        "schema" -> typ
      )
    )
  )

  def PathParam(name: String, desc: String) = Json.obj(
    "in"          -> "path",
    "name"        -> name,
    "required"    -> true,
    "type"        -> "string",
    "description" -> desc
  )

  def BodyParam(desc: String, typ: JsValue) = Json.obj(
    "in"          -> "body",
    "name"        -> "body",
    "required"    -> true,
    "schema"      -> typ,
    "description" -> desc
  )

  def GoodResponse(ref: JsValue) = Json.obj(
    "description" -> "Successful operation",
    "schema"      -> ref
  )

  def Tag(name: String, description: String) = Json.obj(
    "name"        -> name,
    "description" -> description
  )

  def Operation(
      summary: String,
      tag: String,
      description: String = "",
      operationId: String = "",
      produces: JsArray = Json.arr("application/json"),
      parameters: JsArray = Json.arr(),
      goodCode: String = "200",
      goodResponse: JsObject
  ): JsValue =
    Json.obj(
      "deprecated"  -> false,
      "tags"        -> Json.arr(tag),
      "summary"     -> summary,
      "description" -> description,
      "operationId" -> operationId,
      "produces"    -> produces,
      "parameters"  -> parameters,
      "responses" -> Json.obj(
        "401" -> Json.obj(
          "description" -> "You have to provide an Api Key. Api Key can be passed with 'Otoroshi-Client-Id' and 'Otoroshi-Client-Secret' headers, or use basic http authentication"
        ),
        "400" -> Json.obj(
          "description" -> "Bad resource format. Take another look to the swagger, or open an issue :)"
        ),
        "404" -> Json.obj(
          "description" -> "Resource not found or does not exist"
        ),
        goodCode -> goodResponse
      ),
      "security" -> Json.arr(
        Json.obj(
          "otoroshi_auth" -> Json.arr()
        )
      )
    )

  def NoAuthOperation(
      summary: String,
      tag: String,
      description: String = "",
      operationId: String = "",
      produces: JsArray = Json.arr("application/json"),
      parameters: JsArray = Json.arr(),
      goodCode: String = "200",
      goodResponse: JsObject
  ): JsValue =
    Json.obj(
      "deprecated"  -> false,
      "tags"        -> Json.arr(tag),
      "summary"     -> summary,
      "description" -> description,
      "operationId" -> operationId,
      "produces"    -> produces,
      "parameters"  -> parameters,
      "responses" -> Json.obj(
        "400" -> Json.obj(
          "description" -> "Bad resource format. Take another look to the swagger, or open an issue :)"
        ),
        "404" -> Json.obj(
          "description" -> "Resource not found or does not exist"
        ),
        goodCode -> goodResponse
      )
    )

  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  //////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // Models definition

  def LargeRequestFaultConfig = Json.obj(
    "description" -> "Config for large request injection fault",
    "type"        -> "object",
    "required"    -> Json.arr("ratio", "additionalRequestSize"),
    "properties" -> Json.obj(
      "ratio"                 -> SimpleDoubleType ~~> "The percentage of requests affected by this fault. Value should be between 0.0 and 1.0",
      "additionalRequestSize" -> SimpleIntType ~~> "The size added to the request body in bytes. Added payload will be spaces only."
    )
  )

  def BadResponse = Json.obj(
    "description" -> "An HTTP response that is not supposed to be returned by a service",
    "type"        -> "object",
    "required"    -> Json.arr("status", "body", "headers"),
    "properties" -> Json.obj(
      "status"  -> SimpleIntType ~~> "The HTTP status for the response",
      "body"    -> SimpleStringType ~~> "The body of the HTTP response",
      "headers" -> SimpleObjectType ~~> "The HTTP headers of the response"
    )
  )
  def LargeResponseFaultConfig = Json.obj(
    "description" -> "Config for large response injection fault",
    "type"        -> "object",
    "required"    -> Json.arr("ratio", "additionalResponseSize"),
    "properties" -> Json.obj(
      "ratio"                 -> SimpleDoubleType ~~> "The percentage of requests affected by this fault. Value should be between 0.0 and 1.0",
      "additionalRequestSize" -> SimpleIntType ~~> "The size added to the response body in bytes. Added payload will be spaces only."
    )
  )
  def LatencyInjectionFaultConfig = Json.obj(
    "description" -> "Config for large latency injection fault",
    "type"        -> "object",
    "required"    -> Json.arr("ratio", "from", "to"),
    "properties" -> Json.obj(
      "ratio" -> SimpleDoubleType ~~> "The percentage of requests affected by this fault. Value should be between 0.0 and 1.0",
      "from"  -> SimpleIntType ~~> "The start range of latency added to the request",
      "to"    -> SimpleIntType ~~> "The end range of latency added to the request"
    )
  )
  def BadResponsesFaultConfig = Json.obj(
    "description" -> "Config for bad requests injection fault",
    "type"        -> "object",
    "required"    -> Json.arr("ratio", "responses"),
    "properties" -> Json.obj(
      "ratio"     -> SimpleDoubleType ~~> "The percentage of requests affected by this fault. Value should be between 0.0 and 1.0",
      "responses" -> ArrayOf(Ref("BadResponse")) ~~> "The possibles responses"
    )
  )
  def ChaosConfig = Json.obj(
    "description" -> "Configuration for the faults that can be injected in requests",
    "type"        -> "object",
    "required" -> Json.arr(
      "enabled"
    ),
    "properties" -> Json.obj(
      "enabled"                     -> SimpleBooleanType ~~> "Whether or not this config is enabled",
      "largeRequestFaultConfig"     -> Ref("LargeRequestFaultConfig"),
      "largeResponseFaultConfig"    -> Ref("LargeResponseFaultConfig"),
      "latencyInjectionFaultConfig" -> Ref("LatencyInjectionFaultConfig"),
      "badResponsesFaultConfig"     -> Ref("BadResponsesFaultConfig")
    )
  )
  def OutageStrategy = Json.obj(
    "type" -> "string",
    "enum" -> Json.arr("OneServicePerGroup", "AllServicesPerGroup")
  )
  def SnowMonkeyConfig = Json.obj(
    "description" -> """Configuration for the faults that can be injected in requests. The name Snow Monkey is an hommage to Netflix's Chaos Monkey 😉""",
    "type"        -> "object",
    "required" -> Json.arr(
      "enabled",
      "outageStrategy",
      "includeUserFacingDescriptors",
      "dryRun",
      "timesPerDay",
      "startTime",
      "stopTime",
      "outageDurationFrom",
      "outageDurationTo",
      "targetGroups",
      "chaosConfig"
    ),
    "properties" -> Json.obj(
      "enabled"                      -> SimpleBooleanType ~~> "Whether or not this config is enabled",
      "outageStrategy"               -> Ref("OutageStrategy") ~~> "",
      "includeUserFacingDescriptors" -> SimpleBooleanType ~~> "Whether or not user facing apps. will be impacted by Snow Monkey",
      "dryRun"                       -> SimpleBooleanType ~~> "Whether or not outages will actualy impact requests",
      "timesPerDay"                  -> SimpleIntType ~~> "Number of time per day each service will be outage",
      "startTime"                    -> SimpleTimeType ~~> "Start time of Snow Monkey each day",
      "stopTime"                     -> SimpleTimeType ~~> "Stop time of Snow Monkey each day",
      "outageDurationFrom"           -> SimpleIntType ~~> "Start of outage duration range",
      "outageDurationTo"             -> SimpleIntType ~~> "End of outage duration range",
      "targetGroups"                 -> ArrayOf(SimpleStringType) ~~> "Groups impacted by Snow Monkey. If empty, all groups will be impacted",
      "chaosConfig"                  -> Ref("ChaosConfig")
    )
  )
  def Outage = Json.obj(
    "description" -> "An outage by the Snow Monkey on a service",
    "type"        -> "object",
    "required"    -> Json.arr("descriptorId", "descriptorName", "until", "duration"),
    "properties" -> Json.obj(
      "descriptorId"   -> SimpleStringType ~~> "The service impacted by outage",
      "descriptorName" -> SimpleStringType ~~> "The name of service impacted by outage",
      "until"          -> SimpleTimeType ~~> "The end of the outage",
      "duration"       -> SimpleIntType ~~> "The duration of the outage"
    )
  )
  def Target = Json.obj(
    "description" -> "A Target is where an HTTP call will be forwarded in the end from a service domain",
    "type"        -> "object",
    "required"    -> Json.arr("host", "scheme"),
    "properties" -> Json.obj(
      "host"   -> SimpleHostType ~~> "The host on which the HTTP call will be forwarded. Can be a domain name, or an IP address. Can also have a port",
      "scheme" -> SimpleStringType ~~> "The protocol used for communication. Can be http or https"
    )
  )

  def IpFiltering = Json.obj(
    "description" -> "The filtering configuration block for a service of globally.",
    "type"        -> "object",
    "required"    -> Json.arr("whitelist", "blacklist"),
    "properties" -> Json.obj(
      "whitelist" -> ArrayOf(SimpleIpv4Type) ~~> "Whitelisted IP addresses",
      "blacklist" -> ArrayOf(SimpleIpv4Type) ~~> "Blacklisted IP addresses"
    )
  )

  def ExposedApi = Json.obj(
    "description" -> "The Open API configuration for your service (if one)",
    "type"        -> "object",
    "required"    -> Json.arr("exposeApi"),
    "properties" -> Json.obj(
      "exposeApi"            -> SimpleBooleanType ~~> "Whether or not the current service expose an API with an Open API descriptor",
      "openApiDescriptorUrl" -> SimpleUriType ~~> "The URL of the Open API descriptor"
    )
  )

  def HealthCheck = Json.obj(
    "description" -> "The configuration for checking health of a service. Otoroshi will perform GET call on the URL to check if the service is still alive",
    "type"        -> "object",
    "required"    -> Json.arr("enabled"),
    "properties" -> Json.obj(
      "enabled" -> SimpleBooleanType ~~> "Whether or not healthcheck is enabled on the current service descriptor",
      "url"     -> SimpleUriType ~~> "The URL to check"
    )
  )

  def StatsdConfig = Json.obj(
    "description" -> "The configuration for statsd metrics push",
    "type"        -> "object",
    "required"    -> Json.arr("host", "port", "datadog"),
    "properties" -> Json.obj(
      "host"    -> SimpleStringType ~~> "The host of the StatsD agent",
      "port"    -> SimpleIntType ~~> "The port of the StatsD agent",
      "datadog" -> SimpleBooleanType ~~> "Datadog agent"
    )
  )

  def CorsSettings = Json.obj(
    "description" -> "The configuration for cors support",
    "type"        -> "object",
    "required" -> Json.arr(
      "enabled",
      "allowOrigin",
      "exposeHeaders",
      "allowHeaders",
      "allowMethods",
      "excludedPatterns",
      "maxAge",
      "allowCredentials"
    ),
    "properties" -> Json.obj(
      "enabled"          -> SimpleBooleanType ~~> "Whether or not cors is enabled",
      "allowOrigin"      -> SimpleStringType ~~> "The cors allowed origin",
      "exposeHeaders"    -> ArrayOf(SimpleStringType) ~~> "The cors exposed header",
      "allowHeaders"     -> ArrayOf(SimpleStringType) ~~> "The cors allowed headers",
      "allowMethods"     -> ArrayOf(SimpleStringType) ~~> "The cors allowed methods",
      "excludedPatterns" -> ArrayOf(SimpleStringType) ~~> "The cors excluded patterns",
      "maxAge"           -> SimpleIntType ~~> "Cors max age",
      "allowCredentials" -> SimpleBooleanType ~~> "Allow to pass credentials"
    )
  )

  def RedirectionSettings = Json.obj(
    "description" -> "The configuration for redirection per service",
    "type"        -> "object",
    "required" -> Json.arr(
      "enabled",
      "to",
      "code"
    ),
    "properties" -> Json.obj(
      "enabled" -> SimpleBooleanType ~~> "Whether or not redirection is enabled",
      "to"      -> SimpleStringType ~~> "The location for redirection",
      "code"    -> SimpleIntType ~~> "The http redirect code",
    )
  )

  def ValidationAuthority = Json.obj(
    "description" -> "Settings to access a validation authority server",
    "type"        -> "object",
    "required" -> Json.arr(
      "id",
      "name",
      "description",
      "url",
      "host",
      "goodTtl",
      "badTtl",
      "method",
      "path",
      "timeout",
      "noCache",
      "alwaysValid",
      "headers"
    ),
    "properties" -> Json.obj(
      "id"          -> SimpleStringType ~~> "The id of the settings",
      "name"        -> SimpleStringType ~~> "The name of the settings",
      "description" -> SimpleStringType ~~> "The description of the settings",
      "url"         -> SimpleStringType ~~> "The URL of the server",
      "host"        -> SimpleStringType ~~> "The host of the server",
      "goodTtl"     -> SimpleLongType ~~> "The TTL for valid access response caching",
      "badTtl"      -> SimpleLongType ~~> "The TTL for invalid access response caching",
      "method"      -> SimpleStringType ~~> "The HTTP method",
      "path"        -> SimpleStringType ~~> "The URL path",
      "timeout"     -> SimpleLongType ~~> "The call timeout",
      "noCache"     -> SimpleBooleanType ~~> "Avoid caching responses",
      "alwaysValid" -> SimpleBooleanType ~~> "Bypass http calls, every certificates are valids",
      "headers"     -> SimpleObjectType ~~> "HTTP call headers"
    )
  )

  def ElasticConfig = Json.obj(
    "description" -> "The configuration for elastic access",
    "type"        -> "object",
    "required"    -> Json.arr("clusterUri", "index", "type", "user", "password", "headers"),
    "properties" -> Json.obj(
      "clusterUri" -> SimpleStringType ~~> "URL of the elastic cluster",
      "index"      -> OptionalStringType ~~> "Index for events. Default is otoroshi-events",
      "type"       -> OptionalStringType ~~> "Type of events. Default is event",
      "user"       -> OptionalStringType ~~> "Optional user",
      "password"   -> OptionalStringType ~~> "Optional password",
      "headers"    -> SimpleObjectType ~~> "Additionnal http headers"
    )
  )

  def ClientConfig = Json.obj(
    "description" -> "The configuration of the circuit breaker for a service descriptor",
    "type"        -> "object",
    "required" -> Json.arr("useCircuitBreaker",
                           "retries",
                           "maxErrors",
                           "retryInitialDelay",
                           "backoffFactor",
                           "callTimeout",
                           "globalTimeout",
                           "sampleInterval"),
    "properties" -> Json.obj(
      "useCircuitBreaker" -> SimpleBooleanType ~~> "Use a circuit breaker to avoid cascading failure when calling chains of services. Highly recommended !",
      "retries"           -> SimpleIntType ~~> "Specify how many times the client will try to fetch the result of the request after an error before giving up.",
      "maxErrors"         -> SimpleIntType ~~> "Specify how many errors can pass before opening the circuit breaker",
      "retryInitialDelay" -> SimpleIntType ~~> "Specify the delay between two retries. Each retry, the delay is multiplied by the backoff factor",
      "backoffFactor"     -> SimpleIntType ~~> "Specify the factor to multiply the delay for each retry",
      "callTimeout"       -> SimpleIntType ~~> "Specify how long each call should last at most in milliseconds",
      "globalTimeout"     -> SimpleIntType ~~> "Specify how long the global call (with retries) should last at most in milliseconds",
      "sampleInterval"    -> SimpleIntType ~~> "Specify the sliding window time for the circuit breaker in milliseconds, after this time, error count will be reseted"
    )
  )

  def Canary = Json.obj(
    "description" -> "The configuration of the canary mode for a service descriptor",
    "type"        -> "object",
    "required"    -> Json.arr("enabled", "traffic", "targets", "root"),
    "properties" -> Json.obj(
      "enabled" -> SimpleBooleanType ~~> "Use canary mode for this service",
      "traffic" -> SimpleIntType ~~> "Ratio of traffic that will be sent to canary targets.",
      "targets" -> ArrayOf(Ref("Target")) ~~> "The list of target that Otoroshi will proxy and expose through the subdomain defined before. Otoroshi will do round-robin load balancing between all those targets with circuit breaker mecanism to avoid cascading failures",
      "root"    -> SimpleStringType ~~> "Otoroshi will append this root to any target choosen. If the specified root is '/api/foo', then a request to https://yyyyyyy/bar will actually hit https://xxxxxxxxx/api/foo/bar"
    )
  )

  def Service = Json.obj(
    "description" -> "An otoroshi service descriptor. Represent a forward HTTP call on a domain to another location with some optional api management mecanism",
    "type"        -> "object",
    "required" -> Json.arr(
      "id",
      "groupId",
      "name",
      "env",
      "domain",
      "subdomain",
      "targets",
      "root",
      "enabled",
      "privateApp",
      "forceHttps",
      "maintenanceMode",
      "buildMode",
      "enforceSecureCommunication"
    ),
    "properties" -> Json.obj(
      "id"                         -> SimpleUuidType ~~> "A unique random string to identify your service",
      "groupId"                    -> SimpleStringType ~~> "Each service descriptor is attached to a group. A group can have one or more services. Each API key is linked to a group and allow access to every service in the group",
      "name"                       -> SimpleStringType ~~> "The name of your service. Only for debug and human readability purposes",
      "env"                        -> SimpleStringType ~~> "The line on which the service is available. Based on that value, the name of the line will be appended to the subdomain. For line prod, nothing will be appended. For example, if the subdomain is 'foo' and line is 'preprod', then the exposed service will be available at 'foo.preprod.mydomain'",
      "domain"                     -> SimpleStringType ~~> "The domain on which the service is available.",
      "subdomain"                  -> SimpleStringType ~~> "The subdomain on which the service is available",
      "targets"                    -> ArrayOf(Ref("Target")) ~~> "The list of target that Otoroshi will proxy and expose through the subdomain defined before. Otoroshi will do round-robin load balancing between all those targets with circuit breaker mecanism to avoid cascading failures",
      "root"                       -> SimpleStringType ~~> "Otoroshi will append this root to any target choosen. If the specified root is '/api/foo', then a request to https://yyyyyyy/bar will actually hit https://xxxxxxxxx/api/foo/bar",
      "matchingRoot"               -> SimpleStringType ~~> "The root path on which the service is available",
      "localHost"                  -> SimpleStringType ~~> "The host used localy, mainly localhost:xxxx",
      "localScheme"                -> SimpleStringType ~~> "The scheme used localy, mainly http",
      "redirectToLocal"            -> SimpleBooleanType ~~> "If you work locally with Otoroshi, you may want to use that feature to redirect one particuliar service to a local host. For example, you can relocate https://foo.preprod.bar.com to http://localhost:8080 to make some tests",
      "enabled"                    -> SimpleBooleanType ~~> "Activate or deactivate your service. Once disabled, users will get an error page saying the service does not exist",
      "userFacing"                 -> SimpleBooleanType ~~> "The fact that this service will be seen by users and cannot be impacted by the Snow Monkey",
      "privateApp"                 -> SimpleBooleanType ~~> "When enabled, user will be allowed to use the service (UI) only if they are registered users of the private apps domain",
      "forceHttps"                 -> SimpleBooleanType ~~> "Will force redirection to https:// if not present",
      "maintenanceMode"            -> SimpleBooleanType ~~> "Display a maintainance page when a user try to use the service",
      "buildMode"                  -> SimpleBooleanType ~~> "Display a construction page when a user try to use the service",
      "enforceSecureCommunication" -> SimpleBooleanType ~~> "When enabled, Otoroshi will try to exchange headers with downstream service to ensure no one else can use the service from outside",
      "sendOtoroshiHeadersBack"    -> SimpleBooleanType ~~> "When enabled, Otoroshi will send headers to consumer like request id, client latency, overhead, etc ...",
      "xForwardedHeaders"          -> SimpleBooleanType ~~> "When enabled, Otoroshi will send X-Forwarded-* headers to target",
      "overrideHost"               -> SimpleBooleanType ~~> "When enabled, Otoroshi will automatically set the Host header to corresponding target host",
      "secComExcludedPatterns"     -> ArrayOf(SimpleStringType) ~~> "URI patterns excluded from secured communications",
      "publicPatterns"             -> ArrayOf(SimpleStringType) ~~> "By default, every services are private only and you'll need an API key to access it. However, if you want to expose a public UI, you can define one or more public patterns (regex) to allow access to anybody. For example if you want to allow anybody on any URL, just use '/.*'",
      "privatePatterns"            -> ArrayOf(SimpleStringType) ~~> "If you define a public pattern that is a little bit too much, you can make some of public URL private again",
      "ipFiltering"                -> Ref("IpFiltering"),
      "api"                        -> Ref("ExposedApi"),
      "healthCheck"                -> Ref("HealthCheck"),
      "clientConfig"               -> Ref("ClientConfig"),
      "Canary"                     -> Ref("Canary"),
      "statsdConfig"               -> Ref("StatsdConfig"),
      "chaosConfig"                -> Ref("ChaosConfig"),
      "jwtVerifier"                -> OneOf(Ref("LocalJwtVerifier"), Ref("RefJwtVerifier")),
      "secComSettings" -> OneOf(Ref("HSAlgoSettings"),
                                Ref("RSAlgoSettings"),
                                Ref("ESAlgoSettings"),
                                Ref("JWKSAlgoSettings")),
      "metadata"            -> SimpleObjectType ~~> "Just a bunch of random properties",
      "matchingHeaders"     -> SimpleObjectType ~~> "Specify headers that MUST be present on client request to route it. Useful to implement versioning",
      "additionalHeaders"   -> SimpleObjectType ~~> "Specify headers that will be added to each client request. Useful to add authentication",
      "authConfigRef"       -> SimpleStringType ~~> "A reference to a global auth module config",
      "transformerRef"      -> SimpleStringType ~~> "A reference to a request transformer",
      "clientValidatorRef"  -> SimpleStringType ~~> "A reference to validation authority",
      "clientValidatorRef"  -> SimpleStringType ~~> "A reference to validation authority",
      "cors"                -> Ref("CorsSettings"),
      "redirection"         -> Ref("RedirectionSettings"),
      "overrideHost"        -> SimpleBooleanType ~~> "Host header will be overriden with Host of the target",
      "xForwardedHeaders"   -> SimpleBooleanType ~~> "Send X-Forwarded-* headers",
      "gzip"                -> Ref("Gzip"),
      "headersVerification" -> SimpleObjectType ~~> "Specify headers that will be verified after routing.",
    )
  )

  def Gzip = Json.obj(
    "description" -> "Configuration for gzip of service responses",
    "type"        -> "object",
    "required"    -> Json.arr(
      "enabled",
      "excludedPatterns",
      "whiteList",
      "blackList",
      "bufferSize",
      "chunkedThreshold",
      "compressionLevel",
    ),
    "properties" -> Json.obj(
      "enabled"           -> SimpleBooleanType ~~> "Whether gzip compression is enabled or not",
      "excludedPatterns"  -> ArrayOf(SimpleStringType) ~~> "Patterns that are excluded from gzipping",        
      "whiteList"         -> ArrayOf(SimpleStringType) ~~> "Whitelisted mime types. Wildcard supported",  
      "blackList"         -> ArrayOf(SimpleStringType) ~~> "Blacklisted mime types. Wildcard supported",  
      "bufferSize"        -> SimpleLongType ~~> "Size of the GZip buffer", 
      "chunkedThreshold"  -> SimpleLongType ~~> "Threshold for chunking data",        
      "compressionLevel"  -> SimpleIntType ~~> "Compression level. From 0 to 9"        
    )
  )

  def ApiKey = Json.obj(
    "description" -> "An Otoroshi Api Key. An Api Key is defined for a group of services to allow usage of the same Api Key for multiple services.",
    "type"        -> "object",
    "required"    -> Json.arr("clientId", "clientSecret", "clientName", "authorizedGroup", "enabled"),
    "properties" -> Json.obj(
      "clientId"        -> SimpleStringType ~~> "The unique id of the Api Key. Usually 16 random alpha numerical characters, but can be anything",
      "clientSecret"    -> SimpleStringType ~~> "The secret of the Api Key. Usually 64 random alpha numerical characters, but can be anything",
      "clientName"      -> SimpleStringType ~~> "The name of the api key, for humans ;-)",
      "authorizedGroup" -> SimpleStringType ~~> "The group id on which the key is authorized",
      "enabled"         -> SimpleBooleanType ~~> "Whether or not the key is enabled. If disabled, resources won't be available to calls using this key",
      "throttlingQuota" -> SimpleLongType ~~> "Authorized number of calls per second, measured on 10 seconds",
      "dailyQuota"      -> SimpleLongType ~~> "Authorized number of calls per day",
      "monthlyQuota"    -> SimpleLongType ~~> "Authorized number of calls per month",
      "metadata"        -> SimpleObjectType ~~> "Bunch of metadata for the key"
    )
  )

  def Group = Json.obj(
    "description" -> "An Otoroshi service group is just a group of service descriptor. It is useful to be able to define Api Keys for the whole group",
    "type"        -> "object",
    "required"    -> Json.arr("id", "name"),
    "properties" -> Json.obj(
      "id"          -> SimpleStringType ~~> "The unique id of the group. Usually 64 random alpha numerical characters, but can be anything",
      "name"        -> SimpleStringType ~~> "The name of the group",
      "description" -> SimpleStringType ~~> "The descriptoin of the group"
    )
  )

  def Auth0Config = Json.obj(
    "description" -> "Configuration for Auth0 domain",
    "type"        -> "object",
    "required"    -> Json.arr("clientId", "clientSecret", "domain", "callbackUrl"),
    "properties" -> Json.obj(
      "clientId"     -> SimpleStringType ~~> "Auth0 client id",
      "clientSecret" -> SimpleStringType ~~> "Auth0 client secret",
      "domain"       -> SimpleStringType ~~> "Auth0 domain",
      "callbackUrl"  -> SimpleStringType ~~> "Auth0 callback URL"
    )
  )

  def MailgunSettings = Json.obj(
    "description" -> "Configuration for mailgun api client",
    "type"        -> "object",
    "required"    -> Json.arr("apiKey", "domain"),
    "properties" -> Json.obj(
      "apiKey" -> SimpleStringType ~~> "Mailgun Api Key",
      "domain" -> SimpleStringType ~~> "Mailgun domain"
    )
  )

  def CleverSettings = Json.obj(
    "description" -> "Configuration for CleverCloud client",
    "type"        -> "object",
    "required"    -> Json.arr("consumerKey", "consumerSecret", "token", "secret", "orgaId"),
    "properties" -> Json.obj(
      "consumerKey"    -> SimpleStringType ~~> "CleverCloud consumer key",
      "consumerSecret" -> SimpleStringType ~~> "CleverCloud consumer token",
      "token"          -> SimpleStringType ~~> "CleverCloud oauth token",
      "secret"         -> SimpleStringType ~~> "CleverCloud oauth secret",
      "orgaId"         -> SimpleStringType ~~> "CleverCloud organization id"
    )
  )

  def GlobalConfig = Json.obj(
    "type" -> "object",
    "required" -> Json.arr(
      "streamEntityOnly",
      "autoLinkToDefaultGroup",
      "limitConcurrentRequests",
      "maxConcurrentRequests",
      "useCircuitBreakers",
      "apiReadOnly",
      "u2fLoginOnly",
      "ipFiltering",
      "throttlingQuota",
      "perIpThrottlingQuota",
      "analyticsWebhooks",
      "alertsWebhooks",
      "alertsEmails",
      "endlessIpAddresses"
    ),
    "description" -> "The global config object of Otoroshi, used to customize settings of the current Otoroshi instance",
    "properties" -> Json.obj(
      "lines"                   -> ArrayOf(SimpleStringType) ~~> "Possibles lines for Otoroshi",
      "streamEntityOnly"        -> SimpleBooleanType ~~> "HTTP will be streamed only. Doesn't work with old browsers",
      "autoLinkToDefaultGroup"  -> SimpleBooleanType ~~> "If not defined, every new service descriptor will be added to the default group",
      "limitConcurrentRequests" -> SimpleBooleanType ~~> "If enabled, Otoroshi will reject new request if too much at the same time",
      "maxConcurrentRequests"   -> SimpleLongType ~~> "The number of authorized request processed at the same time",
      "maxHttp10ResponseSize"   -> SimpleLongType ~~> "The max size in bytes of an HTTP 1.0 response",
      "useCircuitBreakers"      -> SimpleBooleanType ~~> "If enabled, services will be authorized to use circuit breakers",
      "apiReadOnly"             -> SimpleBooleanType ~~> "If enabled, Admin API won't be able to write/update/delete entities",
      "u2fLoginOnly"            -> SimpleBooleanType ~~> "If enabled, login to backoffice through Auth0 will be disabled",
      "ipFiltering"             -> Ref("IpFiltering"),
      "throttlingQuota"         -> SimpleLongType ~~> "Authorized number of calls per second globally, measured on 10 seconds",
      "perIpThrottlingQuota"    -> SimpleLongType ~~> "Authorized number of calls per second globally per IP address, measured on 10 seconds",
      "elasticWritesConfigs"    -> ArrayOf(Ref("ElasticConfig")) ~~> "Configs. for Elastic writes",
      "elasticReadsConfig"      -> Ref("ElasticConfig") ~~> "Config. for elastic reads",
      "analyticsWebhooks"       -> ArrayOf(Ref("Webhook")) ~~> "Webhook that will receive all internal Otoroshi events",
      "alertsWebhooks"          -> ArrayOf(Ref("Webhook")) ~~> "Webhook that will receive all Otoroshi alert events",
      "alertsEmails"            -> ArrayOf(SimpleEmailType) ~~> "Email addresses that will receive all Otoroshi alert events",
      "endlessIpAddresses"      -> ArrayOf(SimpleIpv4Type) ~~> "IP addresses for which any request to Otoroshi will respond with 128 Gb of zeros",
      "middleFingers"           -> SimpleBooleanType ~~> "Use middle finger emoji as a response character for endless HTTP responses",
      "maxLogsSize"             -> SimpleIntType ~~> "Number of events kept locally",
      "cleverSettings"          -> Ref("CleverSettings") ~~> "Optional CleverCloud configuration",
      "mailGunSettings"         -> Ref("MailgunSettings") ~~> "Optional mailgun configuration",
      "backofficeAuth0Config"   -> Ref("Auth0Config") ~~> "Optional configuration for the backoffice Auth0 domain",
      "privateAppsAuth0Config"  -> Ref("Auth0Config") ~~> "Optional configuration for the private apps Auth0 domain"
    )
  )

  def Webhook = Json.obj(
    "description" -> "A callback URL where events are posted",
    "type"        -> "object",
    "required"    -> Json.arr("url", "headers"),
    "properties" -> Json.obj(
      "url"     -> SimpleUriType ~~> "The URL where events are posted",
      "headers" -> SimpleObjectType ~~> "Headers to authorize the call or whatever"
    )
  )

  def ImportExportStats = Json.obj(
    "description" -> "Global stats for the current Otoroshi instances",
    "type"        -> "object",
    "required"    -> Json.arr("calls", "dataIn", "dataOut"),
    "properties" -> Json.obj(
      "calls"   -> SimpleLongType ~~> "Number of calls to Otoroshi globally",
      "dataIn"  -> SimpleLongType ~~> "The amount of data sent to Otoroshi globally",
      "dataOut" -> SimpleLongType ~~> "The amount of data sent from Otoroshi globally"
    )
  )

  def U2FAdmin = Json.obj(
    "description" -> "Administrator using FIDO U2F device to access Otoroshi",
    "type"        -> "object",
    "required"    -> Json.arr("username", "label", "password", "createdAt", "registration"),
    "properties" -> Json.obj(
      "username"     -> SimpleStringType ~~> "The email address of the user",
      "label"        -> SimpleStringType ~~> "The label for the user",
      "password"     -> SimpleStringType ~~> "The hashed password of the user",
      "createdAt"    -> SimpleLongType ~~> "The creation date of the user",
      "registration" -> SimpleObjectType ~~> "The U2F registration slug"
    )
  )

  def SimpleAdmin = Json.obj(
    "description" -> "Administrator using just login/password tuple to access Otoroshi",
    "type"        -> "object",
    "required"    -> Json.arr("username", "label", "password", "createdAt"),
    "properties" -> Json.obj(
      "username"  -> SimpleStringType ~~> "The email address of the user",
      "label"     -> SimpleStringType ~~> "The label for the user",
      "password"  -> SimpleStringType ~~> "The hashed password of the user",
      "createdAt" -> SimpleLongType ~~> "The creation date of the user"
    )
  )

  def ErrorTemplate = Json.obj(
    "description" -> "Error templates for a service descriptor",
    "type"        -> "object",
    "required" -> Json.arr("serviceId",
                           "template40x",
                           "template50x",
                           "templateBuild",
                           "templateMaintenance",
                           "messages"),
    "properties" -> Json.obj(
      "serviceId"           -> SimpleStringType ~~> "The Id of the service for which the error template is enabled",
      "template40x"         -> SimpleStringType ~~> "The html template for 40x errors",
      "template50x"         -> SimpleStringType ~~> "The html template for 50x errors",
      "templateBuild"       -> SimpleStringType ~~> "The html template for build page",
      "templateMaintenance" -> SimpleStringType ~~> "The html template for maintenance page",
      "messages"            -> SimpleObjectType ~~> "Map for custom messages"
    )
  )

  def ImportExport = Json.obj(
    "description" -> "The structure that can be imported to or exported from Otoroshi. It represent the memory state of Otoroshi",
    "type"        -> "object",
    "required" -> Json.arr("label",
                           "dateRaw",
                           "date",
                           "stats",
                           "config",
                           "admins",
                           "simpleAdmins",
                           "serviceGroups",
                           "apiKeys",
                           "serviceDescriptors",
                           "errorTemplates"),
    "properties" -> Json.obj(
      "label"              -> SimpleStringType,
      "dateRaw"            -> SimpleLongType,
      "date"               -> SimpleDateTimeType,
      "stats"              -> Ref("ImportExportStats") ~~> "Current global stats at the time of export",
      "config"             -> Ref("GlobalConfig") ~~> "Current global config at the time of export",
      "appConfig"          -> SimpleObjectType ~~> "Current env variables at the time of export",
      "admins"             -> ArrayOf(U2FAdmin) ~~> "Current U2F admin at the time of export",
      "simpleAdmins"       -> ArrayOf(SimpleAdmin) ~~> "Current simple admins at the time of export",
      "serviceGroups"      -> ArrayOf(Group) ~~> "Current service groups at the time of export",
      "apiKeys"            -> ArrayOf(ApiKey) ~~> "Current apik keys at the time of export",
      "serviceDescriptors" -> ArrayOf(Service) ~~> "Current service descriptors at the time of export",
      "errorTemplates"     -> ArrayOf(ErrorTemplate) ~~> "Current error templates at the time of export"
    )
  )

  def OtoroshiHealth = Json.obj(
    "description" -> "The structure that represent current Otoroshi health",
    "type"        -> "object",
    "required"    -> Json.arr("label", "otoroshi", "datastore"),
    "properties" -> Json.obj(
      "otoroshi" -> Json.obj(
        "type" -> "string",
        "enum" -> Json.arr("healthy", "unhealthy", "down")
      ),
      "datastore" -> Json.obj(
        "type" -> "string",
        "enum" -> Json.arr("healthy", "unhealthy", "unreachable")
      )
    )
  )

  def Stats = Json.obj(
    "description" -> "Live stats for a service or globally",
    "type"        -> "object",
    "required" -> Json.arr("calls",
                           "dataIn",
                           "dataOut",
                           "rate",
                           "duration",
                           "overhead",
                           "dataInRate",
                           "dataOutRate",
                           "concurrentHandledRequests"),
    "properties" -> Json.obj(
      "calls"                     -> SimpleLongType ~~> "Number of calls on the specified service or globally",
      "dataIn"                    -> SimpleLongType ~~> "The amount of data sent to the specified service or Otoroshi globally",
      "dataOut"                   -> SimpleLongType ~~> "The amount of data sent from the specified service or Otoroshi globally",
      "rate"                      -> SimpleDoubleType ~~> "The rate of data sent from and to the specified service or Otoroshi globally",
      "duration"                  -> SimpleDoubleType ~~> "The average duration for a call",
      "overhead"                  -> SimpleDoubleType ~~> "The average overhead time induced by Otoroshi for each call",
      "dataInRate"                -> SimpleDoubleType ~~> "The rate of data sent to the specified service or Otoroshi globally",
      "dataOutRate"               -> SimpleDoubleType ~~> "The rate of data sent from the specified service or Otoroshi globally",
      "concurrentHandledRequests" -> SimpleLongType ~~> "The number of concurrent request currently"
    )
  )

  def Patch = Json.obj(
    "description" -> "A set of changes described in JSON Patch format: http://jsonpatch.com/ (RFC 6902)",
    "type"        -> "array",
    "items" -> Json.obj(
      "type"     -> "object",
      "required" -> Json.arr("op", "path"),
      "properties" -> Json.obj(
        "op" -> Json.obj(
          "type" -> "string",
          "enum" -> Json.arr("add", "replace", "remove", "copy", "test")
        ),
        "path"  -> SimpleStringType,
        "value" -> Json.obj()
      )
    )
  )

  def Quotas = Json.obj(
    "description" -> "Quotas state for an api key on a service group",
    "type"        -> "object",
    "required" -> Json.arr(
      "authorizedCallsPerSec",
      "currentCallsPerSec",
      "remainingCallsPerSec",
      "authorizedCallsPerDay",
      "currentCallsPerDay",
      "remainingCallsPerDay",
      "authorizedCallsPerMonth",
      "currentCallsPerMonth",
      "remainingCallsPerMonth"
    ),
    "properties" -> Json.obj(
      "authorizedCallsPerSec"   -> SimpleLongType ~~> "The number of authorized calls per second",
      "currentCallsPerSec"      -> SimpleLongType ~~> "The current number of calls per second",
      "remainingCallsPerSec"    -> SimpleLongType ~~> "The remaining number of calls per second",
      "authorizedCallsPerDay"   -> SimpleLongType ~~> "The number of authorized calls per day",
      "currentCallsPerDay"      -> SimpleLongType ~~> "The current number of calls per day",
      "remainingCallsPerDay"    -> SimpleLongType ~~> "The remaining number of calls per day",
      "authorizedCallsPerMonth" -> SimpleLongType ~~> "The number of authorized calls per month",
      "currentCallsPerMonth"    -> SimpleLongType ~~> "The current number of calls per month",
      "remainingCallsPerMonth"  -> SimpleLongType ~~> "The number of authorized calls per month"
    )
  )

  def Deleted = Json.obj(
    "type"     -> "object",
    "required" -> Json.arr("deleted"),
    "properties" -> Json.obj(
      "deleted" -> SimpleBooleanType
    )
  )

  def Done = Json.obj(
    "type"     -> "object",
    "required" -> Json.arr("done"),
    "properties" -> Json.obj(
      "done" -> SimpleBooleanType
    )
  )

  def RefJwtVerifier = Json.obj(
    "description" -> "Reference to a global JWT verifier",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "id",
      "enabled"
    ),
    "properties" -> Json.obj(
      "type"    -> SimpleStringType ~~> "A string with value 'ref'",
      "id"      -> SimpleStringType ~~> "The id of the GlobalJWTVerifier",
      "enabled" -> SimpleBooleanType ~~> "Is it enabled"
    )
  )

  def LocalJwtVerifier = Json.obj(
    "description" -> "A JWT verifier used only for the current service descriptor",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "enabled",
      "strict",
      "source",
      "algoSettings",
      "strategy"
    ),
    "properties" -> Json.obj(
      "type"    -> SimpleStringType ~~> "A string with value 'local'",
      "enabled" -> SimpleBooleanType ~~> "Is it enabled",
      "strict"  -> SimpleBooleanType ~~> "Does it fail if JWT not found",
      "source"  -> OneOf(Ref("InQueryParam"), Ref("InHeader"), Ref("InCookie")),
      "algoSettings" -> OneOf(Ref("HSAlgoSettings"),
                              Ref("RSAlgoSettings"),
                              Ref("ESAlgoSettings"),
                              Ref("JWKSAlgoSettings")),
      "strategy" -> OneOf(Ref("PassThrough"), Ref("Sign"), Ref("Transform"))
    )
  )
  def GlobalJwtVerifier = Json.obj(
    "description" -> "A JWT verifier used by multiple service descriptor",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "id",
      "name",
      "desc",
      "enabled",
      "strict",
      "source",
      "algoSettings",
      "strategy"
    ),
    "properties" -> Json.obj(
      "id"      -> SimpleStringType ~~> "Verifier id",
      "name"    -> SimpleStringType ~~> "Verifier name",
      "desc"    -> SimpleStringType ~~> "Verifier description",
      "enabled" -> SimpleBooleanType ~~> "Is it enabled",
      "strict"  -> SimpleBooleanType ~~> "Does it fail if JWT not found",
      "source"  -> OneOf(Ref("InQueryParam"), Ref("InHeader"), Ref("InCookie")),
      "algoSettings" -> OneOf(Ref("HSAlgoSettings"),
                              Ref("RSAlgoSettings"),
                              Ref("ESAlgoSettings"),
                              Ref("JWKSAlgoSettings")),
      "strategy" -> OneOf(Ref("PassThrough"), Ref("Sign"), Ref("Transform"))
    )
  )
  def GenericOauth2ModuleConfig = Json.obj(
    "description" -> "Settings to authenticate users using a generic OAuth2 provider",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "id",
      "name",
      "desc",
      "sessionMaxAge",
      "clientId",
      "clientSecret",
      "authorizeUrl",
      "tokenUrl",
      "userInfoUrl",
      "loginUrl",
      "logoutUrl",
      "callbackUrl",
      "accessTokenField",
      "nameField",
      "emailField",
      "otoroshiDataField"
    ),
    "properties" -> Json.obj(
      "type"                 -> SimpleStringType ~~> "Type of settings. value is oauth2",
      "id"                   -> SimpleStringType ~~> "Unique id of the config",
      "name"                 -> SimpleStringType ~~> "Name of the config",
      "desc"                 -> SimpleStringType ~~> "Description of the config",
      "sessionMaxAge"        -> SimpleIntType ~~> "Max age of the session",
      "clientId"             -> SimpleStringType ~~> "OAuth Client id",
      "clientSecret"         -> SimpleStringType ~~> "OAuth Client secret",
      "authorizeUrl"         -> SimpleStringType ~~> "OAuth authorize URL",
      "tokenUrl"             -> SimpleStringType ~~> "OAuth token URL",
      "userInfoUrl"          -> SimpleStringType ~~> "OAuth userinfo to get user profile",
      "loginUrl"             -> SimpleStringType ~~> "OAuth login URL",
      "logoutUrl"            -> SimpleStringType ~~> "OAuth logout URL",
      "callbackUrl"          -> SimpleStringType ~~> "Otoroshi callback URL",
      "scope"                -> SimpleStringType ~~> "The scope of the token",
      "claims"               -> SimpleStringType ~~> "The claims of the token",
      "accessTokenField"     -> SimpleStringType ~~> "Field name to get access token",
      "nameField"            -> SimpleStringType ~~> "Field name to get name from user profile",
      "emailField"           -> SimpleStringType ~~> "Field name to get email from user profile",
      "otoroshiDataField"    -> SimpleStringType ~~> "Field name to get otoroshi metadata from. You can specify sub fields using | as separator",
      "oidConfig"            -> SimpleStringType ~~> "URL of the OIDC config. file",
      "useJson"              -> SimpleBooleanType ~~> "Use JSON or URL Form Encoded as payload with the OAuth provider",
      "useCookies"           -> SimpleBooleanType ~~> "Use for redirection to actual service",
      "readProfileFromToken" -> SimpleBooleanType ~~> "The user profile will be read from the JWT token in id_token",
      "jwtVerifier" -> OneOf(Ref("HSAlgoSettings"),
                             Ref("RSAlgoSettings"),
                             Ref("ESAlgoSettings"),
                             Ref("JWKSAlgoSettings")) ~~> "Algo. settings to verify JWT token"
    )
  )

  def InQueryParam = Json.obj(
    "description" -> "JWT location in a query param",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "name",
    ),
    "properties" -> Json.obj(
      "type" -> SimpleStringType ~~> "String with value InQueryParam",
      "name" -> SimpleStringType ~~> "Name of the query param"
    )
  )
  def InHeader = Json.obj(
    "description" -> "JWT location in a header",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "name",
      "remove"
    ),
    "properties" -> Json.obj(
      "type"   -> SimpleStringType ~~> "String with value InHeader",
      "name"   -> SimpleStringType ~~> "Name of the header",
      "remove" -> SimpleStringType ~~> "Remove regex inside the value, like 'Bearer '"
    )
  )
  def InCookie = Json.obj(
    "description" -> "JWT location in a cookie",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "name",
    ),
    "properties" -> Json.obj(
      "type" -> SimpleStringType ~~> "String with value InCookie",
      "name" -> SimpleStringType ~~> "Name of the cookie"
    )
  )
  def HSAlgoSettings = Json.obj(
    "description" -> "Settings for an HMAC + SHA signing algorithm",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "size",
      "secret",
    ),
    "properties" -> Json.obj(
      "type"   -> SimpleStringType ~~> "String with value HSAlgoSettings",
      "size"   -> SimpleIntType ~~> "Size for SHA function. can be 256, 384 or 512",
      "secret" -> SimpleStringType ~~> "The secret value for the HMAC function"
    )
  )
  def RSAlgoSettings = Json.obj(
    "description" -> "Settings for an HMAC + SHA signing algorithm",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "size",
      "publicKey",
    ),
    "properties" -> Json.obj(
      "type"       -> SimpleStringType ~~> "String with value RSAlgoSettings",
      "size"       -> SimpleIntType ~~> "Size for SHA function. can be 256, 384 or 512",
      "publicKey"  -> SimpleStringType ~~> "The public key for the RSA function",
      "privateKey" -> SimpleStringType ~~> "The private key for the RSA function"
    )
  )
  def ESAlgoSettings = Json.obj(
    "description" -> "Settings for an EC + SHA signing algorithm",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "size",
      "publicKey",
    ),
    "properties" -> Json.obj(
      "type"       -> SimpleStringType ~~> "String with value ESAlgoSettings",
      "size"       -> SimpleIntType ~~> "Size for SHA function. can be 256, 384 or 512",
      "publicKey"  -> SimpleStringType ~~> "The public key for the RSA function",
      "privateKey" -> SimpleStringType ~~> "The private key for the RSA function"
    )
  )
  def JWKSAlgoSettings = Json.obj(
    "description" -> "Settings for a JWK set",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "size",
      "publicKey",
    ),
    "properties" -> Json.obj(
      "type"    -> SimpleStringType ~~> "String with value JWKSAlgoSettings",
      "url"     -> SimpleStringType ~~> "The url for the http call",
      "headers" -> SimpleObjectType ~~> "The headers for the http call",
      "timeout" -> SimpleLongType ~~> "The timeout of the http call",
      "ttl"     -> SimpleLongType ~~> "The ttl of the keyset",
      "kty"     -> SimpleStringType ~~> "The type of key: RSA or EC",
    )
  )
  def MappingSettings = Json.obj(
    "description" -> "Settings to change fields of a JWT token",
    "type"        -> "object",
    "required" -> Json.arr(
      "map",
      "values",
      "remove"
    ),
    "properties" -> Json.obj(
      "map"    -> SimpleObjectType ~~> "Fields to rename",
      "values" -> SimpleObjectType ~~> "Fields to set",
      "remove" -> ArrayOf(SimpleStringType) ~~> "Fields to remove"
    )
  )
  def TransformSettings = Json.obj(
    "description" -> "Settings to transform a JWT token and its location",
    "type"        -> "object",
    "required" -> Json.arr(
      "location",
      "mappingSettings"
    ),
    "properties" -> Json.obj(
      "location"        -> OneOf(Ref("InQueryParam"), Ref("InHeader"), Ref("InCookie")),
      "mappingSettings" -> Ref("MappingSettings")
    )
  )
  def VerificationSettings = Json.obj(
    "description" -> "Settings to verify the value of JWT token fields",
    "type"        -> "object",
    "required" -> Json.arr(
      "fields"
    ),
    "properties" -> Json.obj(
      "fields"          -> SimpleObjectType ~~> "Fields to verify with their values",
      "mappingSettings" -> Ref("MappingSettings")
    )
  )
  def PassThrough = Json.obj(
    "description" -> "Strategy where only signature and field values are verified",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "verificationSettings"
    ),
    "properties" -> Json.obj(
      "type"                 -> SimpleStringType ~~> "String with value PassThrough",
      "verificationSettings" -> Ref("VerificationSettings")
    )
  )
  def Sign = Json.obj(
    "description" -> "Strategy where signature and field values are verified, and then token si re-signed",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "verificationSettings",
      "algoSettings"
    ),
    "properties" -> Json.obj(
      "type"                 -> SimpleStringType ~~> "String with value Sign",
      "verificationSettings" -> Ref("VerificationSettings"),
      "algoSettings" -> OneOf(Ref("HSAlgoSettings"),
                              Ref("RSAlgoSettings"),
                              Ref("ESAlgoSettings"),
                              Ref("JWKSAlgoSettings"))
    )
  )

  def Transform = Json.obj(
    "description" -> "Strategy where signature and field values are verified, trasnformed and then token si re-signed",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "verificationSettings",
      "algoSettings"
    ),
    "properties" -> Json.obj(
      "type"                 -> SimpleStringType ~~> "String with value Transform",
      "verificationSettings" -> Ref("VerificationSettings"),
      "transformSettings"    -> Ref("TransformSettings"),
      "algoSettings" -> OneOf(Ref("HSAlgoSettings"),
                              Ref("RSAlgoSettings"),
                              Ref("ESAlgoSettings"),
                              Ref("JWKSAlgoSettings"))
    )
  )

  def InMemoryAuthModuleConfig = Json.obj(
    "description" -> "Settings to authenticate users using the in memory user store",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "id",
      "name",
      "desc",
      "users",
      "sessionMaxAge"
    ),
    "properties" -> Json.obj(
      "type"          -> SimpleStringType ~~> "Type of settings. value is basic",
      "id"            -> SimpleStringType ~~> "Unique id of the config",
      "name"          -> SimpleStringType ~~> "Name of the config",
      "desc"          -> SimpleStringType ~~> "Description of the config",
      "sessionMaxAge" -> SimpleStringType ~~> "Max age of the session",
      "users"         -> ArrayOf(Ref("InMemoryUser")) ~~> "List of users"
    )
  )

  def InMemoryUser = Json.obj(
    "description" -> "A user",
    "type"        -> "object",
    "required" -> Json.arr(
      "name",
      "password",
      "email",
      "metadata",
    ),
    "properties" -> Json.obj(
      "name"     -> SimpleStringType ~~> "Name of the user",
      "email"    -> SimpleStringType ~~> "Email of the user",
      "password" -> SimpleStringType ~~> "Password of the user (BCrypt hash)",
      "metadata" -> SimpleObjectType ~~> "Metadata of the user"
    )
  )

  def LdapUser = Json.obj(
    "description" -> "A user",
    "type"        -> "object",
    "required" -> Json.arr(
      "name",
      "email",
      "metadata",
    ),
    "properties" -> Json.obj(
      "name"     -> SimpleStringType ~~> "Name of the user",
      "email"    -> SimpleStringType ~~> "Email of the user",
      "metadata" -> SimpleObjectType ~~> "Metadata of the user"
    )
  )

  def Certificate = Json.obj(
    "description" -> "A SSL/TLS X509 certificate",
    "type"        -> "object",
    "required" -> Json.arr(
      "id",
      "chain",
      "privateKey",
      "caRef",
      "domain",
      "selfSigned",
      "ca",
      "valid",
      "autoRenew",
      "subject",
      "from",
      "to"
    ),
    "properties" -> Json.obj(
      "id"         -> SimpleStringType ~~> "Id of the certificate",
      "chain"      -> SimpleStringType ~~> "Certificate chain of trust in PEM format",
      "privateKey" -> SimpleStringType ~~> "PKCS8 private key in PEM format",
      "caRef"      -> SimpleStringType ~~> "Reference for a CA certificate in otoroshi",
      "autoRenew"  -> SimpleStringType ~~> "Allow Otoroshi to renew the certificate (if self signed)",
      "domain"     -> SimpleStringType ~~> "Domain of the certificate (read only)",
      "selfSigned" -> SimpleStringType ~~> "Certificate is self signed  read only)",
      "ca"         -> SimpleStringType ~~> "Certificate is a CA (read only)",
      "valid"      -> SimpleStringType ~~> "Certificate is valid (read only)",
      "subject"    -> SimpleStringType ~~> "Subject of the certificate (read only)",
      "from"       -> SimpleStringType ~~> "Start date of validity",
      "to"         -> SimpleStringType ~~> "End date of validity"
    )
  )

  def LdapAuthModuleConfig = Json.obj(
    "description" -> "Settings to authenticate users using a generic OAuth2 provider",
    "type"        -> "object",
    "required" -> Json.arr(
      "type",
      "id",
      "name",
      "desc",
      "sessionMaxAge",
      "serverUrl",
      "searchBase",
      "userBase",
      "groupFilter",
      "searchFilter",
      "adminUsername",
      "adminPassword",
      "nameField",
      "emailField",
      "metadataField",
    ),
    "properties" -> Json.obj(
      "type"              -> SimpleStringType ~~> "Type of settings. value is ldap",
      "id"                -> SimpleStringType ~~> "Unique id of the config",
      "name"              -> SimpleStringType ~~> "Name of the config",
      "desc"              -> SimpleStringType ~~> "Description of the config",
      "sessionMaxAge"     -> SimpleIntType ~~> "Max age of the session",
      "serverUrl"         -> SimpleStringType ~~> "URL of the ldap server",
      "searchBase"        -> SimpleStringType ~~> "LDAP search base",
      "userBase"          -> SimpleStringType ~~> "LDAP user base DN",
      "groupFilter"       -> SimpleStringType ~~> "Filter for groups",
      "searchFilter"      -> SimpleStringType ~~> "Filter for users",
      "adminUsername"     -> SimpleStringType ~~> "The admin username",
      "adminPassword"     -> SimpleStringType ~~> "The admin password",
      "nameField"         -> SimpleStringType ~~> "Field name to get name from user profile",
      "emailField"        -> SimpleStringType ~~> "Field name to get email from user profile",
      "otoroshiDataField" -> SimpleStringType ~~> "Field name to get otoroshi metadata from. You can specify sub fields using | as separator"
    )
  )

  def ScriptCompilationResult = Json.obj(
    "description" -> "The result of the compilation of a Script",
    "type"        -> "object",
    "required" -> Json.arr(
      "done"
    ),
    "properties" -> Json.obj(
      "done"  -> SimpleBooleanType ~~> "Is the task done or not",
      "error" -> Ref("ScriptCompilationError")
    )
  )

  def ScriptCompilationError = Json.obj(
    "description" -> "The error of the compilation of a Script",
    "type"        -> "object",
    "required" -> Json.arr(
      "line",
      "column",
      "file",
      "rawMessage",
      "message",
    ),
    "properties" -> Json.obj(
      "line"       -> SimpleStringType ~~> "The line of the error",
      "column"     -> SimpleStringType ~~> "The column of the error",
      "file"       -> SimpleObjectType ~~> "The file where the error is located",
      "rawMessage" -> SimpleObjectType ~~> "The raw message from the compiler",
      "message"    -> SimpleObjectType ~~> "The message to display for the error"
    )
  )

  def Script = Json.obj(
    "description" -> "A script to transformer otoroshi requests ",
    "type"        -> "object",
    "required" -> Json.arr(
      "id",
      "name",
      "desc",
      "code",
    ),
    "properties" -> Json.obj(
      "id"   -> SimpleStringType ~~> "The id of the script",
      "name" -> SimpleStringType ~~> "The name of the script",
      "desc" -> SimpleObjectType ~~> "The description of the script",
      "code" -> SimpleObjectType ~~> "The code of the script"
    )
  )

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // Operation definitions

  def NewApiKey: JsValue = Json.obj(
    "get" -> Operation(
      tag = "templates",
      summary = "Get a template of an Otoroshi Api Key",
      description = "Get a template of an Otoroshi Api Key. The generated entity is not persisted",
      operationId = "initiateApiKey",
      goodResponse = GoodResponse(Ref("ApiKey"))
    )
  )

  def NewService: JsValue = Json.obj(
    "get" -> Operation(
      tag = "templates",
      summary = "Get a template of an Otoroshi service descriptor",
      description = "Get a template of an Otoroshi service descriptor. The generated entity is not persisted",
      operationId = "initiateService",
      goodResponse = GoodResponse(Ref("Service"))
    )
  )

  def NewGroup: JsValue = Json.obj(
    "get" -> Operation(
      tag = "templates",
      summary = "Get a template of an Otoroshi service group",
      description = "Get a template of an Otoroshi service group. The generated entity is not persisted",
      operationId = "initiateServiceGroup",
      goodResponse = GoodResponse(Ref("Group"))
    )
  )

  def AllLines(): JsValue = Json.obj(
    "get" -> Operation(
      tag = "environments",
      summary = "Get all environments",
      description = "Get all environments provided by the current Otoroshi instance",
      operationId = "allLines",
      goodResponse = GoodResponse(Ref("Environment"))
    )
  )

  def ServicesForLine: JsValue = Json.obj(
    "get" -> Operation(
      tag = "environments",
      summary = "Get all services for an environment",
      description = "Get all services for an environment provided by the current Otoroshi instance",
      operationId = "servicesForALine",
      parameters = Json.arr(
        PathParam("line", "The environment where to find services")
      ),
      goodResponse = GoodResponse(ArrayOf(Ref("Service")))
    )
  )

  def QuotasOfTheApiKeyOfAService = Json.obj(
    "get" -> Operation(
      tag = "apikeys",
      summary = "Get the quota state of an api key",
      description = "Get the quota state of an api key",
      operationId = "apiKeyQuotas",
      parameters = Json.arr(
        PathParam("serviceId", "The api key service id"),
        PathParam("clientId", "the api key id")
      ),
      goodResponse = GoodResponse(Ref("Quotas"))
    ),
    "delete" -> Operation(
      tag = "apikeys",
      summary = "Reset the quota state of an api key",
      description = "Reset the quota state of an api key",
      operationId = "resetApiKeyQuotas",
      parameters = Json.arr(
        PathParam("serviceId", "The api key service id"),
        PathParam("clientId", "the api key id")
      ),
      goodResponse = GoodResponse(Ref("Quotas"))
    )
  )

  def QuotasOfTheApiKeyOfAGroup = Json.obj(
    "get" -> Operation(
      tag = "apikeys",
      summary = "Get the quota state of an api key",
      description = "Get the quota state of an api key",
      operationId = "apiKeyFromGroupQuotas",
      parameters = Json.arr(
        PathParam("groupId", "The api key group id"),
        PathParam("clientId", "the api key id")
      ),
      goodResponse = GoodResponse(Ref("Quotas"))
    ),
    "delete" -> Operation(
      tag = "apikeys",
      summary = "Reset the quota state of an api key",
      description = "Reset the quota state of an api key",
      operationId = "resetApiKeyFromGroupQuotas",
      parameters = Json.arr(
        PathParam("groupId", "The api key group id"),
        PathParam("clientId", "the api key id")
      ),
      goodResponse = GoodResponse(Ref("Quotas"))
    )
  )

  def GroupForApiKey = Json.obj(
    "get" -> Operation(
      tag = "apikeys",
      summary = "Get the group of an api key",
      description = "Get the group of an api key",
      operationId = "apiKeyGroup",
      parameters = Json.arr(
        PathParam("serviceId", "The api key service id"),
        PathParam("clientId", "the api key id")
      ),
      goodResponse = GoodResponse(Ref("Group"))
    )
  )

  def ApiKeyManagementForService = Json.obj(
    "get" -> Operation(
      tag = "apikeys",
      summary = "Get an api key",
      description = "Get an api key for a specified service descriptor",
      operationId = "apiKey",
      parameters = Json.arr(
        PathParam("serviceId", "The api key service id"),
        PathParam("clientId", "the api key id")
      ),
      goodResponse = GoodResponse(Ref("ApiKey"))
    ),
    "put" -> Operation(
      tag = "apikeys",
      summary = "Update an api key",
      description = "Update an api key for a specified service descriptor",
      operationId = "updateApiKey",
      parameters = Json.arr(
        PathParam("serviceId", "The api key service id"),
        PathParam("clientId", "the api key id"),
        BodyParam("The updated api key", Ref("ApiKey"))
      ),
      goodResponse = GoodResponse(Ref("ApiKey"))
    ),
    "patch" -> Operation(
      tag = "apikeys",
      summary = "Update an api key with a diff",
      description = "Update an api key for a specified service descriptor with a diff",
      operationId = "patchApiKey",
      parameters = Json.arr(
        PathParam("serviceId", "The api key service id"),
        PathParam("clientId", "the api key id"),
        BodyParam("The patch for the api key", Ref("Patch"))
      ),
      goodResponse = GoodResponse(Ref("ApiKey"))
    ),
    "delete" -> Operation(
      tag = "apikeys",
      summary = "Delete an api key",
      description = "Delete an api key for a specified service descriptor",
      operationId = "deleteApiKey",
      parameters = Json.arr(
        PathParam("serviceId", "The api key service id"),
        PathParam("clientId", "the api key id")
      ),
      goodResponse = GoodResponse(Ref("Deleted"))
    )
  )

  def ApiKeyManagementForGroup = Json.obj(
    "get" -> Operation(
      tag = "apikeys",
      summary = "Get an api key",
      description = "Get an api key for a specified service group",
      operationId = "apiKeyFromGroup",
      parameters = Json.arr(
        PathParam("groupId", "The api key group id"),
        PathParam("clientId", "the api key id")
      ),
      goodResponse = GoodResponse(Ref("ApiKey"))
    ),
    "put" -> Operation(
      tag = "apikeys",
      summary = "Update an api key",
      description = "Update an api key for a specified service group",
      operationId = "updateApiKeyFromGroup",
      parameters = Json.arr(
        PathParam("groupId", "The api key group id"),
        PathParam("clientId", "the api key id"),
        BodyParam("The updated api key", Ref("ApiKey"))
      ),
      goodResponse = GoodResponse(Ref("ApiKey"))
    ),
    "patch" -> Operation(
      tag = "apikeys",
      summary = "Update an api key with a diff",
      description = "Update an api key for a specified service descriptor with a diff",
      operationId = "patchApiKeyFromGroup",
      parameters = Json.arr(
        PathParam("groupId", "The api key group id"),
        PathParam("clientId", "the api key id"),
        BodyParam("The patch for the api key", Ref("Patch"))
      ),
      goodResponse = GoodResponse(Ref("ApiKey"))
    ),
    "delete" -> Operation(
      tag = "apikeys",
      summary = "Delete an api key",
      description = "Delete an api key for a specified service group",
      operationId = "deleteApiKeyFromGroup",
      parameters = Json.arr(
        PathParam("groupId", "The api key group id"),
        PathParam("clientId", "the api key id")
      ),
      goodResponse = GoodResponse(Ref("Deleted"))
    )
  )

  def ApiKeysManagementForService = Json.obj(
    "get" -> Operation(
      tag = "apikeys",
      summary = "Get all api keys for the group of a service",
      description = "Get all api keys for the group of a service",
      operationId = "apiKeys",
      parameters = Json.arr(
        PathParam("serviceId", "The api key service id")
      ),
      goodResponse = GoodResponse(ArrayOf(Ref("ApiKey")))
    ),
    "post" -> Operation(
      tag = "apikeys",
      summary = "Create a new api key for a service",
      description = "Create a new api key for a service",
      operationId = "createApiKey",
      parameters = Json.arr(
        PathParam("serviceId", "The api key service id"),
        BodyParam("The api key to create", Ref("ApiKey"))
      ),
      goodResponse = GoodResponse(Ref("ApiKey"))
    )
  )

  def ApiKeysManagementForGroup = Json.obj(
    "get" -> Operation(
      tag = "apikeys",
      summary = "Get all api keys for the group of a service",
      description = "Get all api keys for the group of a service",
      operationId = "apiKeysFromGroup",
      parameters = Json.arr(
        PathParam("groupId", "The api key group id")
      ),
      goodResponse = GoodResponse(ArrayOf(Ref("ApiKey")))
    ),
    "post" -> Operation(
      tag = "apikeys",
      summary = "Create a new api key for a group",
      description = "Create a new api key for a group",
      operationId = "createApiKeyFromGroup",
      parameters = Json.arr(
        PathParam("groupId", "The api key group id"),
        BodyParam("The api key to create", Ref("ApiKey"))
      ),
      goodResponse = GoodResponse(Ref("ApiKey"))
    )
  )

  def JWTVerifiers = Json.obj(
    "get" -> Operation(
      tag = "jwt-verifiers",
      summary = "Get all global JWT verifiers",
      description = "Get all global JWT verifiers",
      operationId = "findAllGlobalJwtVerifiers",
      goodResponse = GoodResponse(ArrayOf(Ref("GlobalJwtVerifier")))
    ),
    "get" -> Operation(
      tag = "jwt-verifiers",
      summary = "Get one global JWT verifiers",
      description = "Get one global JWT verifiers",
      operationId = "findGlobalJwtVerifiersById",
      parameters = Json.arr(
        PathParam("verifierId", "The jwt verifier id")
      ),
      goodResponse = GoodResponse(Ref("GlobalJwtVerifier"))
    ),
    "delete" -> Operation(
      tag = "jwt-verifiers",
      summary = "Delete one global JWT verifiers",
      description = "Delete one global JWT verifiers",
      operationId = "deleteGlobalJwtVerifier",
      parameters = Json.arr(
        PathParam("verifierId", "The jwt verifier id")
      ),
      goodResponse = GoodResponse(Ref("Deleted"))
    ),
    "put" -> Operation(
      tag = "jwt-verifiers",
      summary = "Update one global JWT verifiers",
      description = "Update one global JWT verifiers",
      operationId = "updateGlobalJwtVerifier",
      parameters = Json.arr(
        PathParam("verifierId", "The jwt verifier id"),
        BodyParam("The verifier to update", Ref("GlobalJwtVerifier"))
      ),
      goodResponse = GoodResponse(Ref("GlobalJwtVerifier"))
    ),
    "patch" -> Operation(
      tag = "jwt-verifiers",
      summary = "Update one global JWT verifiers",
      description = "Update one global JWT verifiers",
      operationId = "patchGlobalJwtVerifier",
      parameters = Json.arr(
        PathParam("verifierId", "The jwt verifier id"),
        BodyParam("The verifier to update", Ref("Patch"))
      ),
      goodResponse = GoodResponse(Ref("GlobalJwtVerifier"))
    ),
    "post" -> Operation(
      tag = "jwt-verifiers",
      summary = "Create one global JWT verifiers",
      description = "Create one global JWT verifiers",
      operationId = "createGlobalJwtVerifier",
      parameters = Json.arr(
        BodyParam("The verifier to create", Ref("GlobalJwtVerifier"))
      ),
      goodResponse = GoodResponse(Ref("GlobalJwtVerifier"))
    )
  )

  def AuthConfigs = Json.obj(
    "get" -> Operation(
      tag = "auth-config",
      summary = "Get all global auth. module configs",
      description = "Get all global auth. module configs",
      operationId = "findAllGlobalAuthModules",
      goodResponse = GoodResponse(
        ArrayOf(OneOf(Ref("LdapAuthModuleConfig"), Ref("InMemoryAuthModuleConfig"), Ref("GenericOauth2ModuleConfig")))
      )
    ),
    "get" -> Operation(
      tag = "auth-config",
      summary = "Get one global auth. module configs",
      description = "Get one global auth. module configs",
      operationId = "findGlobalAuthModuleById",
      parameters = Json.arr(
        PathParam("id", "The auth. config id")
      ),
      goodResponse = GoodResponse(
        OneOf(Ref("LdapAuthModuleConfig"), Ref("InMemoryAuthModuleConfig"), Ref("GenericOauth2ModuleConfig"))
      )
    ),
    "delete" -> Operation(
      tag = "auth-config",
      summary = "Delete one global auth. module config",
      description = "Delete one global auth. module config",
      operationId = "deleteGlobalAuthModule",
      parameters = Json.arr(
        PathParam("id", "The auth. config id id")
      ),
      goodResponse = GoodResponse(Ref("Deleted"))
    ),
    "put" -> Operation(
      tag = "auth-config",
      summary = "Update one global auth. module config",
      description = "Update one global auth. module config",
      operationId = "updateGlobalAuthModule",
      parameters = Json.arr(
        PathParam("id", "The auth. config id"),
        BodyParam("The auth. config to update",
                  OneOf(Ref("LdapAuthModuleConfig"), Ref("InMemoryAuthModuleConfig"), Ref("GenericOauth2ModuleConfig")))
      ),
      goodResponse = GoodResponse(
        OneOf(Ref("LdapAuthModuleConfig"), Ref("InMemoryAuthModuleConfig"), Ref("GenericOauth2ModuleConfig"))
      )
    ),
    "patch" -> Operation(
      tag = "auth-config",
      summary = "Update one global auth. module config",
      description = "Update one global auth. module config",
      operationId = "patchGlobalAuthModule",
      parameters = Json.arr(
        PathParam("id", "The auth. config id"),
        BodyParam("The auth. config to update", Ref("Patch"))
      ),
      goodResponse = GoodResponse(
        OneOf(Ref("LdapAuthModuleConfig"), Ref("InMemoryAuthModuleConfig"), Ref("GenericOauth2ModuleConfig"))
      )
    ),
    "post" -> Operation(
      tag = "auth-config",
      summary = "Create one global auth. module config",
      description = "Create one global auth. module config",
      operationId = "createGlobalAuthModule",
      parameters = Json.arr(
        BodyParam("The auth. config to create",
                  OneOf(Ref("LdapAuthModuleConfig"), Ref("InMemoryAuthModuleConfig"), Ref("GenericOauth2ModuleConfig")))
      ),
      goodResponse = GoodResponse(
        OneOf(Ref("LdapAuthModuleConfig"), Ref("InMemoryAuthModuleConfig"), Ref("GenericOauth2ModuleConfig"))
      )
    )
  )

  def Certificates = Json.obj(
    "get" -> Operation(
      tag = "certificates",
      summary = "Get all certificates",
      description = "Get all certificates",
      operationId = "allCerts",
      goodResponse = GoodResponse(ArrayOf(Ref("Certificate")))
    ),
    "get" -> Operation(
      tag = "certificates",
      summary = "Get one certificate by id",
      description = "Get one certificate by id",
      operationId = "oneCert",
      parameters = Json.arr(
        PathParam("id", "The auth. config id")
      ),
      goodResponse = GoodResponse(Ref("Certificate"))
    ),
    "delete" -> Operation(
      tag = "certificates",
      summary = "Delete one certificate by id",
      description = "Delete one certificate by id",
      operationId = "deleteCert",
      parameters = Json.arr(
        PathParam("id", "The certificate id")
      ),
      goodResponse = GoodResponse(Ref("Deleted"))
    ),
    "put" -> Operation(
      tag = "certificates",
      summary = "Update one certificate by id",
      description = "Update one certificate by id",
      operationId = "putCert",
      parameters = Json.arr(
        PathParam("id", "The certificate id"),
        BodyParam("The certificate to update", Ref("Certificate"))
      ),
      goodResponse = GoodResponse(Ref("Certificate"))
    ),
    "patch" -> Operation(
      tag = "certificates",
      summary = "Update one certificate by id",
      description = "Update one certificate by id",
      operationId = "patchCert",
      parameters = Json.arr(
        PathParam("id", "The certificate id"),
        BodyParam("The certificate to update", Ref("Patch"))
      ),
      goodResponse = GoodResponse(Ref("Certificate"))
    ),
    "post" -> Operation(
      tag = "certificates",
      summary = "Create one certificate",
      description = "Create one certificate",
      operationId = "createCert",
      parameters = Json.arr(
        BodyParam("The certificate to create", Ref("Certificate"))
      ),
      goodResponse = GoodResponse(Ref("Certificate"))
    )
  )

  def ValidationAuthoritiesApi = Json.obj(
    "get" -> Operation(
      tag = "validation-authorities",
      summary = "Get all validation authoritiess",
      description = "Get all validation authoritiess",
      operationId = "findAllClientValidators",
      goodResponse = GoodResponse(ArrayOf(Ref("ValidationAuthority")))
    ),
    "get" -> Operation(
      tag = "validation-authorities",
      summary = "Get one validation authorities by id",
      description = "Get one validation authorities by id",
      operationId = "findClientValidatorById",
      parameters = Json.arr(
        PathParam("id", "The auth. config id")
      ),
      goodResponse = GoodResponse(Ref("ValidationAuthority"))
    ),
    "delete" -> Operation(
      tag = "validation-authorities",
      summary = "Delete one validation authorities by id",
      description = "Delete one validation authorities by id",
      operationId = "deleteClientValidator",
      parameters = Json.arr(
        PathParam("id", "The validation authorities id")
      ),
      goodResponse = GoodResponse(Ref("Deleted"))
    ),
    "put" -> Operation(
      tag = "validation-authorities",
      summary = "Update one validation authorities by id",
      description = "Update one validation authorities by id",
      operationId = "updateClientValidator",
      parameters = Json.arr(
        PathParam("id", "The validation authorities id"),
        BodyParam("The validation authorities to update", Ref("ValidationAuthority"))
      ),
      goodResponse = GoodResponse(Ref("ValidationAuthority"))
    ),
    "patch" -> Operation(
      tag = "validation-authorities",
      summary = "Update one validation authorities by id",
      description = "Update one validation authorities by id",
      operationId = "patchClientValidator",
      parameters = Json.arr(
        PathParam("id", "The validation authorities id"),
        BodyParam("The validation authorities to update", Ref("Patch"))
      ),
      goodResponse = GoodResponse(Ref("ValidationAuthority"))
    ),
    "post" -> Operation(
      tag = "validation-authorities",
      summary = "Create one validation authorities",
      description = "Create one validation authorities",
      operationId = "createClientValidator",
      parameters = Json.arr(
        BodyParam("The validation authorities to create", Ref("ValidationAuthority"))
      ),
      goodResponse = GoodResponse(Ref("ValidationAuthority"))
    )
  )

  def ApiKeys = Json.obj(
    "get" -> Operation(
      tag = "apikeys",
      summary = "Get all api keys",
      description = "Get all api keys",
      operationId = "allApiKeys",
      goodResponse = GoodResponse(ArrayOf(Ref("ApiKey")))
    )
  )

  def GroupManagement = Json.obj(
    "get" -> Operation(
      tag = "groups",
      summary = "Get a service group",
      description = "Get a service group",
      operationId = "serviceGroup",
      parameters = Json.arr(
        PathParam("serviceGroupId", "The service group id")
      ),
      goodResponse = GoodResponse(Ref("Group"))
    ),
    "put" -> Operation(
      tag = "groups",
      summary = "Update a service group",
      description = "Update a service group",
      operationId = "updateGroup",
      parameters = Json.arr(
        PathParam("serviceGroupId", "The service group id"),
        BodyParam("The updated service group", Ref("Group"))
      ),
      goodResponse = GoodResponse(Ref("Group"))
    ),
    "patch" -> Operation(
      tag = "groups",
      summary = "Update a service group with a diff",
      description = "Update a service group with a diff",
      operationId = "patchGroup",
      parameters = Json.arr(
        PathParam("serviceGroupId", "The service group id"),
        BodyParam("The patch for the service group", Ref("Patch"))
      ),
      goodResponse = GoodResponse(Ref("Group"))
    ),
    "delete" -> Operation(
      tag = "groups",
      summary = "Delete a service group",
      description = "Delete a service group",
      operationId = "deleteGroup",
      parameters = Json.arr(
        PathParam("serviceGroupId", "The service group id")
      ),
      goodResponse = GoodResponse(Ref("Deleted"))
    )
  )
  def GroupsManagement = Json.obj(
    "get" -> Operation(
      tag = "groups",
      summary = "Get all service groups",
      description = "Get all service groups",
      operationId = "allServiceGroups",
      goodResponse = GoodResponse(ArrayOf(Ref("Group")))
    ),
    "post" -> Operation(
      tag = "groups",
      summary = "Create a new service group",
      description = "Create a new service group",
      operationId = "createGroup",
      parameters = Json.arr(
        BodyParam("The service group to create", Ref("Group"))
      ),
      goodResponse = GoodResponse(Ref("Group"))
    )
  )
  def SnowMonkeyConfigApi = Json.obj(
    "get" -> Operation(
      tag = "snowmonkey",
      summary = "Get current Snow Monkey config",
      description = "Get current Snow Monkey config",
      operationId = "getSnowMonkeyConfig",
      goodResponse = GoodResponse(Ref("SnowMonkeyConfig"))
    ),
    "put" -> Operation(
      tag = "snowmonkey",
      summary = "Update current Snow Monkey config",
      description = "Update current Snow Monkey config",
      operationId = "updateSnowMonkey",
      parameters = Json.arr(
        BodyParam("The service group to create", Ref("Group"))
      ),
      goodResponse = GoodResponse(Ref("SnowMonkeyConfig"))
    ),
    "patch" -> Operation(
      tag = "snowmonkey",
      summary = "Update current Snow Monkey config",
      description = "Update current Snow Monkey config",
      operationId = "patchSnowMonkey",
      parameters = Json.arr(
        BodyParam("The service group to create", Ref("Group"))
      ),
      goodResponse = GoodResponse(Ref("SnowMonkeyConfig"))
    )
  )
  def SnowMonkeyOutageApi = Json.obj(
    "get" -> Operation(
      tag = "snowmonkey",
      summary = "Get all current Snow Monkey ourages",
      description = "Get all current Snow Monkey ourages",
      operationId = "getSnowMonkeyOutages",
      goodResponse = GoodResponse(ArrayOf(Ref("Outage")))
    ),
    "delete" -> Operation(
      tag = "snowmonkey",
      summary = "Reset Snow Monkey Outages for the day",
      description = "Reset Snow Monkey Outages for the day",
      operationId = "resetSnowMonkey",
      goodResponse = GoodResponse(Ref("Done"))
    )
  )
  def SnowMonkeyStartApi = Json.obj(
    "post" -> Operation(
      tag = "snowmonkey",
      summary = "Start the Snow Monkey",
      description = "Start the Snow Monkey",
      operationId = "startSnowMonkey",
      goodResponse = GoodResponse(Ref("Done"))
    )
  )
  def SnowMonkeyStopApi = Json.obj(
    "post" -> Operation(
      tag = "snowmonkey",
      summary = "Stop the Snow Monkey",
      description = "Stop the Snow Monkey",
      operationId = "stopSnowMonkey",
      goodResponse = GoodResponse(Ref("Done"))
    )
  )
  def ServicesManagement = Json.obj(
    "get" -> Operation(
      tag = "services",
      summary = "Get all services",
      description = "Get all services",
      operationId = "allServices",
      goodResponse = GoodResponse(ArrayOf(Ref("Service")))
    ),
    "post" -> Operation(
      tag = "services",
      summary = "Create a new service descriptor",
      description = "Create a new service descriptor",
      operationId = "createService",
      parameters = Json.arr(
        BodyParam("The service descriptor to create", Ref("Service"))
      ),
      goodResponse = GoodResponse(Ref("Service"))
    )
  )

  def ServicesForGroup = Json.obj(
    "get" -> Operation(
      tag = "services",
      summary = "Get all services descriptor for a group",
      description = "Get all services descriptor for a group",
      operationId = "serviceGroupServices",
      parameters = Json.arr(
        PathParam("serviceGroupId", "The service group id")
      ),
      goodResponse = GoodResponse(ArrayOf(Ref("ApiKey")))
    )
  )

  def ServiceManagement = Json.obj(
    "get" -> Operation(
      tag = "services",
      summary = "Get a service descriptor",
      description = "Get a service descriptor",
      operationId = "service",
      parameters = Json.arr(
        PathParam("serviceId", "The service id")
      ),
      goodResponse = GoodResponse(Ref("Service"))
    ),
    "put" -> Operation(
      tag = "services",
      summary = "Update a service descriptor",
      description = "Update a service descriptor",
      operationId = "updateService",
      parameters = Json.arr(
        PathParam("serviceId", "The service id"),
        BodyParam("The updated service descriptor", Ref("Service"))
      ),
      goodResponse = GoodResponse(Ref("Service"))
    ),
    "patch" -> Operation(
      tag = "services",
      summary = "Update a service descriptor with a diff",
      description = "Update a service descriptor with a diff",
      operationId = "patchService",
      parameters = Json.arr(
        PathParam("serviceId", "The service id"),
        BodyParam("The patch for the service", Ref("Patch"))
      ),
      goodResponse = GoodResponse(Ref("Service"))
    ),
    "delete" -> Operation(
      tag = "services",
      summary = "Delete a service descriptor",
      description = "Delete a service descriptor",
      operationId = "deleteService",
      parameters = Json.arr(
        PathParam("serviceId", "The service id")
      ),
      goodResponse = GoodResponse(Ref("Deleted"))
    )
  )

  def ImportExportJson = Json.obj(
    "get" -> Operation(
      tag = "import",
      summary = "Export the full state of Otoroshi",
      description = "Export the full state of Otoroshi",
      operationId = "fullExport",
      goodResponse = GoodResponse(Ref("ImportExport"))
    ),
    "post" -> Operation(
      tag = "import",
      summary = "Import the full state of Otoroshi",
      description = "Import the full state of Otoroshi",
      operationId = "fullImport",
      parameters = Json.arr(
        BodyParam("The full export", Ref("ImportExport"))
      ),
      goodResponse = GoodResponse(Ref("Done"))
    )
  )

  def GlobalConfigManagement = Json.obj(
    "get" -> Operation(
      tag = "configuration",
      summary = "Get the full configuration of Otoroshi",
      description = "Get the full configuration of Otoroshi",
      operationId = "globalConfig",
      goodResponse = GoodResponse(Ref("GlobalConfig"))
    ),
    "put" -> Operation(
      tag = "configuration",
      summary = "Update the global configuration",
      description = "Update the global configuration",
      operationId = "putGlobalConfig",
      parameters = Json.arr(
        BodyParam("The updated global config", Ref("GlobalConfig"))
      ),
      goodResponse = GoodResponse(Ref("GlobalConfig"))
    ),
    "patch" -> Operation(
      tag = "configuration",
      summary = "Update the global configuration with a diff",
      description = "Update the global configuration with a diff",
      operationId = "patchGlobalConfig",
      parameters = Json.arr(
        BodyParam("The updated global config as patch", Ref("Patch"))
      ),
      goodResponse = GoodResponse(Ref("GlobalConfig"))
    )
  )

  def ImportFromFile = Json.obj(
    "post" -> Operation(
      tag = "import",
      summary = "Import the full state of Otoroshi as a file",
      description = "Import the full state of Otoroshi as a file",
      operationId = "fullImportFromFile",
      parameters = Json.arr(
        BodyParam("The full export", Ref("ImportExport"))
      ),
      goodResponse = GoodResponse(Ref("Done"))
    )
  )

  def CheckOtoroshiHealth = Json.obj(
    "get" -> NoAuthOperation(
      tag = "health",
      summary = "Return current Otoroshi health",
      description = "Import the full state of Otoroshi as a file",
      operationId = "health",
      goodResponse = GoodResponse(Ref("OtoroshiHealth"))
    )
  )

  def ServiceTargetsManagement = Json.obj(
    "get" -> Operation(
      tag = "services",
      summary = "Get a service descriptor targets",
      description = "Get a service descriptor targets",
      operationId = "serviceTargets",
      parameters = Json.arr(
        PathParam("serviceId", "The service id")
      ),
      goodResponse = GoodResponse(ArrayOf(Ref("Target")))
    ),
    "post" -> Operation(
      tag = "services",
      summary = "Add a target to a service descriptor",
      description = "Add a target to a service descriptor",
      operationId = "serviceAddTarget",
      parameters = Json.arr(
        PathParam("serviceId", "The service id"),
        BodyParam("The updated service descriptor", Ref("Target"))
      ),
      goodResponse = GoodResponse(ArrayOf(Ref("Target")))
    ),
    "patch" -> Operation(
      tag = "services",
      summary = "Update a service descriptor targets",
      description = "Update a service descriptor targets",
      operationId = "updateServiceTargets",
      parameters = Json.arr(
        PathParam("serviceId", "The service id"),
        BodyParam("The patch for the service targets", Ref("Patch"))
      ),
      goodResponse = GoodResponse(ArrayOf(Ref("Target")))
    ),
    "delete" -> Operation(
      tag = "services",
      summary = "Delete a service descriptor target",
      description = "Delete a service descriptor target",
      operationId = "serviceDeleteTarget",
      parameters = Json.arr(
        PathParam("serviceId", "The service id")
      ),
      goodResponse = GoodResponse(ArrayOf(Ref("Target")))
    )
  )

  def ServiceTemplatesManagement = Json.obj(
    "get" -> Operation(
      tag = "services",
      summary = "Get a service descriptor error template",
      description = "Get a service descriptor error template",
      operationId = "serviceTemplate",
      parameters = Json.arr(
        PathParam("serviceId", "The service id")
      ),
      goodResponse = GoodResponse(Ref("ErrorTemplate"))
    ),
    "put" -> Operation(
      tag = "services",
      summary = "Update an error template to a service descriptor",
      description = "Update an error template to a service descriptor",
      operationId = "updateServiceTemplate",
      parameters = Json.arr(
        PathParam("serviceId", "The service id"),
        BodyParam("The updated service descriptor template", Ref("ErrorTemplate"))
      ),
      goodResponse = GoodResponse(Ref("ErrorTemplate"))
    ),
    "post" -> Operation(
      tag = "services",
      summary = "Create a service descriptor error template",
      description = "Update a service descriptor targets",
      operationId = "createServiceTemplate",
      parameters = Json.arr(
        PathParam("serviceId", "The service id"),
        BodyParam("The patch for the service error template", Ref("ErrorTemplate"))
      ),
      goodResponse = GoodResponse(Ref("ErrorTemplate"))
    ),
    "delete" -> Operation(
      tag = "services",
      summary = "Delete a service descriptor error template",
      description = "Delete a service descriptor error template",
      operationId = "deleteServiceTemplate",
      parameters = Json.arr(
        PathParam("serviceId", "The service id")
      ),
      goodResponse = GoodResponse(Ref("Deleted"))
    )
  )

  def ScriptApi = Json.obj(
    "get" -> Operation(
      tag = "scripts",
      summary = "Get a script",
      description = "Get a script",
      operationId = "findScriptById",
      parameters = Json.arr(
        PathParam("scriptId", "The script id")
      ),
      goodResponse = GoodResponse(Ref("Script"))
    ),
    "put" -> Operation(
      tag = "scripts",
      summary = "Update a script",
      description = "Update a script",
      operationId = "updateScript",
      parameters = Json.arr(
        PathParam("scriptId", "The script id"),
        BodyParam("The updated script", Ref("Script"))
      ),
      goodResponse = GoodResponse(Ref("Script"))
    ),
    "patch" -> Operation(
      tag = "scripts",
      summary = "Update a script with a diff",
      description = "Update a script with a diff",
      operationId = "patchScript",
      parameters = Json.arr(
        PathParam("scriptId", "The script id"),
        BodyParam("The patch for the script", Ref("Patch"))
      ),
      goodResponse = GoodResponse(Ref("Script"))
    ),
    "delete" -> Operation(
      tag = "scripts",
      summary = "Delete a script",
      description = "Delete a script",
      operationId = "deleteScript",
      parameters = Json.arr(
        PathParam("scriptId", "The script id")
      ),
      goodResponse = GoodResponse(Ref("Deleted"))
    )
  )

  def ScriptsApi = Json.obj(
    "get" -> Operation(
      tag = "scripts",
      summary = "Get all scripts",
      description = "Get all scripts",
      operationId = "findAllScripts",
      goodResponse = GoodResponse(ArrayOf(Ref("Script")))
    ),
    "post" -> Operation(
      tag = "scripts",
      summary = "Create a new script",
      description = "Create a new script",
      operationId = "createScript",
      parameters = Json.arr(
        BodyParam("The script to create", Ref("Script"))
      ),
      goodResponse = GoodResponse(Ref("Script"))
    )
  )

  def ScriptCompilationApi = Json.obj(
    "post" -> Operation(
      tag = "scripts",
      summary = "Compile a script",
      description = "Compile a script",
      operationId = "compileScript",
      parameters = Json.arr(
        BodyParam("The script to compile", Ref("Script"))
      ),
      goodResponse = GoodResponse(Ref("ScriptCompilationResult"))
    )
  )

  ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  ///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

  // Swagger definition

  def swaggerDescriptor(): JsValue = {
    Json.obj(
      "swagger" -> "2.0",
      "info" -> Json.obj(
        "version"     -> "1.4.7",
        "title"       -> "Otoroshi Admin API",
        "description" -> "Admin API of the Otoroshi reverse proxy",
        "contact" -> Json.obj(
          "name"  -> "Otoroshi Team",
          "email" -> "oss@maif.fr"
        ),
        "license" -> Json.obj(
          "name" -> "Apache 2.0",
          "url"  -> "http://www.apache.org/licenses/LICENSE-2.0.html"
        )
      ),
      "tags" -> Json.arr(
        Tag("configuration", "Everything about Otoroshi global configuration"),
        Tag("import", "Everything about Otoroshi import/export"),
        Tag("templates", "Everything about Otoroshi entities templates"),
        Tag("environments", "Everything about Otoroshi Environments"),
        Tag("groups", "Everything about Otoroshi service groups"),
        Tag("apikeys", "Everything about Otoroshi api keys"),
        Tag("services", "Everything about Otoroshi service descriptors"),
        Tag("stats", "Everything about Otoroshi stats"),
        Tag("snowmonkey", "Everything about Otoroshi Snow Monkey"),
        Tag("health", "Everything about Otoroshi health status"),
        Tag("jwt-verifiers", "Everything about Otoroshi global JWT token verifiers"),
        Tag("auth-config", "Everything about Otoroshi global auth. module config"),
        Tag("script", "Everything about Otoroshi request transformer scripts"),
        Tag("certificates", "Everything about Otoroshi SSL/TLS certificates"),
        Tag("validation-authorities", "Everything about Otoroshi validation authorities")
      ),
      "externalDocs" -> Json.obj(
        "description" -> "Find out more about Otoroshi",
        "url"         -> "https://maif.github.io/otoroshi/"
      ),
      "host"     -> env.adminApiExposedHost,
      "basePath" -> "/api",
      "schemes"  -> Json.arr(env.exposedRootScheme),
      "paths" -> Json.obj(
        "/new/apikey"                                         -> NewApiKey,
        "/new/service"                                        -> NewService,
        "/new/group"                                          -> NewGroup,
        "/lines"                                              -> AllLines,
        "/lines/{line}/services"                              -> ServicesForLine,
        "/api/services/{serviceId}/apikeys/{clientId}/quotas" -> QuotasOfTheApiKeyOfAService,
        "/api/services/{serviceId}/apikeys/{clientId}/group"  -> GroupForApiKey,
        "/api/services/{serviceId}/apikeys/{clientId}"        -> ApiKeyManagementForService,
        "/api/services/{serviceId}/apikeys"                   -> ApiKeysManagementForService,
        "/api/groups/{groupId}/apikeys/{clientId}/quotas"     -> QuotasOfTheApiKeyOfAGroup,
        "/api/groups/{groupId}/apikeys/{clientId}"            -> ApiKeyManagementForGroup,
        "/api/groups/{groupId}/apikeys"                       -> ApiKeysManagementForGroup,
        "/api/apikeys"                                        -> ApiKeys,
        "/api/services/{serviceId}/template"                  -> ServiceTemplatesManagement,
        "/api/services/{serviceId}/targets"                   -> ServiceTargetsManagement,
        "/api/services/{serviceId}"                           -> ServiceManagement,
        "/api/services"                                       -> ServicesManagement,
        "/api/groups/{serviceGroupId}/services"               -> ServicesForGroup,
        "/api/groups/{serviceGroupId}"                        -> GroupManagement,
        "/api/groups"                                         -> GroupsManagement,
        "/api/verifiers"                                      -> JWTVerifiers,
        "/api/auths"                                          -> AuthConfigs,
        "/api/scripts/_compile"                               -> ScriptCompilationApi,
        "/api/scripts/{scriptId}"                             -> ScriptApi,
        "/api/scripts"                                        -> ScriptsApi,
        "/api/certificates"                                   -> Certificates,
        "/api/client-validators"                              -> ValidationAuthoritiesApi,
        "/api/snowmonkey/config"                              -> SnowMonkeyConfigApi,
        "/api/snowmonkey/outages"                             -> SnowMonkeyOutageApi,
        "/api/snowmonkey/_start"                              -> SnowMonkeyStartApi,
        "/api/snowmonkey/_stop"                               -> SnowMonkeyStopApi,
        "/api/live/{id}" -> Json.obj(
          "get" -> Operation(
            tag = "stats",
            summary = "Get live feed of otoroshi stats",
            description = "Get live feed of global otoroshi stats (global) or for a service {id}",
            operationId = "serviceLiveStats",
            produces = Json.arr("application/json", "text/event-stream"),
            parameters = Json.arr(
              PathParam("id", "The service id or global for otoroshi stats")
            ),
            goodResponse = Json.obj(
              "description" -> "Successful operation",
              "schema"      -> Ref("Stats")
            )
          )
        ),
        "/api/live" -> Json.obj(
          "get" -> Operation(
            tag = "stats",
            summary = "Get global otoroshi stats",
            description = "Get global otoroshi stats",
            operationId = "globalLiveStats",
            goodResponse = GoodResponse(Ref("Stats"))
          )
        ),
        "/api/globalconfig"  -> GlobalConfigManagement,
        "/api/otoroshi.json" -> ImportExportJson,
        "/api/import"        -> ImportFromFile,
        "/health"            -> CheckOtoroshiHealth
      ),
      "securityDefinitions" -> Json.obj(
        "otoroshi_auth" -> Json.obj(
          "type" -> "basic"
        )
      ),
      "definitions" -> Json.obj(
        "ApiKey"         -> ApiKey,
        "Auth0Config"    -> Auth0Config,
        "Canary"         -> Canary,
        "CleverSettings" -> CleverSettings,
        "ClientConfig"   -> ClientConfig,
        "Deleted"        -> Deleted,
        "Done"           -> Done,
        "Environment" -> Json.obj(
          "type"        -> "string",
          "example"     -> "prod",
          "description" -> "The name of the environment for service descriptors"
        ),
        "ErrorTemplate"               -> ErrorTemplate,
        "ExposedApi"                  -> ExposedApi,
        "GlobalConfig"                -> GlobalConfig,
        "Group"                       -> Group,
        "HealthCheck"                 -> HealthCheck,
        "OtoroshiHealth"              -> OtoroshiHealth,
        "ImportExport"                -> ImportExport,
        "ImportExportStats"           -> ImportExportStats,
        "IpFiltering"                 -> IpFiltering,
        "MailgunSettings"             -> MailgunSettings,
        "Patch"                       -> Patch,
        "Quotas"                      -> Quotas,
        "Service"                     -> Service,
        "SimpleAdmin"                 -> SimpleAdmin,
        "Stats"                       -> Stats,
        "StatsdConfig"                -> StatsdConfig,
        "Target"                      -> Target,
        "U2FAdmin"                    -> U2FAdmin,
        "Webhook"                     -> Webhook,
        "BadResponse"                 -> BadResponse,
        "LargeRequestFaultConfig"     -> LargeRequestFaultConfig,
        "LargeResponseFaultConfig"    -> LargeResponseFaultConfig,
        "LatencyInjectionFaultConfig" -> LatencyInjectionFaultConfig,
        "BadResponsesFaultConfig"     -> BadResponsesFaultConfig,
        "ChaosConfig"                 -> ChaosConfig,
        "OutageStrategy"              -> OutageStrategy,
        "SnowMonkeyConfig"            -> SnowMonkeyConfig,
        "OutageStrategy"              -> OutageStrategy,
        "Outage"                      -> Outage,
        "RefJwtVerifier"              -> RefJwtVerifier,
        "LocalJwtVerifier"            -> LocalJwtVerifier,
        "InQueryParam"                -> InQueryParam,
        "InHeader"                    -> InHeader,
        "InCookie"                    -> InCookie,
        "HSAlgoSettings"              -> HSAlgoSettings,
        "RSAlgoSettings"              -> RSAlgoSettings,
        "ESAlgoSettings"              -> ESAlgoSettings,
        "JWKSAlgoSettings"            -> JWKSAlgoSettings,
        "MappingSettings"             -> MappingSettings,
        "TransformSettings"           -> TransformSettings,
        "VerificationSettings"        -> VerificationSettings,
        "PassThrough"                 -> PassThrough,
        "Sign"                        -> Sign,
        "Transform"                   -> Transform,
        "GlobalJwtVerifier"           -> GlobalJwtVerifier,
        "GenericOauth2ModuleConfig"   -> GenericOauth2ModuleConfig,
        "InMemoryAuthModuleConfig"    -> InMemoryAuthModuleConfig,
        "LdapAuthModuleConfig"        -> LdapAuthModuleConfig,
        "CorsSettings"                -> CorsSettings,
        "RedirectionSettings"         -> RedirectionSettings,
        "InMemoryUser"                -> InMemoryUser,
        "LdapUser"                    -> LdapUser,
        "Gzip"                        -> Gzip,
        "Script"                      -> Script,
        "ScriptCompilationResult"     -> ScriptCompilationResult,
        "ScriptCompilationError"      -> ScriptCompilationError,
        "Certificate"                 -> Certificate,
        "ValidationAuthority"         -> ValidationAuthority
      )
    )
  }
}
