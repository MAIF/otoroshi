package otoroshi.plugins.accesslog

import akka.http.scaladsl.util.FastFuture
import akka.stream.Materializer
import cluster.ClusterMode
import env.Env
import events._
import org.joda.time.DateTime
import otoroshi.script.{HttpResponse, RequestTransformer, TransformerErrorContext, TransformerResponseContext}
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.Result
import utils.RegexPool
import utils.RequestImplicits._
import utils.future.Implicits._

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future}

object AccessLog {
  val statusNames = Map(
    100 -> "Continue",
    101 -> "Switching Protocols",
    102 -> "Processing",
    200 -> "OK",
    201 -> "Created",
    202 -> "Accepted",
    203 -> "Non-authoritative Information",
    204 -> "No Content",
    205 -> "Reset Content",
    206 -> "Partial Content",
    207 -> "Multi-Status",
    208 -> "Already Reported",
    226 -> "IM Used",
    300 -> "Multiple Choices",
    301 -> "Moved Permanently",
    302 -> "Found",
    303 -> "See Other",
    304 -> "Not Modified",
    305 -> "Use Proxy",
    307 -> "Temporary Redirect",
    308 -> "Permanent Redirect",
    400 -> "Bad Request",
    401 -> "Unauthorized",
    402 -> "Payment Required",
    403 -> "Forbidden",
    404 -> "Not Found",
    405 -> "Method Not Allowed",
    406 -> "Not Acceptable",
    407 -> "Proxy Authentication Required",
    408 -> "Request Timeout",
    409 -> "Conflict",
    410 -> "Gone",
    411 -> "Length Required",
    412 -> "Precondition Failed",
    413 -> "Payload Too Large",
    414 -> "Request-URI Too Long",
    415 -> "Unsupported Media Type",
    416 -> "Requested Range Not Satisfiable",
    417 -> "Expectation Failed",
    418 -> "I'm a teapot",
    421 -> "Misdirected Request",
    422 -> "Unprocessable Entity",
    423 -> "Locked",
    424 -> "Failed Dependency",
    426 -> "Upgrade Required",
    428 -> "Precondition Required",
    429 -> "Too Many Requests",
    431 -> "Request Header Fields Too Large",
    444 -> "Connection Closed Without Response",
    451 -> "Unavailable For Legal Reasons",
    499 -> "Client Closed Request",
    500 -> "Internal Server Error",
    501 -> "Not Implemented",
    502 -> "Bad Gateway",
    503 -> "Service Unavailable",
    504 -> "Gateway Timeout",
    505 -> "HTTP Version Not Supported",
    506 -> "Variant Also Negotiates",
    507 -> "Insufficient Storage",
    508 -> "Loop Detected",
    510 -> "Not Extended",
    511 -> "Network Authentication Required",
    599 -> "Network Connect Timeout Error"
  )
}

class AccessLog extends RequestTransformer {

  private val logger = Logger("otoroshi-plugins-access-log-clf")

