package otoroshi.gateway

import akka.http.scaladsl.util.FastFuture
import com.github.blemale.scaffeine.Scaffeine
import org.joda.time.DateTime
import otoroshi.el.TargetExpressionLanguage
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.gateway.Errors.errorTemplate
import otoroshi.models.{ErrorTemplate, RemainingQuotas, ServiceDescriptor}
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api.{NgPluginHttpResponse, NgTransformerErrorContext}
import otoroshi.script.Implicits._
import otoroshi.script.{HttpResponse, TransformerErrorContext}
import otoroshi.utils.TypedMap
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.DefaultWSCookie
import play.api.mvc.Results.Status
import play.api.mvc.{RequestHeader, Result}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}

case class GwError(message: String)

object Errors {

  val messages = Map(
    404 -> ("The page you're looking for does not exist", "notFound.gif")
  )

  private val cache = Scaffeine().expireAfterWrite(60.seconds).maximumSize(100).build[String, Option[ErrorTemplate]]()

  private def sendAnalytics(
      headers: Seq[Header],
      errorId: String,
      message: String,
      status: Status,
      req: RequestHeader,
      maybeDescriptor: Option[ServiceDescriptor] = None,
      maybeCauseId: Option[String] = None,
      duration: Long = 0L,
      overhead: Long = 0L,
      cbDuration: Long = 0L,
      callAttempts: Int = 0,
      emptyBody: Boolean = false,
      sendEvent: Boolean = true,
      attrs: TypedMap,
      maybeRoute: Option[NgRoute] = None
  )(implicit env: Env, ec: ExecutionContext): Unit = {
    (maybeDescriptor, maybeRoute) match {
      case (Some(descriptor), _) => {
        val fromLbl          = req.headers.get(env.Headers.OtoroshiVizFromLabel).getOrElse("internet")
        // TODO : mark as error ???
        val viz: OtoroshiViz = OtoroshiViz(
          to = descriptor.id,
          toLbl = descriptor.name,
          from = req.headers.get(env.Headers.OtoroshiVizFrom).getOrElse("internet"),
          fromLbl = fromLbl,
          fromTo = s"$fromLbl###${descriptor.name}"
        )
        val _target          = attrs.get(otoroshi.plugins.Keys.RequestTargetKey).getOrElse(descriptor.target)
        val scheme           =
          if (descriptor.redirectToLocal) descriptor.localScheme else _target.scheme
        val host             = TargetExpressionLanguage(
          if (descriptor.redirectToLocal)
            descriptor.localHost
          else _target.host,
          Some(req),
          Some(descriptor),
          attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
          attrs.get(otoroshi.plugins.Keys.UserKey),
          attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
          attrs,
          env
        )
        val rawUri           = req.relativeUri.substring(1)
        val uri: String      = descriptor.maybeStrippedUri(req, rawUri)
        val url              = TargetExpressionLanguage(
          s"$scheme://$host${descriptor.root}$uri",
          Some(req),
          Some(descriptor),
          attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
          attrs.get(otoroshi.plugins.Keys.UserKey),
          attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
          attrs,
          env
        )
        GatewayEvent(
          `@id` = errorId,
          reqId = env.snowflakeGenerator.nextIdStr(),
          parentReqId = None,
          `@timestamp` = DateTime.now(),
          `@calledAt` = DateTime.now(),
          protocol = req.version,
          to = Location(
            scheme = req.theProtocol,
            host = req.theHost,
            uri = req.relativeUri
          ),
          target = Location(
            scheme = _target.scheme,
            host = _target.host,
            uri = req.relativeUri
          ),
          duration = duration,
          overhead = overhead,
          cbDuration = cbDuration,
          overheadWoCb = overhead - cbDuration,
          callAttempts = callAttempts,
          url = url,
          method = req.method,
          from = req.theIpAddress,
          env = descriptor.env,
          data = DataInOut(
            dataIn = 0,
            dataOut = 0
          ),
          status = status.header.status,
          headers = req.headers.toSimpleMap.toSeq.map(Header.apply),
          headersOut = headers,
          otoroshiHeadersIn = req.headers.toSimpleMap.toSeq.map(Header.apply),
          otoroshiHeadersOut = headers,
          extraInfos = attrs.get(otoroshi.plugins.Keys.GatewayEventExtraInfosKey),
          identity = None,
          `@serviceId` = descriptor.id,
          `@service` = descriptor.name,
          descriptor = Some(descriptor),
          `@product` = descriptor.metadata.getOrElse("product", "--"),
          remainingQuotas = RemainingQuotas(),
          responseChunked = false,
          viz = Some(viz),
          err = true,
          gwError =
            Some(attrs.get(otoroshi.plugins.Keys.GwErrorKey).map(_.message + " / " + message).getOrElse(message)),
          userAgentInfo = attrs.get[JsValue](otoroshi.plugins.Keys.UserAgentInfoKey),
          geolocationInfo = attrs.get[JsValue](otoroshi.plugins.Keys.GeolocationInfoKey),
          extraAnalyticsData = attrs.get[JsValue](otoroshi.plugins.Keys.ExtraAnalyticsDataKey)
        ).toAnalytics()(env)
      }
      case (_, Some(route))      => {
        val descriptor       = route.serviceDescriptor
        val fromLbl          = req.headers.get(env.Headers.OtoroshiVizFromLabel).getOrElse("internet")
        // TODO : mark as error ???
        val viz: OtoroshiViz = OtoroshiViz(
          to = route.id,
          toLbl = route.name,
          from = req.headers.get(env.Headers.OtoroshiVizFrom).getOrElse("internet"),
          fromLbl = fromLbl,
          fromTo = s"$fromLbl###${route.name}"
        )
        val _target          = attrs.get(otoroshi.plugins.Keys.RequestTargetKey).getOrElse(descriptor.target)
        val scheme           = _target.scheme
        val host             = TargetExpressionLanguage(
          _target.host,
          Some(req),
          Some(descriptor),
          attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
          attrs.get(otoroshi.plugins.Keys.UserKey),
          attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
          attrs,
          env
        )
        val rawUri           = req.relativeUri.substring(1)
        val uri: String      = descriptor.maybeStrippedUri(req, rawUri)
        val url              = TargetExpressionLanguage(
          s"$scheme://$host${descriptor.root}$uri",
          Some(req),
          Some(descriptor),
          attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
          attrs.get(otoroshi.plugins.Keys.UserKey),
          attrs.get(otoroshi.plugins.Keys.ElCtxKey).getOrElse(Map.empty),
          attrs,
          env
        )
        GatewayEvent(
          `@id` = errorId,
          reqId = env.snowflakeGenerator.nextIdStr(),
          parentReqId = None,
          `@timestamp` = DateTime.now(),
          `@calledAt` = DateTime.now(),
          protocol = req.version,
          to = Location(
            scheme = req.theProtocol,
            host = req.theHost,
            uri = req.relativeUri
          ),
          target = Location(
            scheme = _target.scheme,
            host = _target.host,
            uri = req.relativeUri
          ),
          duration = duration,
          overhead = overhead,
          cbDuration = cbDuration,
          overheadWoCb = overhead - cbDuration,
          callAttempts = callAttempts,
          url = url,
          method = req.method,
          from = req.theIpAddress,
          env = "prod",
          data = DataInOut(
            dataIn = 0,
            dataOut = 0
          ),
          status = status.header.status,
          headers = req.headers.toSimpleMap.toSeq.map(Header.apply),
          headersOut = headers,
          otoroshiHeadersIn = req.headers.toSimpleMap.toSeq.map(Header.apply),
          otoroshiHeadersOut = headers,
          extraInfos = attrs.get(otoroshi.plugins.Keys.GatewayEventExtraInfosKey),
          identity = None,
          `@serviceId` = route.id,
          `@service` = route.name,
          descriptor = None,
          route = Some(route),
          `@product` = route.metadata.getOrElse("product", "--"),
          remainingQuotas = RemainingQuotas(),
          responseChunked = false,
          viz = Some(viz),
          err = true,
          gwError =
            Some(attrs.get(otoroshi.plugins.Keys.GwErrorKey).map(_.message + " / " + message).getOrElse(message)),
          userAgentInfo = attrs.get[JsValue](otoroshi.plugins.Keys.UserAgentInfoKey),
          geolocationInfo = attrs.get[JsValue](otoroshi.plugins.Keys.GeolocationInfoKey),
          extraAnalyticsData = attrs.get[JsValue](otoroshi.plugins.Keys.ExtraAnalyticsDataKey)
        ).toAnalytics()(env)
      }
      case _                     => {
        val fromLbl = req.headers.get(env.Headers.OtoroshiVizFromLabel).getOrElse("internet")
        GatewayEvent(
          `@id` = errorId,
          reqId = env.snowflakeGenerator.nextIdStr(),
          parentReqId = None,
          `@timestamp` = DateTime.now(),
          `@calledAt` = DateTime.now(),
          protocol = req.version,
          to = Location(
            scheme = req.theProtocol,
            host = req.theHost,
            uri = req.relativeUri
          ),
          target = Location(
            scheme = req.theProtocol,
            host = req.theHost,
            uri = req.relativeUri
          ),
          duration = duration,
          overhead = overhead,
          cbDuration = cbDuration,
          overheadWoCb = overhead - cbDuration,
          callAttempts = callAttempts,
          url = s"${req.theProtocol}://${req.theHost}${req.relativeUri}",
          method = req.method,
          from = req.theIpAddress,
          env = "prod",
          data = DataInOut(
            dataIn = 0,
            dataOut = 0
          ),
          status = status.header.status,
          headers = req.headers.toSimpleMap.toSeq.map(Header.apply),
          headersOut = headers,
          otoroshiHeadersIn = req.headers.toSimpleMap.toSeq.map(Header.apply),
          otoroshiHeadersOut = headers,
          extraInfos = attrs.get(otoroshi.plugins.Keys.GatewayEventExtraInfosKey),
          identity = None,
          `@serviceId` = "none",
          `@service` = "none",
          descriptor = None,
          `@product` = "--",
          remainingQuotas = RemainingQuotas(),
          responseChunked = false,
          viz = None,
          err = true,
          gwError =
            Some(attrs.get(otoroshi.plugins.Keys.GwErrorKey).map(_.message + " / " + message).getOrElse(message)),
          userAgentInfo = attrs.get[JsValue](otoroshi.plugins.Keys.UserAgentInfoKey),
          geolocationInfo = attrs.get[JsValue](otoroshi.plugins.Keys.GeolocationInfoKey),
          extraAnalyticsData = attrs.get[JsValue](otoroshi.plugins.Keys.ExtraAnalyticsDataKey)
        ).toAnalytics()(env)
      }
    }
    ()
  }

