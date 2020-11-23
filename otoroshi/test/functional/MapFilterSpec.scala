package functional

import org.scalatest.{MustMatchers, OptionValues, WordSpec}
import play.api.libs.json.{JsNumber, JsObject, Json}

class MapFilterSpec extends WordSpec with MustMatchers with OptionValues {

  val source = Json.parse(
    """
      |{
      |  "foo": "bar",
      |  "type": "AlertEvent",
      |  "alert": "big-alert",
      |  "status": 200,
      |  "codes": ["a", "b"],
      |  "inner": {
      |    "foo": "bar",
      |    "bar": "foo"
      |  }
      |}
      |""".stripMargin)

  "Match and Project utils" should {
    "match objects" in {
      otoroshi.utils.Match.matches(source, Json.obj("foo"    -> "bar")) mustBe true
      otoroshi.utils.Match.matches(source, Json.obj("foo"    -> "baz")) mustBe false
      otoroshi.utils.Match.matches(source, Json.obj("foo"    -> "bar", "type" -> Json.obj("$wildcard" -> "Alert*"))) mustBe true
      otoroshi.utils.Match.matches(source, Json.obj("foo"    -> "bar", "type" -> Json.obj("$wildcard" -> "Foo*"))) mustBe false
      otoroshi.utils.Match.matches(source, Json.obj("foo"    -> "bar", "inner" -> Json.obj("foo" -> "bar"), "type" -> Json.obj("$wildcard" -> "Alert*"))) mustBe true
      otoroshi.utils.Match.matches(source, Json.obj("foo"    -> "bar", "inner" -> Json.obj("foo" -> "baz"), "type" -> Json.obj("$wildcard" -> "Alert*"))) mustBe false
      otoroshi.utils.Match.matches(source, Json.obj("status" -> 200)) mustBe true
      otoroshi.utils.Match.matches(source, Json.obj("status" -> 201)) mustBe false
      otoroshi.utils.Match.matches(source, Json.obj("status" -> Json.obj("$gt" -> 100))) mustBe true
      otoroshi.utils.Match.matches(source, Json.obj("status" -> Json.obj("$gt" -> 200))) mustBe false
      otoroshi.utils.Match.matches(source, Json.obj("status" -> Json.obj("$gte" -> 200))) mustBe true
      otoroshi.utils.Match.matches(source, Json.obj("status" -> Json.obj("$lt" -> 201))) mustBe true
      otoroshi.utils.Match.matches(source, Json.obj("status" -> Json.obj("$lt" -> 200))) mustBe false
      otoroshi.utils.Match.matches(source, Json.obj("status" -> Json.obj("$lte" -> 200))) mustBe true
      otoroshi.utils.Match.matches(source, Json.obj("status" -> Json.obj("$between" -> Json.obj("min" -> 100, "max" -> 300)))) mustBe true
      otoroshi.utils.Match.matches(source, Json.obj("inner"  -> Json.obj("$and" -> Json.arr(Json.obj("foo" -> "bar"), Json.obj("bar" -> "foo" ))))) mustBe true
      otoroshi.utils.Match.matches(source, Json.obj("inner"  -> Json.obj("$and" -> Json.arr(Json.obj("foo" -> "bar"), Json.obj("bar" -> "fooo" ))))) mustBe false
      otoroshi.utils.Match.matches(source, Json.obj("inner"  -> Json.obj("$or" -> Json.arr(Json.obj("foo" -> "bar"), Json.obj("bar" -> "fooo" ))))) mustBe true
      otoroshi.utils.Match.matches(source, Json.obj("status" -> Json.obj("$or" -> Json.arr(JsNumber(200), JsNumber(201))))) mustBe true
      otoroshi.utils.Match.matches(source, Json.obj("status" -> Json.obj("$or" -> Json.arr(JsNumber(202), JsNumber(201))))) mustBe false
      otoroshi.utils.Match.matches(source, Json.obj("codes" -> Json.arr("a", "b"))) mustBe true
      otoroshi.utils.Match.matches(source, Json.obj("codes" -> Json.obj("$contains" -> "a"))) mustBe true
      otoroshi.utils.Match.matches(source, Json.obj("codes" -> Json.obj("$all" -> Json.arr("a", "b")))) mustBe true
      otoroshi.utils.Match.matches(source, Json.obj("codes" -> Json.obj("$all" -> Json.arr("a", "b", "c")))) mustBe false
    }
    "project objects" in {
      otoroshi.utils.Project.project(source, Json.obj("foo"  -> true, "status" -> true)) mustBe Json.obj("foo" -> "bar", "status" -> 200)
      otoroshi.utils.Project.project(source, Json.obj("foo"  -> true, "inner" -> true)) mustBe Json.obj("foo" -> "bar", "inner" -> Json.obj("foo" -> "bar", "bar" -> "foo"))
      otoroshi.utils.Project.project(source, Json.obj("foo"  -> true, "inner" -> Json.obj("foo" -> true))) mustBe Json.obj("foo" -> "bar", "inner" -> Json.obj("foo" -> "bar"))
    }
    "work on actual otoroshi event" in {
      val source = Json.parse(
        """
          |{
          |    "method": "GET",
          |    "instance-name": "otoroshi",
          |    "instance-zone": "local",
          |    "callAttempts": 1,
          |    "err": false,
          |    "headers": [
          |      {
          |        "value": "build-product.domain.io",
          |        "key": "Host"
          |      },
          |      {
          |        "value": "image/webp,*/*",
          |        "key": "Accept"
          |      },
          |      {
          |        "value": "token=xxx",
          |        "key": "Cookie"
          |      },
          |      {
          |        "value": "https://build-product.domain.io",
          |        "key": "Referer"
          |      },
          |      {
          |        "value": "xxxxxxx-xxxx-xxxx-xxxx-xxxx",
          |        "key": "Sozu-Id"
          |      },
          |      {
          |        "value": "proto=https;for=127.0.0.1:56325;by=127.0.0.1",
          |        "key": "Forwarded"
          |      },
          |      {
          |        "value": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:78.0) Gecko/20100101 Firefox/78.0",
          |        "key": "User-Agent"
          |      },
          |      {
          |        "value": "127.0.0.1:60788",
          |        "key": "Remote-Address"
          |      },
          |      {
          |        "value": "<function1>",
          |        "key": "Timeout-Access"
          |      },
          |      {
          |        "value": "gzip, deflate, br",
          |        "key": "Accept-Encoding"
          |      },
          |      {
          |        "value": "fr,fr-FR;q=0.8,en-US;q=0.5,en;q=0.3",
          |        "key": "Accept-Language"
          |      },
          |      {
          |        "value": "/favicon.ico",
          |        "key": "Raw-Request-URI"
          |      },
          |      {
          |        "value": "127.0.0.1",
          |        "key": "X-Forwarded-For"
          |      },
          |      {
          |        "value": "Session(000000000000000000|SSL_NULL_WITH_NULL_NULL)",
          |        "key": "Tls-Session-Info"
          |      },
          |      {
          |        "value": "443",
          |        "key": "X-Forwarded-Port"
          |      },
          |      {
          |        "value": "https",
          |        "key": "X-Forwarded-Proto"
          |      },
          |      {
          |        "value": "85671B",
          |        "key": "x-product-userid"
          |      },
          |      {
          |        "value": "1.0.0",
          |        "key": "x-productclient-version"
          |      },
          |      {
          |        "value": "1.0.0",
          |        "key": "x-productfrontend-version"
          |      }
          |    ],
          |    "viz": {
          |      "fromTo": "internet###product-core-api",
          |      "from": "internet",
          |      "fromLbl": "internet",
          |      "toLbl": "product-core-api",
          |      "to": "service-id"
          |    },
          |    "userAgentInfo": null,
          |    "headersOut": [
          |      {
          |        "value": "Access-Control-Request-Headers",
          |        "key": "Vary"
          |      },
          |      {
          |        "value": "Fri, 25 Sep 2020 12:26:04 GMT",
          |        "key": "Last-Modified"
          |      },
          |      {
          |        "value": "bytes",
          |        "key": "Accept-Ranges"
          |      },
          |      {
          |        "value": "xxxxxxx-xxxx-xxxx-xxxx-xxxx",
          |        "key": "Sozu-Id"
          |      },
          |      {
          |        "value": "DENY",
          |        "key": "X-Frame-Options"
          |      },
          |      {
          |        "value": "1; mode=block",
          |        "key": "X-XSS-Protection"
          |      },
          |      {
          |        "value": "max-age=172800",
          |        "key": "Cache-Control"
          |      },
          |      {
          |        "value": "nosniff",
          |        "key": "X-Content-Type-Options"
          |      },
          |      {
          |        "value": "image/png",
          |        "key": "Content-Type"
          |      },
          |      {
          |        "value": "Wed, 30 Sep 2020 06:58:07 GMT",
          |        "key": "Date"
          |      }
          |    ],
          |    "@created": 1601449087433,
          |    "instance-provider": "local",
          |    "from": "127.0.0.1",
          |    "data": {
          |      "dataOut": 947,
          |      "dataIn": 0
          |    },
          |    "overheadWoCb": 123,
          |    "responseChunked": false,
          |    "instance-region": "local",
          |    "@env": "prod",
          |    "@product": "product",
          |    "instance-rack": "local",
          |    "url": "https://backend.domain.io/favicon.ico",
          |    "descriptor": {
          |      "forceHttps": true,
          |      "useNewWSClient": false,
          |      "headersVerification": {},
          |      "enabled": true,
          |      "root": "/",
          |      "targets": [
          |        {
          |          "scheme": "https",
          |          "host": "backend.domain.io",
          |          "ipAddress": null,
          |          "mtlsConfig": {
          |            "trustAll": false,
          |            "mtls": false,
          |            "certs": [],
          |            "trustedCerts": [],
          |            "loose": false
          |          },
          |          "predicate": {
          |            "type": "AlwaysMatch"
          |          },
          |          "weight": 1,
          |          "protocol": "HTTP/1.1"
          |        }
          |      ],
          |      "readOnly": false,
          |      "chaosConfig": {
          |        "enabled": false,
          |        "largeResponseFaultConfig": {
          |          "additionalResponseSize": 0,
          |          "ratio": 0.2
          |        },
          |        "latencyInjectionFaultConfig": {
          |          "from": 0,
          |          "ratio": 0.2,
          |          "to": 0
          |        },
          |        "largeRequestFaultConfig": {
          |          "additionalRequestSize": 0,
          |          "ratio": 0.2
          |        },
          |        "badResponsesFaultConfig": {
          |          "ratio": 0.2,
          |          "responses": []
          |        }
          |      },
          |      "ipFiltering": {
          |        "whitelist": [],
          |        "blacklist": []
          |      },
          |      "preRouting": {
          |        "refs": [],
          |        "enabled": false,
          |        "config": {},
          |        "excludedPatterns": []
          |      },
          |      "sendInfoToken": true,
          |      "thirdPartyApiKey": {
          |        "enabled": false,
          |        "rolesPath": [],
          |        "mode": "Tmp",
          |        "quotasEnabled": true,
          |        "monthlyQuota": 10000000,
          |        "ttl": 0,
          |        "excludedPatterns": [],
          |        "throttlingQuota": 100,
          |        "localVerificationOnly": false,
          |        "uniqueApiKey": false,
          |        "oidcConfigRef": null,
          |        "headerName": "Authorization",
          |        "type": "OIDC",
          |        "roles": [],
          |        "scopes": [],
          |        "dailyQuota": 10000000
          |      },
          |      "logAnalyticsOnServer": false,
          |      "domain": "domain.io",
          |      "subdomain": "build-product",
          |      "userFacing": false,
          |      "privatePatterns": [],
          |      "restrictions": {
          |        "enabled": false,
          |        "allowed": [],
          |        "notFound": [],
          |        "forbidden": [],
          |        "allowLast": true
          |      },
          |      "removeHeadersIn": [],
          |      "secComVersion": 1,
          |      "matchingHeaders": {},
          |      "authConfigRef": null,
          |      "name": "product-core-api",
          |      "hosts": [],
          |      "healthCheck": {
          |        "enabled": false,
          |        "url": "/"
          |      },
          |      "publicPatterns": [
          |        "/.*"
          |      ],
          |      "removeHeadersOut": [],
          |      "redirection": {
          |        "enabled": false,
          |        "code": 303,
          |        "to": "https://www.otoroshi.io"
          |      },
          |      "clientValidatorRef": null,
          |      "env": "prod",
          |      "letsEncrypt": false,
          |      "securityExcludedPatterns": [],
          |      "strictlyPrivate": false,
          |      "clientConfig": {
          |        "connectionTimeout": 10000,
          |        "callTimeout": 30000,
          |        "customTimeouts": [],
          |        "retries": 1,
          |        "retryInitialDelay": 50,
          |        "callAndStreamTimeout": 120000,
          |        "useCircuitBreaker": true,
          |        "idleTimeout": 60000,
          |        "proxy": {},
          |        "sampleInterval": 2000,
          |        "maxErrors": 20,
          |        "backoffFactor": 2,
          |        "globalTimeout": 30000
          |      },
          |      "cors": {
          |        "enabled": false,
          |        "exposeHeaders": [],
          |        "allowMethods": [],
          |        "excludedPatterns": [],
          |        "allowOrigin": "*",
          |        "allowCredentials": true,
          |        "maxAge": null,
          |        "allowHeaders": []
          |      },
          |      "paths": [],
          |      "accessValidator": {
          |        "refs": [],
          |        "enabled": false,
          |        "config": {},
          |        "excludedPatterns": []
          |      },
          |      "additionalHeadersOut": {},
          |      "metadata": {
          |        "env": "preprod",
          |        "product": "product",
          |        "service": "core-api"
          |      },
          |      "localScheme": "http",
          |      "allowHttp10": true,
          |      "detectApiKeySooner": false,
          |      "targetsLoadBalancing": {
          |        "type": "RoundRobin"
          |      },
          |      "buildMode": false,
          |      "apiKeyConstraints": {
          |        "routing": {
          |          "noneMetaIn": {},
          |          "noneTagIn": [],
          |          "allMetaIn": {},
          |          "oneMetaIn": {},
          |          "oneTagIn": [],
          |          "allTagsIn": []
          |        },
          |        "clientIdAuth": {
          |          "enabled": true,
          |          "queryName": null,
          |          "headerName": null
          |        },
          |        "jwtAuth": {
          |          "enabled": true,
          |          "maxJwtLifespanSecs": null,
          |          "cookieName": null,
          |          "queryName": null,
          |          "headerName": null,
          |          "includeRequestAttributes": false
          |        },
          |        "basicAuth": {
          |          "enabled": true,
          |          "queryName": null,
          |          "headerName": null
          |        },
          |        "customHeadersAuth": {
          |          "enabled": true,
          |          "clientSecretHeaderName": null,
          |          "clientIdHeaderName": null
          |        }
          |      },
          |      "localHost": "localhost:8080",
          |      "secComSettings": {
          |        "size": 512,
          |        "base64": false,
          |        "secret": "${config.app.claim.sharedKey}",
          |        "type": "HSAlgoSettings"
          |      },
          |      "redirectToLocal": false,
          |      "overrideHost": true,
          |      "secComHeaders": {
          |        "stateRequestName": null,
          |        "stateResponseName": null,
          |        "claimRequestName": null
          |      },
          |      "maintenanceMode": false,
          |      "canary": {
          |        "enabled": false,
          |        "root": "/",
          |        "targets": [],
          |        "traffic": 0.2
          |      },
          |      "sendOtoroshiHeadersBack": false,
          |      "missingOnlyHeadersIn": {},
          |      "transformerRefs": [
          |        "xxxxxxxx"
          |      ],
          |      "sendStateChallenge": true,
          |      "transformerRef": "xxxxxxxx",
          |      "gzip": {
          |        "bufferSize": 8192,
          |        "enabled": false,
          |        "whiteList": [
          |          "text/*",
          |          "application/javascript",
          |          "application/json"
          |        ],
          |        "excludedPatterns": [],
          |        "blackList": [],
          |        "compressionLevel": 5,
          |        "chunkedThreshold": 102400
          |      },
          |      "xForwardedHeaders": false,
          |      "missingOnlyHeadersOut": {},
          |      "api": {
          |        "exposeApi": false
          |      },
          |      "tcpUdpTunneling": false,
          |      "useAkkaHttpClient": false,
          |      "groupId": "group-1",
          |      "id": "service-id",
          |      "transformerConfig": {},
          |      "issueCertCA": null,
          |      "secComTtl": 30000,
          |      "jwtVerifier": {
          |        "enabled": false,
          |        "excludedPatterns": [],
          |        "ids": [],
          |        "type": "ref",
          |        "id": null
          |      },
          |      "secComExcludedPatterns": [],
          |      "enforceSecureCommunication": true,
          |      "issueCert": false,
          |      "stripPath": true,
          |      "secComInfoTokenVersion": "Legacy",
          |      "privateApp": false,
          |      "additionalHeaders": {},
          |      "matchingRoot": null
          |    },
          |    "parentReqId": null,
          |    "@serviceId": "service-id",
          |    "target": {
          |      "scheme": "https",
          |      "host": "backend.domain.io",
          |      "uri": "/favicon.ico"
          |    },
          |    "remainingQuotas": {
          |      "remainingCallsPerMonth": 10000000,
          |      "authorizedCallsPerSec": 10000000,
          |      "currentCallsPerSec": 10000000,
          |      "remainingCallsPerSec": 10000000,
          |      "authorizedCallsPerDay": 10000000,
          |      "currentCallsPerDay": 10000000,
          |      "authorizedCallsPerMonth": 10000000,
          |      "remainingCallsPerDay": 10000000,
          |      "currentCallsPerMonth": 10000000
          |    },
          |    "@callAt": 1601449087303,
          |    "clientCertChain": [],
          |    "status": 200,
          |    "cbDuration": 0,
          |    "overhead": 123,
          |    "duration": 130,
          |    "geolocationInfo": null,
          |    "@service": "product-core-api",
          |    "@timestamp": 1601449087433,
          |    "cluster-mode": "Worker",
          |    "instance-dc": "local",
          |    "user-agent-details": null,
          |    "protocol": "HTTP/1.1",
          |    "gwError": null,
          |    "reqId": "1311198623127570119",
          |    "@type": "GatewayEvent",
          |    "@id": "1311198623672829645",
          |    "to": {
          |      "scheme": "https",
          |      "host": "build-product.domain.io",
          |      "uri": "/favicon.ico"
          |    },
          |    "origin-details": null,
          |    "identity": null,
          |    "cluster-name": "otoroshi-worker-0"
          |}
          |""".stripMargin)

      val projection = Json.obj(
        "@type"              -> Json.obj("$value" -> "HttpAccessEvent"),
        "@id"                -> true,
        "@reqId"             -> "reqId",
        "@timestamp"         -> true,
        "@service"           -> true,
        "@serviceId"         -> true,
        "client"             -> Json.obj("$atIf" -> Json.obj("path" -> "identity.identity", "predicate" -> Json.obj("at" -> "identity.identityType", "value" -> "PRIVATEAPP"))),
        "status"             -> true,
        "path"               -> Json.obj("$at" -> "to.uri"),
        "method"             -> true,
        "user"               -> Json.obj("$atIf" -> Json.obj("path" -> "identity.identity", "predicate" -> Json.obj("at" -> "identity.identityType", "value" -> "APIKEY"))),
        "from"               -> true,
        "duration"           -> true,
        "to"                 -> Json.obj("$at" -> "target"),
        "http"               -> Json.obj("$at" -> "to.scheme"),
        "protocol"           -> true,
        "size"               -> Json.obj("$at" -> "data.dataOut"),
        "referer"            -> Json.obj("$header" -> Json.obj("path" -> "headers", "name" -> "Referer")),
        "user-agent"         -> Json.obj("$header" -> Json.obj("path" -> "headers", "name" -> "User-Agent")),
        "service"            -> Json.obj("$at" -> "descriptor.name"),
        "host"               -> Json.obj("$at" -> "to.host"),
        "error"              -> "err",
        "errorMsg"           -> "gwError",
        "errorCause"         -> "gwError",
        "user-agent-details" -> true,
        "origin-details"     -> true,
        "instance-name"      -> true,
        "instance-zone"      -> true,
        "instance-region"    -> true,
        "instance-dc"        -> true,
        "instance-provider"  -> true,
        "instance-rack"      -> true,
        "product-headers"     -> Json.obj(
          "x-product-userid"   -> Json.obj("$header" -> Json.obj("path" -> "headers", "name" -> "x-product-userid")),
          "x-productclient-version"   -> Json.obj("$header" -> Json.obj("path" -> "headers", "name" -> "x-productclient-version")),
          "x-productfrontend-version" -> Json.obj("$header" -> Json.obj("path" -> "headers", "name" -> "x-productfrontend-version")),
          "x-product-host-appid"      -> Json.obj("$header" -> Json.obj("path" -> "headers", "name" -> "x-product-host-appid")),
          "x-product-extrainfo1"      -> Json.obj("$header" -> Json.obj("path" -> "headers", "name" -> "x-product-extrainfo1")),
          "x-product-extrainfo2"      -> Json.obj("$header" -> Json.obj("path" -> "headers", "name" -> "x-product-extrainfo2"))
        ),
        "cluster-mode"       -> true,
        "cluster-name"       -> true
      )

      val predicate = Json.obj(
        "@type" -> "GatewayEvent",
        "@serviceId" -> "service-id"
      )

      otoroshi.utils.Match.matches(source, predicate) mustBe true
      val result = otoroshi.utils.Project.project(source, projection)
      println(Json.prettyPrint(result))
    }
  }

}