  override def name: String = "Access log (CLF)"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "AccessLog" -> Json.obj(
          "enabled"    -> true,
          "statuses"   -> Json.arr(),
          "paths"      -> Json.arr(),
          "methods"    -> Json.arr(),
          "identities" -> Json.arr(),
        )
      )
    )

  override def description: Option[String] =
    Some(
      """With this plugin, any access to a service will be logged in CLF format.
      |
      |Log format is the following:
      |
      |`"$service" $clientAddress - "$userId" [$timestamp] "$host $method $path $protocol" "$status $statusTxt" $size $snowflake "$to" "$referrer" "$userAgent" $http $duration $errorMsg`
      |
      |The plugin accepts the following configuration
      |
      |```json
      |{
      |  "AccessLog": {
      |    "enabled": true,
      |    "statuses": [], // list of status to enable logs, if none, log everything
      |    "paths": [], // list of paths to enable logs, if none, log everything
      |    "methods": [], // list of http methods to enable logs, if none, log everything
      |    "identities": [] // list of identities to enable logs, if none, log everything
      |  }
      |}
      |```
    """.stripMargin
    )

  override def transformResponseWithCtx(
      ctx: TransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {

    val snowflake = ctx.snowflake
    val status    = ctx.rawResponse.status
    val statusTxt = AccessLog.statusNames.getOrElse(status, "-")
    val path      = ctx.request.relativeUri
    val method    = ctx.request.method
    val userId    = ctx.user.map(_.name).orElse(ctx.apikey.map(_.clientName)).getOrElse("-")

    val (matchPath, methodMatch, statusMatch, identityMatch, enabled) = if ((ctx.config \ "AccessLog").isDefined) {
      val enabled: Boolean          = (ctx.config \ "AccessLog" \ "enabled").asOpt[Boolean].getOrElse(true)
      val validPaths: Seq[String]   = (ctx.config \ "AccessLog" \ "paths").asOpt[Seq[String]].getOrElse(Seq.empty)
      val validStatuses: Seq[Int]   = (ctx.config \ "AccessLog" \ "statuses").asOpt[Seq[Int]].getOrElse(Seq.empty)
      val validMethods: Seq[String] = (ctx.config \ "AccessLog" \ "methods").asOpt[Seq[String]].getOrElse(Seq.empty)
      val validIdentities: Seq[String] =
        (ctx.config \ "AccessLog" \ "identities").asOpt[Seq[String]].getOrElse(Seq.empty)

      val matchPath = if (validPaths.isEmpty) true else validPaths.exists(p => RegexPool.regex(p).matches(path))
      val methodMatch =
        if (validMethods.isEmpty) true else validMethods.map(_.toLowerCase()).contains(method.toLowerCase())
      val statusMatch   = if (validStatuses.isEmpty) true else validStatuses.contains(status)
      val identityMatch = if (validIdentities.isEmpty) true else validIdentities.contains(userId)

      (matchPath, methodMatch, statusMatch, identityMatch, enabled)
    } else {
      (true, true, true, true, true)
    }

    if (matchPath && methodMatch && statusMatch && identityMatch && enabled) {
      val ipAddress = ctx.request.theIpAddress
      val timestamp = ctx.attrs
        .get(otoroshi.plugins.Keys.RequestTimestampKey)
        .getOrElse(DateTime.now())
        .toString("yyyy-MM-dd HH:mm:ss.SSS z")
      val duration =
        ctx.attrs.get(otoroshi.plugins.Keys.RequestStartKey).map(v => System.currentTimeMillis() - v).getOrElse(0L)
      val to       = ctx.attrs.get(otoroshi.plugins.Keys.RequestTargetKey).map(_.asTargetStr).getOrElse("-")
      val http     = ctx.request.theProtocol
      val protocol = ctx.request.version
      val size = ctx.rawResponse.headers
        .get("Content-Length")
        .orElse(ctx.rawResponse.headers.get("content-length"))
        .getOrElse("-")
      val referrer =
        ctx.rawResponse.headers.get("Referrer").orElse(ctx.rawResponse.headers.get("Referrer")).getOrElse("-")
      val userAgent = ctx.request.headers.get("User-Agent").orElse(ctx.request.headers.get("user-agent")).getOrElse("-")
      val service   = ctx.descriptor.name
      val host      = ctx.request.host
      logger.info(
        s""""$service" $ipAddress - "$userId" [$timestamp] "$host $method $path $protocol" "$status $statusTxt" $size $snowflake "$to" "$referrer" "$userAgent" $http ${duration}ms "-""""
      )
    }
    Right(ctx.otoroshiResponse).future
  }

  override def transformErrorWithCtx(
      ctx: TransformerErrorContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {

    val snowflake = ctx.snowflake
    val status    = ctx.otoroshiResponse.status
    val statusTxt = AccessLog.statusNames.getOrElse(status, "-")
    val path      = ctx.request.relativeUri
    val method    = ctx.request.method
    val userId    = ctx.user.map(_.name).orElse(ctx.apikey.map(_.clientName)).getOrElse("-")

    val (matchPath, methodMatch, statusMatch, identityMatch, enabled) = if ((ctx.config \ "AccessLog").isDefined) {
      val enabled: Boolean          = (ctx.config \ "AccessLog" \ "enabled").asOpt[Boolean].getOrElse(true)
      val validPaths: Seq[String]   = (ctx.config \ "AccessLog" \ "paths").asOpt[Seq[String]].getOrElse(Seq.empty)
      val validStatuses: Seq[Int]   = (ctx.config \ "AccessLog" \ "statuses").asOpt[Seq[Int]].getOrElse(Seq.empty)
      val validMethods: Seq[String] = (ctx.config \ "AccessLog" \ "methods").asOpt[Seq[String]].getOrElse(Seq.empty)
      val validIdentities: Seq[String] =
        (ctx.config \ "AccessLog" \ "identities").asOpt[Seq[String]].getOrElse(Seq.empty)

      val matchPath = if (validPaths.isEmpty) true else validPaths.exists(p => RegexPool.regex(p).matches(path))
      val methodMatch =
        if (validMethods.isEmpty) true else validMethods.map(_.toLowerCase()).contains(method.toLowerCase())
      val statusMatch   = if (validStatuses.isEmpty) true else validStatuses.contains(status)
      val identityMatch = if (validIdentities.isEmpty) true else validIdentities.contains(userId)

      (matchPath, methodMatch, statusMatch, identityMatch, enabled)
    } else {
      (true, true, true, true, true)
    }

    if (matchPath && methodMatch && statusMatch && identityMatch && enabled) {
      val ipAddress = ctx.request.theIpAddress
      val timestamp = ctx.attrs
        .get(otoroshi.plugins.Keys.RequestTimestampKey)
        .getOrElse(DateTime.now())
        .toString("yyyy-MM-dd HH:mm:ss.SSS z")
      val duration =
        ctx.attrs.get(otoroshi.plugins.Keys.RequestStartKey).map(v => System.currentTimeMillis() - v).getOrElse(0L)
      val to       = ctx.attrs.get(otoroshi.plugins.Keys.RequestTargetKey).map(_.asTargetStr).getOrElse("-")
      val http     = ctx.request.theProtocol
      val protocol = ctx.request.version
      val size = ctx.otoroshiResponse.headers
        .get("Content-Length")
        .orElse(ctx.otoroshiResponse.headers.get("content-length"))
        .getOrElse("-")
      val referrer =
        ctx.otoroshiResponse.headers.get("Referrer").orElse(ctx.otoroshiResponse.headers.get("Referrer")).getOrElse("-")
      val userAgent = ctx.request.headers.get("User-Agent").orElse(ctx.request.headers.get("user-agent")).getOrElse("-")
      val service   = ctx.descriptor.name
      val host      = ctx.request.host
      logger.info(
        s""""$service" $ipAddress - "$userId" [$timestamp] "$host $method $path $protocol" "$status $statusTxt" $size $snowflake "$to" "$referrer" "$userAgent" $http ${duration}ms "${ctx.message}" """
      )
    }
    ctx.otoroshiResult.future
  }
}

class AccessLogJson extends RequestTransformer {

  private val logger = Logger("otoroshi-plugins-access-log-json")

  override def name: String = "Access log (JSON)"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "AccessLog" -> Json.obj(
          "enabled"    -> true,
          "statuses"   -> Json.arr(),
          "paths"      -> Json.arr(),
          "methods"    -> Json.arr(),
          "identities" -> Json.arr(),
        )
      )
    )

  override def description: Option[String] =
    Some("""With this plugin, any access to a service will be logged in json format.
      |
      |The plugin accepts the following configuration
      |
      |```json
      |{
      |  "AccessLog": {
      |    "enabled": true,
      |    "statuses": [], // list of status to enable logs, if none, log everything
      |    "paths": [], // list of paths to enable logs, if none, log everything
      |    "methods": [], // list of http methods to enable logs, if none, log everything
      |    "identities": [] // list of identities to enable logs, if none, log everything
      |  }
      |}
      |```
    """.stripMargin)

  override def transformResponseWithCtx(
      ctx: TransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {

    val snowflake = ctx.snowflake
    val status    = ctx.rawResponse.status
    val statusTxt = AccessLog.statusNames.getOrElse(status, "-")
    val path      = ctx.request.relativeUri
    val method    = ctx.request.method
    val userId    = ctx.user.map(_.name).orElse(ctx.apikey.map(_.clientName)).getOrElse("-")

    val (matchPath, methodMatch, statusMatch, identityMatch, enabled) = if ((ctx.config \ "AccessLog").isDefined) {
      val enabled: Boolean          = (ctx.config \ "AccessLog" \ "enabled").asOpt[Boolean].getOrElse(true)
      val validPaths: Seq[String]   = (ctx.config \ "AccessLog" \ "paths").asOpt[Seq[String]].getOrElse(Seq.empty)
      val validStatuses: Seq[Int]   = (ctx.config \ "AccessLog" \ "statuses").asOpt[Seq[Int]].getOrElse(Seq.empty)
      val validMethods: Seq[String] = (ctx.config \ "AccessLog" \ "methods").asOpt[Seq[String]].getOrElse(Seq.empty)
      val validIdentities: Seq[String] =
        (ctx.config \ "AccessLog" \ "identities").asOpt[Seq[String]].getOrElse(Seq.empty)

      val matchPath = if (validPaths.isEmpty) true else validPaths.exists(p => RegexPool.regex(p).matches(path))
      val methodMatch =
        if (validMethods.isEmpty) true else validMethods.map(_.toLowerCase()).contains(method.toLowerCase())
      val statusMatch   = if (validStatuses.isEmpty) true else validStatuses.contains(status)
      val identityMatch = if (validIdentities.isEmpty) true else validIdentities.contains(userId)

      (matchPath, methodMatch, statusMatch, identityMatch, enabled)
    } else {
      (true, true, true, true, true)
    }

    if (matchPath && methodMatch && statusMatch && identityMatch && enabled) {
      val ipAddress = ctx.request.theIpAddress
      val timestamp = ctx.attrs
        .get(otoroshi.plugins.Keys.RequestTimestampKey)
        .getOrElse(DateTime.now())
        .toString("yyyy-MM-dd HH:mm:ss.SSS z")
      val duration =
        ctx.attrs.get(otoroshi.plugins.Keys.RequestStartKey).map(v => System.currentTimeMillis() - v).getOrElse(0L)
      val to       = ctx.attrs.get(otoroshi.plugins.Keys.RequestTargetKey).map(_.asTargetStr).getOrElse("-")
      val http     = ctx.request.theProtocol
      val protocol = ctx.request.version
      val size = ctx.rawResponse.headers
        .get("Content-Length")
        .orElse(ctx.rawResponse.headers.get("content-length"))
        .getOrElse("-")
      val referrer =
        ctx.rawResponse.headers.get("Referrer").orElse(ctx.rawResponse.headers.get("Referrer")).getOrElse("-")
      val userAgent = ctx.request.headers.get("User-Agent").orElse(ctx.request.headers.get("user-agent")).getOrElse("-")
      val service   = ctx.descriptor.name
      val host      = ctx.request.host
      logger.info(
        Json.stringify(
          Json.obj(
            "snowflake"  -> snowflake,
            "timestamp"  -> timestamp,
            "service"    -> ctx.descriptor.name,
            "serviceId"  -> ctx.descriptor.id,
            "status"     -> status,
            "statusTxt"  -> statusTxt,
            "path"       -> path,
            "method"     -> method,
            "user"       -> userId,
            "from"       -> ipAddress,
            "duration"   -> duration,
            "to"         -> to,
            "http"       -> http,
            "protocol"   -> protocol,
            "size"       -> size,
            "referrer"   -> referrer,
            "user-agent" -> userAgent,
            "service"    -> service,
            "host"       -> host,
            "error"      -> false,
            "errorMsg"   -> JsNull,
            "errorCause" -> JsNull
          )
        )
      )
    }
    Right(ctx.otoroshiResponse).future
  }

  override def transformErrorWithCtx(
      ctx: TransformerErrorContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {

    val snowflake = ctx.snowflake
    val status    = ctx.otoroshiResponse.status
    val statusTxt = AccessLog.statusNames.getOrElse(status, "-")
    val path      = ctx.request.relativeUri
    val method    = ctx.request.method
    val userId    = ctx.user.map(_.name).orElse(ctx.apikey.map(_.clientName)).getOrElse("-")

    val (matchPath, methodMatch, statusMatch, identityMatch, enabled) = if ((ctx.config \ "AccessLog").isDefined) {
      val enabled: Boolean          = (ctx.config \ "AccessLog" \ "enabled").asOpt[Boolean].getOrElse(true)
      val validPaths: Seq[String]   = (ctx.config \ "AccessLog" \ "paths").asOpt[Seq[String]].getOrElse(Seq.empty)
      val validStatuses: Seq[Int]   = (ctx.config \ "AccessLog" \ "statuses").asOpt[Seq[Int]].getOrElse(Seq.empty)
      val validMethods: Seq[String] = (ctx.config \ "AccessLog" \ "methods").asOpt[Seq[String]].getOrElse(Seq.empty)
      val validIdentities: Seq[String] =
        (ctx.config \ "AccessLog" \ "identities").asOpt[Seq[String]].getOrElse(Seq.empty)

      val matchPath = if (validPaths.isEmpty) true else validPaths.exists(p => RegexPool.regex(p).matches(path))
      val methodMatch =
        if (validMethods.isEmpty) true else validMethods.map(_.toLowerCase()).contains(method.toLowerCase())
      val statusMatch   = if (validStatuses.isEmpty) true else validStatuses.contains(status)
      val identityMatch = if (validIdentities.isEmpty) true else validIdentities.contains(userId)

      (matchPath, methodMatch, statusMatch, identityMatch, enabled)
    } else {
      (true, true, true, true, true)
    }

    if (matchPath && methodMatch && statusMatch && identityMatch && enabled) {
      val ipAddress = ctx.request.theIpAddress
      val timestamp = ctx.attrs
        .get(otoroshi.plugins.Keys.RequestTimestampKey)
        .getOrElse(DateTime.now())
        .toString("yyyy-MM-dd HH:mm:ss.SSS z")
      val duration =
        ctx.attrs.get(otoroshi.plugins.Keys.RequestStartKey).map(v => System.currentTimeMillis() - v).getOrElse(0L)
      val to       = ctx.attrs.get(otoroshi.plugins.Keys.RequestTargetKey).map(_.asTargetStr).getOrElse("-")
      val http     = ctx.request.theProtocol
      val protocol = ctx.request.version
      val size = ctx.otoroshiResponse.headers
        .get("Content-Length")
        .orElse(ctx.otoroshiResponse.headers.get("content-length"))
        .getOrElse("-")
      val referrer =
        ctx.otoroshiResponse.headers.get("Referrer").orElse(ctx.otoroshiResponse.headers.get("Referrer")).getOrElse("-")
      val userAgent = ctx.request.headers.get("User-Agent").orElse(ctx.request.headers.get("user-agent")).getOrElse("-")
      val service   = ctx.descriptor.name
      val host      = ctx.request.host
      logger.info(
        Json.stringify(
          Json.obj(
            "snowflake"  -> snowflake,
            "timestamp"  -> timestamp,
            "service"    -> ctx.descriptor.name,
            "serviceId"  -> ctx.descriptor.id,
            "status"     -> status,
            "statusTxt"  -> statusTxt,
            "path"       -> path,
            "method"     -> method,
            "user"       -> userId,
            "from"       -> ipAddress,
            "duration"   -> duration,
            "to"         -> to,
            "http"       -> http,
            "protocol"   -> protocol,
            "size"       -> size,
            "referrer"   -> referrer,
            "user-agent" -> userAgent,
            "service"    -> service,
            "host"       -> host,
            "error"      -> true,
            "errorMsg"   -> ctx.message,
            "errorCause" -> ctx.maybeCauseId.map(JsString.apply).getOrElse(JsNull).as[JsValue],
          )
        )
      )
    }
    ctx.otoroshiResult.future
  }
}

class KafkaAccessLog extends RequestTransformer {

  private val logger = Logger("otoroshi-plugins-kafka-access-log")

  private val kafkaWrapperCache = new TrieMap[String, KafkaWrapper]

  override def name: String = "Kafka access log"

  override def defaultConfig: Option[JsObject] =
    Some(
      Json.obj(
        "KafkaAccessLog" -> Json.obj(
          "enabled"    -> true,
          "topic"      -> "otoroshi-access-log",
          "statuses"   -> Json.arr(),
          "paths"      -> Json.arr(),
          "methods"    -> Json.arr(),
          "identities" -> Json.arr(),
        )
      )
    )

  override def description: Option[String] =
    Some("""With this plugin, any access to a service will be logged as an event in a kafka topic.
      |
      |The plugin accepts the following configuration
      |
      |```json
      |{
      |  "KafkaAccessLog": {
      |    "enabled": true,
      |    "topic": "otoroshi-access-log",
      |    "statuses": [], // list of status to enable logs, if none, log everything
      |    "paths": [], // list of paths to enable logs, if none, log everything
      |    "methods": [], // list of http methods to enable logs, if none, log everything
      |    "identities": [] // list of identities to enable logs, if none, log everything
      |  }
      |}
      |```
    """.stripMargin)

  override def stop(env: Env): Future[Unit] = {
    kafkaWrapperCache.foreach(_._2.close())
    FastFuture.successful(())
  }

  override def transformResponseWithCtx(
      ctx: TransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, HttpResponse]] = {

    env.datastores.globalConfigDataStore.latestSafe match {
      case None => Right(ctx.otoroshiResponse).future
      case Some(globalConfig) =>
        globalConfig.kafkaConfig match {
          case None => Right(ctx.otoroshiResponse).future
          case Some(kafkaConfig) => {

            val snowflake = ctx.snowflake
            val status    = ctx.rawResponse.status
            val statusTxt = AccessLog.statusNames.getOrElse(status, "-")
            val path      = ctx.request.relativeUri
            val method    = ctx.request.method
            val userId    = ctx.user.map(_.name).orElse(ctx.apikey.map(_.clientName)).getOrElse("-")

            val (matchPath, methodMatch, statusMatch, identityMatch, enabled, topic) =
              if ((ctx.config \ "KafkaAccessLog").isDefined) {
                val enabled: Boolean = (ctx.config \ "KafkaAccessLog" \ "enabled").asOpt[Boolean].getOrElse(true)
                val topic: String =
                  (ctx.config \ "KafkaAccessLog" \ "topic").asOpt[String].getOrElse("otoroshi-access-log")
                val validPaths: Seq[String] =
                  (ctx.config \ "KafkaAccessLog" \ "paths").asOpt[Seq[String]].getOrElse(Seq.empty)
                val validStatuses: Seq[Int] =
                  (ctx.config \ "KafkaAccessLog" \ "statuses").asOpt[Seq[Int]].getOrElse(Seq.empty)
                val validMethods: Seq[String] =
                  (ctx.config \ "KafkaAccessLog" \ "methods").asOpt[Seq[String]].getOrElse(Seq.empty)
                val validIdentities: Seq[String] =
                  (ctx.config \ "KafkaAccessLog" \ "identities").asOpt[Seq[String]].getOrElse(Seq.empty)

                val matchPath =
                  if (validPaths.isEmpty) true else validPaths.exists(p => RegexPool.regex(p).matches(path))
                val methodMatch =
                  if (validMethods.isEmpty) true else validMethods.map(_.toLowerCase()).contains(method.toLowerCase())
                val statusMatch   = if (validStatuses.isEmpty) true else validStatuses.contains(status)
                val identityMatch = if (validIdentities.isEmpty) true else validIdentities.contains(userId)

                (matchPath, methodMatch, statusMatch, identityMatch, enabled, topic)
              } else {
                (true, true, true, true, true, "otoroshi-access-log")
              }

            if (matchPath && methodMatch && statusMatch && identityMatch && enabled) {
              val ipAddress = ctx.request.theIpAddress
              val timestamp = ctx.attrs.get(otoroshi.plugins.Keys.RequestTimestampKey).getOrElse(DateTime.now()) //.toString("yyyy-MM-dd HH:mm:ss.SSS z")
              val duration = ctx.attrs
                .get(otoroshi.plugins.Keys.RequestStartKey)
                .map(v => System.currentTimeMillis() - v)
                .getOrElse(0L)
              val to       = ctx.attrs.get(otoroshi.plugins.Keys.RequestTargetKey).map(_.asTargetStr).getOrElse("-")
              val http     = ctx.request.theProtocol
              val protocol = ctx.request.version
              val size = ctx.rawResponse.headers
                .get("Content-Length")
                .orElse(ctx.rawResponse.headers.get("content-length"))
                .getOrElse("-")
              val referrer =
                ctx.rawResponse.headers.get("Referrer").orElse(ctx.rawResponse.headers.get("Referrer")).getOrElse("-")
              val userAgent =
                ctx.request.headers.get("User-Agent").orElse(ctx.request.headers.get("user-agent")).getOrElse("-")
              val service                   = ctx.descriptor.name
              val host                      = ctx.request.host
              val userAgentDetails: JsValue = ctx.attrs.get(otoroshi.plugins.Keys.UserAgentInfoKey).getOrElse(JsNull)
              val geolocationDetails: JsValue =
                ctx.attrs.get(otoroshi.plugins.Keys.GeolocationInfoKey).getOrElse(JsNull)
              val kafkaWrapper =
                kafkaWrapperCache.getOrElseUpdate(topic, new KafkaWrapper(env.analyticsActorSystem, env, _ => topic))
              kafkaWrapper.publish(
                Json.obj(
                  "@type"              -> "HttpAccessEvent",
                  "@id"                -> env.snowflakeGenerator.nextIdStr(),
                  "@reqId"             -> snowflake,
                  "@timestamp"         -> timestamp.toDate.getTime,
                  "@service"           -> ctx.descriptor.name,
                  "@serviceId"         -> ctx.descriptor.id,
                  "status"             -> status,
                  "statusTxt"          -> statusTxt,
                  "path"               -> path,
                  "method"             -> method,
                  "user"               -> userId,
                  "from"               -> ipAddress,
                  "duration"           -> duration,
                  "to"                 -> to,
                  "http"               -> http,
                  "protocol"           -> protocol,
                  "size"               -> size,
                  "referrer"           -> referrer,
                  "user-agent"         -> userAgent,
                  "service"            -> service,
                  "host"               -> host,
                  "error"              -> false,
                  "errorMsg"           -> JsNull,
                  "errorCause"         -> JsNull,
                  "user-agent-details" -> userAgentDetails,
                  "origin-details"     -> geolocationDetails,
                  "instance-name"      -> env.name,
                  "instance-zone"      -> env.zone,
                  "instance-region"    -> env.region,
                  "instance-dc"        -> env.dataCenter,
                  "instance-provider"  -> env.infraProvider,
                  "instance-rack"      -> env.rack,
                  "cluster-mode"       -> env.clusterConfig.mode.name,
                  "cluster-name" -> (env.clusterConfig.mode match {
                    case ClusterMode.Worker => env.clusterConfig.worker.name
                    case ClusterMode.Leader => env.clusterConfig.leader.name
                    case _                  => "none"
                  })
                )
              )(env, kafkaConfig)
            }
            Right(ctx.otoroshiResponse).future
          }
        }
    }
  }

  override def transformErrorWithCtx(
      ctx: TransformerErrorContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {

    env.datastores.globalConfigDataStore.latestSafe match {
      case None => ctx.otoroshiResult.future
      case Some(globalConfig) =>
        globalConfig.kafkaConfig match {
          case None => ctx.otoroshiResult.future
          case Some(kafkaConfig) => {

            val snowflake = ctx.snowflake
            val status    = ctx.otoroshiResponse.status
            val statusTxt = AccessLog.statusNames.getOrElse(status, "-")
            val path      = ctx.request.relativeUri
            val method    = ctx.request.method
            val userId    = ctx.user.map(_.name).orElse(ctx.apikey.map(_.clientName)).getOrElse("-")

            val (matchPath, methodMatch, statusMatch, identityMatch, enabled, topic) =
              if ((ctx.config \ "KafkaAccessLog").isDefined) {
                val enabled: Boolean = (ctx.config \ "KafkaAccessLog" \ "enabled").asOpt[Boolean].getOrElse(true)
                val topic: String =
                  (ctx.config \ "KafkaAccessLog" \ "topic").asOpt[String].getOrElse("otoroshi-access-log")
                val validPaths: Seq[String] =
                  (ctx.config \ "KafkaAccessLog" \ "paths").asOpt[Seq[String]].getOrElse(Seq.empty)
                val validStatuses: Seq[Int] =
                  (ctx.config \ "KafkaAccessLog" \ "statuses").asOpt[Seq[Int]].getOrElse(Seq.empty)
                val validMethods: Seq[String] =
                  (ctx.config \ "KafkaAccessLog" \ "methods").asOpt[Seq[String]].getOrElse(Seq.empty)
                val validIdentities: Seq[String] =
                  (ctx.config \ "KafkaAccessLog" \ "identities").asOpt[Seq[String]].getOrElse(Seq.empty)

                val matchPath =
                  if (validPaths.isEmpty) true else validPaths.exists(p => RegexPool.regex(p).matches(path))
                val methodMatch =
                  if (validMethods.isEmpty) true else validMethods.map(_.toLowerCase()).contains(method.toLowerCase())
                val statusMatch   = if (validStatuses.isEmpty) true else validStatuses.contains(status)
                val identityMatch = if (validIdentities.isEmpty) true else validIdentities.contains(userId)

                (matchPath, methodMatch, statusMatch, identityMatch, enabled, topic)
              } else {
                (true, true, true, true, true, "otoroshi-access-log")
              }

            val kafkaWrapper =
              kafkaWrapperCache.getOrElseUpdate(topic, new KafkaWrapper(env.analyticsActorSystem, env, _ => topic))

            if (matchPath && methodMatch && statusMatch && identityMatch && enabled) {
              val ipAddress = ctx.request.theIpAddress
              val timestamp = ctx.attrs.get(otoroshi.plugins.Keys.RequestTimestampKey).getOrElse(DateTime.now()) //.toString("yyyy-MM-dd HH:mm:ss.SSS z")
              val duration = ctx.attrs
                .get(otoroshi.plugins.Keys.RequestStartKey)
                .map(v => System.currentTimeMillis() - v)
                .getOrElse(0L)
              val to       = ctx.attrs.get(otoroshi.plugins.Keys.RequestTargetKey).map(_.asTargetStr).getOrElse("-")
              val http     = ctx.request.theProtocol
              val protocol = ctx.request.version
              val size = ctx.otoroshiResponse.headers
                .get("Content-Length")
                .orElse(ctx.otoroshiResponse.headers.get("content-length"))
                .getOrElse("-")
              val referrer = ctx.otoroshiResponse.headers
                .get("Referrer")
                .orElse(ctx.otoroshiResponse.headers.get("Referrer"))
                .getOrElse("-")
              val userAgent =
                ctx.request.headers.get("User-Agent").orElse(ctx.request.headers.get("user-agent")).getOrElse("-")
              val service                   = ctx.descriptor.name
              val host                      = ctx.request.host
              val userAgentDetails: JsValue = ctx.attrs.get(otoroshi.plugins.Keys.UserAgentInfoKey).getOrElse(JsNull)
              val geolocationDetails: JsValue =
                ctx.attrs.get(otoroshi.plugins.Keys.GeolocationInfoKey).getOrElse(JsNull)
              kafkaWrapper.publish(
                Json.obj(
                  "@type"              -> "HttpAccessEvent",
                  "@id"                -> env.snowflakeGenerator.nextIdStr(),
                  "@reqId"             -> snowflake,
                  "@timestamp"         -> timestamp.toDate.getTime,
                  "@service"           -> ctx.descriptor.name,
                  "@serviceId"         -> ctx.descriptor.id,
                  "status"             -> status,
                  "statusTxt"          -> statusTxt,
                  "path"               -> path,
                  "method"             -> method,
                  "user"               -> userId,
                  "from"               -> ipAddress,
                  "duration"           -> duration,
                  "to"                 -> to,
                  "http"               -> http,
                  "protocol"           -> protocol,
                  "size"               -> size,
                  "referrer"           -> referrer,
                  "user-agent"         -> userAgent,
                  "service"            -> service,
                  "host"               -> host,
                  "error"              -> true,
                  "errorMsg"           -> ctx.message,
                  "errorCause"         -> ctx.maybeCauseId.map(JsString.apply).getOrElse(JsNull).as[JsValue],
                  "user-agent-details" -> userAgentDetails,
                  "origin-details"     -> geolocationDetails,
                  "instance-name"      -> env.name,
                  "instance-zone"      -> env.zone,
                  "instance-region"    -> env.region,
                  "instance-dc"        -> env.dataCenter,
                  "instance-provider"  -> env.infraProvider,
                  "instance-rack"      -> env.rack,
                  "cluster-mode"       -> env.clusterConfig.mode.name,
                  "cluster-name" -> (env.clusterConfig.mode match {
                    case ClusterMode.Worker => env.clusterConfig.worker.name
                    case ClusterMode.Leader => env.clusterConfig.leader.name
                    case _                  => "none"
                  })
                )
              )(env, kafkaConfig)
            }
            ctx.otoroshiResult.future
          }
        }
    }
  }
}