  private def standardResult(
      req: RequestHeader,
      status: Status,
      message: String,
      maybeCauseId: Option[String],
      emptyBody: Boolean
  )(implicit env: Env): Result = {
    val accept = req.headers.get("Accept").getOrElse("text/html").split(",").toSeq
    if (accept.contains("text/html")) { // in a browser
      if (maybeCauseId.contains("errors.service.in.maintenance")) {
        status
          .apply(otoroshi.views.html.oto.maintenance(env))
          .withHeaders(
            env.Headers.OtoroshiGatewayError -> "true",
            env.Headers.OtoroshiErrorMsg     -> message,
            env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
          )
      } else if (maybeCauseId.contains("errors.service.under.construction")) {
        status
          .apply(otoroshi.views.html.oto.build(env))
          .withHeaders(
            env.Headers.OtoroshiGatewayError -> "true",
            env.Headers.OtoroshiErrorMsg     -> message,
            env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
          )
      } else {
        val body =
          if (emptyBody) status.apply("")
          else
            status.apply(
              otoroshi.views.html.oto.error(
                message = message,
                _env = env
              )
            )
        body
          .withHeaders(
            env.Headers.OtoroshiGatewayError -> "true",
            env.Headers.OtoroshiErrorMsg     -> message,
            env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
          )
      }
    } else {
      status
        .apply(Json.obj(env.Headers.OtoroshiGatewayError -> message))
        .withHeaders(
          env.Headers.OtoroshiGatewayError -> "true",
          env.Headers.OtoroshiErrorMsg     -> message,
          env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
        )
    }
  }

  private def customResult(
      descriptorId: String,
      req: RequestHeader,
      status: Status,
      message: String,
      maybeCauseId: Option[String],
      emptyBody: Boolean,
      errorId: String
  )(implicit env: Env, ec: ExecutionContext): Future[Result] = {
    env.datastores.errorTemplateDataStore.findById(descriptorId).map {
      case None                => standardResult(req, status, message, maybeCauseId, emptyBody)
      case Some(errorTemplate) => {
        val accept = req.headers.get("Accept").getOrElse("text/html").split(",").toSeq
        if (accept.contains("text/html")) { // in a browser
          status
            .apply(
              errorTemplate
                .renderHtml(status.header.status, maybeCauseId.getOrElse("--"), message, errorId)
            )
            .as("text/html")
            .withHeaders(
              env.Headers.OtoroshiGatewayError -> "true",
              env.Headers.OtoroshiErrorMsg     -> message,
              env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
            )
        } else {
          status
            .apply(
              errorTemplate
                .renderJson(status.header.status, maybeCauseId.getOrElse("--"), message, errorId)
            )
            .withHeaders(
              env.Headers.OtoroshiGatewayError -> "true",
              env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
            )
        }
      }
    }
  }

  private def errorTemplate(descriptorId: String)(implicit env: Env, ec: ExecutionContext): Option[ErrorTemplate] = {
    cache.getIfPresent(descriptorId) match {
      case Some(opt) => opt
      case None      =>
        env.proxyState.errorTemplate(descriptorId) match {
          case None                =>
            cache.put(descriptorId, None)
            None
          case Some(errorTemplate) =>
            cache.put(descriptorId, errorTemplate.some)
            errorTemplate.some
        }
    }
  }

  private def customResultSync(
      descriptorId: String,
      req: RequestHeader,
      status: Status,
      message: String,
      maybeCauseId: Option[String],
      emptyBody: Boolean,
      errorId: String
  )(implicit env: Env, ec: ExecutionContext): Result = {
    errorTemplate(descriptorId) match {
      case None                => standardResult(req, status, message, maybeCauseId, emptyBody)
      case Some(errorTemplate) => {
        val accept = req.headers.get("Accept").getOrElse("text/html").split(",").toSeq
        if (accept.contains("text/html")) { // in a browser
          status
            .apply(
              errorTemplate
                .renderHtml(status.header.status, maybeCauseId.getOrElse("--"), message, errorId)
            )
            .as("text/html")
            .withHeaders(
              env.Headers.OtoroshiGatewayError -> "true",
              env.Headers.OtoroshiErrorMsg     -> message,
              env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
            )
        } else {
          status
            .apply(
              errorTemplate
                .renderJson(status.header.status, maybeCauseId.getOrElse("--"), message, errorId)
            )
            .withHeaders(
              env.Headers.OtoroshiGatewayError -> "true",
              env.Headers.OtoroshiStateResp    -> req.headers.get(env.Headers.OtoroshiState).getOrElse("--")
            )
        }
      }
    }
  }

  def craftResponseResult(
      message: String,
      status: Status,
      req: RequestHeader,
      maybeDescriptor: Option[ServiceDescriptor] = None,
      maybeCauseId: Option[String] = None,
      duration: Long = 0L,
      overhead: Long = 0L,
      cbDuration: Long = 0L,
      callAttempts: Int = 0,
      emptyBody: Boolean = false,
      sendEvent: Boolean = true,
      attrs: TypedMap,
      maybeRoute: Option[NgRoute] = None
  )(implicit ec: ExecutionContext, env: Env): Future[Result] = {
    val errorId = env.snowflakeGenerator.nextIdStr()
    ((maybeDescriptor, maybeRoute) match {
      case (Some(desc), _)  => {
        customResult(desc.id, req, status, message, maybeCauseId, emptyBody, errorId).flatMap { res =>
          val ctx = TransformerErrorContext(
            index = -1,
            snowflake = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).getOrElse(env.snowflakeGenerator.nextIdStr()),
            message = message,
            otoroshiResult = res,
            otoroshiResponse = HttpResponse(
              res.header.status,
              res.header.headers,
              res.newCookies.map(c =>
                DefaultWSCookie(
                  name = c.name,
                  value = c.value,
                  domain = c.domain,
                  path = Option(c.path),
                  maxAge = c.maxAge.map(_.toLong),
                  secure = c.secure,
                  httpOnly = c.httpOnly
                )
              ),
              () => res.body.dataStream
            ),
            request = req,
            maybeCauseId = maybeCauseId,
            callAttempts = callAttempts,
            descriptor = desc,
            apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
            user = attrs.get(otoroshi.plugins.Keys.UserKey),
            config = Json.obj(),
            attrs = attrs
          )
          desc.transformError(ctx)(env, ec, env.otoroshiMaterializer)
        }
      }
      case (_, Some(route)) => {
        customResult(route.id, req, status, message, maybeCauseId, emptyBody, errorId).flatMap { res =>
          val ctx = NgTransformerErrorContext(
            snowflake = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).getOrElse(env.snowflakeGenerator.nextIdStr()),
            message = message,
            otoroshiResponse = NgPluginHttpResponse(
              res.header.status,
              res.header.headers,
              res.newCookies.map(c =>
                DefaultWSCookie(
                  name = c.name,
                  value = c.value,
                  domain = c.domain,
                  path = Option(c.path),
                  maxAge = c.maxAge.map(_.toLong),
                  secure = c.secure,
                  httpOnly = c.httpOnly
                )
              ),
              res.body.dataStream
            ),
            request = req,
            maybeCauseId = maybeCauseId,
            callAttempts = callAttempts,
            route = route,
            apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
            user = attrs.get(otoroshi.plugins.Keys.UserKey),
            config = Json.obj(),
            attrs = attrs,
            report = attrs.get(otoroshi.next.plugins.Keys.ReportKey).get
          )
          route.transformError(ctx)(env, ec, env.otoroshiMaterializer)
        }
      }
      case _                => standardResult(req, status, message, maybeCauseId, emptyBody).vfuture
    }) andThen {
      case scala.util.Success(resp) if sendEvent =>
        sendAnalytics(
          resp.header.headers.toSeq.map(Header.apply),
          errorId,
          message,
          status,
          req,
          maybeDescriptor,
          maybeCauseId,
          duration,
          overhead,
          cbDuration,
          callAttempts,
          emptyBody,
          sendEvent,
          attrs,
          maybeRoute
        )
    }
  }

  // this one might be dangerous
  def craftResponseResultSync(
      message: String,
      status: Status,
      req: RequestHeader,
      maybeDescriptor: Option[ServiceDescriptor] = None,
      maybeCauseId: Option[String] = None,
      duration: Long = 0L,
      overhead: Long = 0L,
      cbDuration: Long = 0L,
      callAttempts: Int = 0,
      emptyBody: Boolean = false,
      sendEvent: Boolean = true,
      attrs: TypedMap,
      maybeRoute: Option[NgRoute] = None
  )(implicit ec: ExecutionContext, env: Env): Result = {
    val errorId = env.snowflakeGenerator.nextIdStr()
    (maybeDescriptor, maybeRoute) match {
      case (Some(desc), _)  => {
        val res      = customResultSync(desc.id, req, status, message, maybeCauseId, emptyBody, errorId)
        val ctx      = TransformerErrorContext(
          index = -1,
          snowflake = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).getOrElse(env.snowflakeGenerator.nextIdStr()),
          message = message,
          otoroshiResult = res,
          otoroshiResponse = HttpResponse(
            res.header.status,
            res.header.headers,
            res.newCookies.map(c =>
              DefaultWSCookie(
                name = c.name,
                value = c.value,
                domain = c.domain,
                path = Option(c.path),
                maxAge = c.maxAge.map(_.toLong),
                secure = c.secure,
                httpOnly = c.httpOnly
              )
            ),
            () => res.body.dataStream
          ),
          request = req,
          maybeCauseId = maybeCauseId,
          callAttempts = callAttempts,
          descriptor = desc,
          apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
          user = attrs.get(otoroshi.plugins.Keys.UserKey),
          config = Json.obj(),
          attrs = attrs
        )
        // TODO: make it work with transformError
        val finalRes = res // Await.result(desc.transformError(ctx)(env, ec, env.otoroshiMaterializer), 10.seconds)
        if (sendEvent)
          sendAnalytics(
            finalRes.header.headers.toSeq.map(Header.apply),
            errorId,
            message,
            status,
            req,
            maybeDescriptor,
            maybeCauseId,
            duration,
            overhead,
            cbDuration,
            callAttempts,
            emptyBody,
            sendEvent,
            attrs,
            maybeRoute
          )
        finalRes
      }
      case (_, Some(route)) => {
        val res      = customResultSync(route.id, req, status, message, maybeCauseId, emptyBody, errorId)
        val ctx      = NgTransformerErrorContext(
          snowflake = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).getOrElse(env.snowflakeGenerator.nextIdStr()),
          message = message,
          otoroshiResponse = NgPluginHttpResponse(
            res.header.status,
            res.header.headers,
            res.newCookies.map(c =>
              DefaultWSCookie(
                name = c.name,
                value = c.value,
                domain = c.domain,
                path = Option(c.path),
                maxAge = c.maxAge.map(_.toLong),
                secure = c.secure,
                httpOnly = c.httpOnly
              )
            ),
            res.body.dataStream
          ),
          request = req,
          maybeCauseId = maybeCauseId,
          callAttempts = callAttempts,
          route = route,
          apikey = attrs.get(otoroshi.plugins.Keys.ApiKeyKey),
          user = attrs.get(otoroshi.plugins.Keys.UserKey),
          config = Json.obj(),
          attrs = attrs,
          report = attrs.get(otoroshi.next.plugins.Keys.ReportKey).get
        )
        // TODO: make it work with transformError
        val finalRes = res // Await.result(route.transformError(ctx)(env, ec, env.otoroshiMaterializer), 10.seconds)
        if (sendEvent)
          sendAnalytics(
            finalRes.header.headers.toSeq.map(Header.apply),
            errorId,
            message,
            status,
            req,
            maybeDescriptor,
            maybeCauseId,
            duration,
            overhead,
            cbDuration,
            callAttempts,
            emptyBody,
            sendEvent,
            attrs,
            maybeRoute
          )
        finalRes
      }
      case _                => {
        val resp = standardResult(req, status, message, maybeCauseId, emptyBody)
        if (sendEvent)
          sendAnalytics(
            resp.header.headers.toSeq.map(Header.apply),
            errorId,
            message,
            status,
            req,
            maybeDescriptor,
            maybeCauseId,
            duration,
            overhead,
            cbDuration,
            callAttempts,
            emptyBody,
            sendEvent,
            attrs,
            maybeRoute
          )
        resp
      }
    }
  }
}
