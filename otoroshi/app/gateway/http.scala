package otoroshi.gateway

import akka.actor.ActorRef
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.models.{BestResponseTime, RemainingQuotas, SecComVersion, WeightedBestResponseTime}
import org.joda.time.DateTime
import otoroshi.el.TargetExpressionLanguage
import otoroshi.script.Implicits._
import otoroshi.script.{
  TransformerRequestBodyContext,
  TransformerRequestContext,
  TransformerResponseBodyContext,
  TransformerResponseContext
}
import otoroshi.utils.UrlSanitizer
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json.{JsArray, JsString, JsValue, Json}
import play.api.libs.streams.Accumulator
import play.api.libs.ws.{DefaultWSCookie, EmptyBody, SourceBody}
import play.api.mvc.Results.{BadGateway, Forbidden, HttpVersionNotSupported, NotFound, Status}
import play.api.mvc._
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.http.{HeadersHelper, WSCookieWithSameSite}
import otoroshi.utils.http.Implicits._
import otoroshi.utils.streams.MaxLengthLimiter

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

class HttpHandler()(implicit env: Env) {

  implicit lazy val currentEc           = env.otoroshiExecutionContext
  implicit lazy val currentScheduler    = env.otoroshiScheduler
  implicit lazy val currentSystem       = env.otoroshiActorSystem
  implicit lazy val currentMaterializer = env.otoroshiMaterializer

  lazy val logger = Logger("otoroshi-http-handler")

  val sourceBodyParser = BodyParser("Http BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def rawForwardCall(
      reverseProxyAction: ReverseProxyAction,
      analyticsQueue: ActorRef,
      snowMonkey: SnowMonkey,
      headersInFiltered: Seq[String],
      headersOutFiltered: Seq[String]
  ): (RequestHeader, Source[ByteString, _]) => Future[Result] = { (req, body) =>
    {
      reverseProxyAction
        .async[Result](
          ReverseProxyActionContext(req, body, snowMonkey, logger),
          false,
          c => actuallyCallDownstream(c, analyticsQueue, headersInFiltered, headersOutFiltered)
        )
        .map {
          case Left(r)  => r
          case Right(r) => r
        }
    }
  }

  def forwardCall(
      actionBuilder: ActionBuilder[Request, AnyContent],
      reverseProxyAction: ReverseProxyAction,
      analyticsQueue: ActorRef,
      snowMonkey: SnowMonkey,
      headersInFiltered: Seq[String],
      headersOutFiltered: Seq[String]
  ) =
    actionBuilder.async(sourceBodyParser) { req =>
      reverseProxyAction
        .async[Result](
          ReverseProxyActionContext(req, req.body, snowMonkey, logger),
          false,
          c => actuallyCallDownstream(c, analyticsQueue, headersInFiltered, headersOutFiltered)
        )
        .map {
          case Left(r)  => r
          case Right(r) => r
        }
    }

  def actuallyCallDownstream(
      ctx: ActualCallContext,
      analyticsQueue: ActorRef,
      headersInFiltered: Seq[String],
      headersOutFiltered: Seq[String]
  ): Future[Either[Result, Result]] = {

    val ActualCallContext(
      req,
      descriptor,
      _target,
      apiKey,
      paUsr,
      jwtInjection,
      snowMonkeyContext,
      snowflake,
      attrs,
      elCtx,
      globalConfig,
      withTrackingCookies,
      bodyAlreadyConsumed,
      requestBody,
      secondStart,
      firstOverhead,
      cbDuration,
      callAttempts
    ) = ctx

    val counterIn  = attrs.get(otoroshi.plugins.Keys.RequestCounterInKey).get
    val counterOut = attrs.get(otoroshi.plugins.Keys.RequestCounterOutKey).get
    val canaryId   = attrs.get(otoroshi.plugins.Keys.RequestCanaryIdKey).get
    val callDate   = attrs.get(otoroshi.plugins.Keys.RequestTimestampKey).get
    val start      = attrs.get(otoroshi.plugins.Keys.RequestStartKey).get

    val requestTimestamp       = callDate.toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
    val jti                    = IdGenerator.uuid
    val stateValue             = IdGenerator.extendedToken(128)
    val stateToken: String     = descriptor.secComVersion match {
      case SecComVersion.V1 => stateValue
      case SecComVersion.V2 =>
        OtoroshiClaim(
          iss = env.Headers.OtoroshiIssuer,
          sub = env.Headers.OtoroshiIssuer,
          aud = descriptor.name,
          exp = DateTime
            .now()
            .plus(descriptor.secComTtl.toMillis)
            .toDate
            .getTime,
          iat = DateTime.now().toDate.getTime,
          jti = jti
        ).withClaim("state", stateValue)
          .serialize(descriptor.algoChallengeFromOtoToBack)
    }
    val rawUri                 = req.relativeUri.substring(1)
    val uriParts               = rawUri.split("/").toSeq
    val uri: String            = descriptor.maybeStrippedUri(req, rawUri)
    val scheme                 =
      if (descriptor.redirectToLocal) descriptor.localScheme else _target.scheme
    val host                   = TargetExpressionLanguage(
      if (descriptor.redirectToLocal)
        descriptor.localHost
      else _target.host,
      Some(req),
      Some(descriptor),
      apiKey,
      paUsr,
      elCtx,
      attrs,
      env
    )
    val root                   = descriptor.root
    val url                    = TargetExpressionLanguage(
      s"$scheme://$host$root$uri",
      Some(req),
      Some(descriptor),
      apiKey,
      paUsr,
      elCtx,
      attrs,
      env
    )
    lazy val currentReqHasBody = req.theHasBody
    // val queryString = req.queryString.toSeq.flatMap { case (key, values) => values.map(v => (key, v)) }
    val fromOtoroshi           = req.headers
      .get(env.Headers.OtoroshiRequestId)
      .orElse(req.headers.get(env.Headers.OtoroshiGatewayParentRequest))
    val promise                = Promise[ProxyDone]

    val claim = descriptor.generateInfoToken(apiKey, paUsr, Some(req))
    logger.trace(s"Claim is : $claim")

    val stateResponseHeaderName = descriptor.secComHeaders.stateResponseName
      .getOrElse(env.Headers.OtoroshiStateResp)

    val headersIn: Seq[(String, String)] = HeadersHelper.composeHeadersIn(
      descriptor = descriptor,
      req = req,
      apiKey = apiKey,
      paUsr = paUsr,
      elCtx = elCtx,
      currentReqHasBody = currentReqHasBody,
      headersInFiltered = headersInFiltered,
      snowflake = snowflake,
      requestTimestamp = requestTimestamp,
      host = host,
      claim = claim,
      stateToken = stateToken,
      fromOtoroshi = fromOtoroshi,
      snowMonkeyContext = snowMonkeyContext,
      jwtInjection = jwtInjection,
      attrs = attrs
    )

    val lazySource = Source.single(ByteString.empty).flatMapConcat { _ =>
      bodyAlreadyConsumed.compareAndSet(false, true)
      requestBody
        .concat(snowMonkeyContext.trailingRequestBodyStream)
        .map(bs => {
          // meterIn.mark(bs.length)
          counterIn.addAndGet(bs.length)
          bs
        })
    }

    val overhead                        = (System.currentTimeMillis() - secondStart) + firstOverhead
    if (overhead > env.overheadThreshold) {
      HighOverheadAlert(
        `@id` = env.snowflakeGenerator.nextIdStr(),
        limitOverhead = env.overheadThreshold,
        currentOverhead = overhead,
        serviceDescriptor = descriptor,
        target = Location(
          scheme = req.theProtocol,
          host = req.theHost,
          uri = req.relativeUri
        )
      ).toAnalytics()
    }
    val quotas: Future[RemainingQuotas] =
      apiKey
        .map(_.updateQuotas())
        .getOrElse(FastFuture.successful(RemainingQuotas()))
    promise.future.andThen {
      case Success(resp) => {

        val actualDuration: Long = System.currentTimeMillis() - start
        val duration: Long       =
          if (descriptor.id == env.backOfficeServiceId && actualDuration > 300L)
            300L
          else actualDuration

        analyticsQueue ! AnalyticsQueueEvent(
          descriptor,
          duration,
          overhead,
          counterIn.get(),
          counterOut.get(),
          resp.upstreamLatency,
          globalConfig
        )

        descriptor.targetsLoadBalancing match {
          case BestResponseTime            =>
            BestResponseTime.incrementAverage(descriptor, _target, duration)
          case WeightedBestResponseTime(_) =>
            BestResponseTime.incrementAverage(descriptor, _target, duration)
          case _                           =>
        }

        quotas.andThen {
          case Success(q) => {
            val fromLbl          =
              req.headers
                .get(env.Headers.OtoroshiVizFromLabel)
                .getOrElse("internet")
            val viz: OtoroshiViz = OtoroshiViz(
              to = descriptor.id,
              toLbl = descriptor.name,
              from = req.headers
                .get(env.Headers.OtoroshiVizFrom)
                .getOrElse("internet"),
              fromLbl = fromLbl,
              fromTo = s"$fromLbl###${descriptor.name}"
            )
            val evt              = GatewayEvent(
              `@id` = env.snowflakeGenerator.nextIdStr(),
              reqId = snowflake,
              parentReqId = fromOtoroshi,
              `@timestamp` = DateTime.now(),
              `@calledAt` = callDate,
              protocol = req.version,
              to = Location(
                scheme = req.theProtocol,
                host = req.theHost,
                uri = req.relativeUri
              ),
              target = Location(
                scheme = scheme,
                host = host,
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
                dataIn = counterIn.get(),
                dataOut = counterOut.get()
              ),
              status = resp.status,
              headers = req.headers.toSimpleMap.toSeq.map(Header.apply),
              headersOut = resp.headersOut,
              otoroshiHeadersIn = resp.otoroshiHeadersIn,
              otoroshiHeadersOut = resp.otoroshiHeadersOut,
              extraInfos = attrs.get(otoroshi.plugins.Keys.GatewayEventExtraInfosKey),
              identity = apiKey
                .map(k =>
                  Identity(
                    identityType = "APIKEY",
                    identity = k.clientId,
                    label = k.clientName
                  )
                )
                .orElse(
                  paUsr.map(k =>
                    Identity(
                      identityType = "PRIVATEAPP",
                      identity = k.email,
                      label = k.name
                    )
                  )
                ),
              responseChunked = resp.isChunked,
              `@serviceId` = descriptor.id,
              `@service` = descriptor.name,
              descriptor = Some(descriptor),
              `@product` = descriptor.metadata.getOrElse("product", "--"),
              remainingQuotas = q,
              viz = Some(viz),
              clientCertChain = req.clientCertChainPem,
              err = false,
              userAgentInfo = attrs.get[JsValue](otoroshi.plugins.Keys.UserAgentInfoKey),
              geolocationInfo = attrs.get[JsValue](otoroshi.plugins.Keys.GeolocationInfoKey),
              extraAnalyticsData = attrs.get[JsValue](otoroshi.plugins.Keys.ExtraAnalyticsDataKey)
            )
            evt.toAnalytics()
            if (descriptor.logAnalyticsOnServer) {
              evt.log()(env, env.analyticsExecutionContext) // pressure EC
            }
          }
        }(env.analyticsExecutionContext) // pressure EC
      }
    }(env.analyticsExecutionContext) // pressure EC
    //.andThen {
    //  case _ => env.datastores.requestsDataStore.decrementProcessedRequests()
    //}
    val wsCookiesIn                     = req.cookies.toSeq.map(c =>
      WSCookieWithSameSite(
        name = c.name,
        value = c.value,
        domain = c.domain,
        path = Option(c.path),
        maxAge = c.maxAge.map(_.toLong),
        secure = c.secure,
        httpOnly = c.httpOnly,
        sameSite = c.sameSite
      )
    )
    val rawRequest                      = otoroshi.script.HttpRequest(
      url = s"${req.theProtocol}://${req.theHost}${req.relativeUri}",
      method = req.method,
      headers = req.headers.toSimpleMap,
      cookies = wsCookiesIn,
      version = req.version,
      clientCertificateChain = req.clientCertificateChain,
      target = None,
      claims = claim
    )
    val otoroshiRequest                 = otoroshi.script.HttpRequest(
      url = url,
      method = req.method,
      headers = headersIn.toMap,
      cookies = wsCookiesIn,
      version = req.version,
      clientCertificateChain = req.clientCertificateChain,
      target = Some(_target),
      claims = claim
    )
    val transReqCtx                     = TransformerRequestContext(
      index = -1,
      snowflake = snowflake,
      rawRequest = rawRequest,
      otoroshiRequest = otoroshiRequest,
      descriptor = descriptor,
      apikey = apiKey,
      user = paUsr,
      request = req,
      config = descriptor.transformerConfig,
      attrs = attrs
    )
    val finalRequest                    = descriptor
      .transformRequest(transReqCtx)
    val finalBody                       = descriptor.transformRequestBody(
      TransformerRequestBodyContext(
        index = -1,
        snowflake = snowflake,
        rawRequest = rawRequest,
        otoroshiRequest = otoroshiRequest,
        descriptor = descriptor,
        apikey = apiKey,
        user = paUsr,
        request = req,
        config = descriptor.transformerConfig,
        body = lazySource,
        attrs = attrs
      )
    )
    finalRequest
      .flatMap {
        case Left(badResult)    => {
          quotas.fast.map { remainingQuotas =>
            val _headersOut: Seq[(String, String)] =
              HeadersHelper.composeHeadersOutBadResult(
                descriptor = descriptor,
                req = req,
                badResult = badResult,
                apiKey = apiKey,
                paUsr = paUsr,
                elCtx = elCtx,
                snowflake = snowflake,
                requestTimestamp = requestTimestamp,
                headersOutFiltered = headersOutFiltered,
                overhead = overhead,
                upstreamLatency = 0L,
                canaryId = canaryId,
                remainingQuotas = remainingQuotas,
                attrs = attrs
              )
            promise.trySuccess(
              ProxyDone(
                badResult.header.status,
                false,
                0,
                headersOut = badResult.header.headers.toSeq.map(Header.apply),
                otoroshiHeadersOut = _headersOut.map(Header.apply),
                otoroshiHeadersIn = headersIn.map(Header.apply)
              )
            )
            badResult.withHeaders(_headersOut: _*)
          }
        }
        case Right(httpRequest) => {
          val upstreamStart = System.currentTimeMillis()
          // Stream IN
          val body          =
            if (currentReqHasBody) SourceBody(finalBody)
            else EmptyBody

          val finalTarget = httpRequest.target.getOrElse(_target)
          attrs.put(otoroshi.plugins.Keys.RequestTargetKey -> finalTarget)

          val clientReq = descriptor.useAkkaHttpClient match {
            case _ if finalTarget.mtlsConfig.mtls =>
              env.gatewayClient.akkaUrlWithTarget(
                UrlSanitizer.sanitize(httpRequest.url),
                finalTarget,
                descriptor.clientConfig
              )
            case true                             =>
              env.gatewayClient.akkaUrlWithTarget(
                UrlSanitizer.sanitize(httpRequest.url),
                finalTarget,
                descriptor.clientConfig
              )
            case false                            =>
              env.gatewayClient.urlWithTarget(
                UrlSanitizer.sanitize(httpRequest.url),
                finalTarget,
                descriptor.clientConfig
              )
          }

          val builder = clientReq
            .withRequestTimeout(
              descriptor.clientConfig.extractTimeout(req.relativeUri, _.callAndStreamTimeout, _.callAndStreamTimeout)
            )
            //.withRequestTimeout(env.requestTimeout) // we should monitor leaks
            .withMethod(httpRequest.method)
            // .withHttpHeaders(httpRequest.headers.toSeq.filterNot(_._1 == "Cookie"): _*)
            .withHttpHeaders(
              HeadersHelper
                .addClaims(httpRequest.headers, httpRequest.claims, descriptor)
                .filterNot(_._1 == "Cookie"): _*
            )
            .withCookies(wsCookiesIn: _*)
            .withFollowRedirects(false)
            .withMaybeProxyServer(
              descriptor.clientConfig.proxy.orElse(globalConfig.proxies.services)
            )

          // because writeableOf_WsBody always add a 'Content-Type: application/octet-stream' header
          val builderWithBody = if (currentReqHasBody) {
            builder.withBody(body)
          } else {
            builder
          }

          builderWithBody
            .stream()
            .flatMap(resp => quotas.fast.map(q => (resp, q)))
            .flatMap { tuple =>
              val isUp                                  = true
              val (resp, remainingQuotas)               = tuple
              // val responseHeader          = ByteString(s"HTTP/1.1 ${resp.headers.status}")
              val headers                               = resp.headers.mapValues(_.head)
              val _headersForOut: Seq[(String, String)] =
                resp.headers.toSeq.flatMap(c =>
                  c._2.map(v => (c._1, v))
                ) //.map(tuple => (tuple._1, tuple._2.mkString(","))) //.toSimpleMap // .mapValues(_.head)
              val rawResponse         = otoroshi.script.HttpResponse(
                status = resp.status,
                headers = headers.toMap,
                cookies = resp.cookies
              )
              val stateRespHeaderName = descriptor.secComHeaders.stateResponseName
                .getOrElse(env.Headers.OtoroshiStateResp)
              val stateResp           = headers
                .get(stateRespHeaderName)
                .orElse(headers.get(stateRespHeaderName.toLowerCase))
              if (
                (descriptor.enforceSecureCommunication && descriptor.sendStateChallenge)
                && !descriptor.isUriExcludedFromSecuredCommunication("/" + uri)
                && !ReverseProxyActionHelper.stateRespValid(stateValue, stateResp, jti, descriptor)
              ) {
                // && !headers.get(stateRespHeaderName).contains(state)) {
                resp.ignore()
                if (
                  resp.status == 404 && headers
                    .get("X-CleverCloudUpgrade")
                    .contains("true")
                ) {
                  Errors.craftResponseResult(
                    "No service found for the specified target host, the service descriptor should be verified !",
                    NotFound,
                    req,
                    Some(descriptor),
                    Some("errors.no.service.found"),
                    duration = System.currentTimeMillis - start,
                    overhead = (System
                        .currentTimeMillis() - secondStart) + firstOverhead,
                    cbDuration = cbDuration,
                    callAttempts = callAttempts,
                    attrs = attrs
                  )
                } else if (isUp) {
                  val exchange = Json.stringify(
                    Json.obj(
                      "uri"           -> req.relativeUri,
                      "url"           -> url,
                      "state"         -> stateValue,
                      "reveivedState" -> JsString(stateResp.getOrElse("--")),
                      "claim"         -> claim
                        .serialize(descriptor.algoInfoFromOtoToBack)(env),
                      "method"        -> req.method,
                      "query"         -> req.rawQueryString,
                      "status"        -> resp.status,
                      "headersIn"     -> JsArray(
                        req.headers.toSimpleMap
                          .map(t => Json.obj("name" -> t._1, "value" -> t._2))
                          .toSeq
                      ),
                      "headersOut"    -> JsArray(
                        headers
                          .map(t => Json.obj("name" -> t._1, "values" -> t._2))
                          .toSeq
                      )
                    )
                  )
                  logger
                    .error(
                      s"\n\nError while talking with downstream service :(\n\n$exchange\n\n"
                    )
                  Errors.craftResponseResult(
                    "Downstream microservice does not seems to be secured. Cancelling request !",
                    BadGateway,
                    req,
                    Some(descriptor),
                    Some("errors.service.not.secured"),
                    duration = System.currentTimeMillis - start,
                    overhead = (System
                        .currentTimeMillis() - secondStart) + firstOverhead,
                    cbDuration = cbDuration,
                    callAttempts = callAttempts,
                    attrs = attrs
                  )
                } else {
                  Errors.craftResponseResult(
                    "The service seems to be down :( come back later",
                    Forbidden,
                    req,
                    Some(descriptor),
                    Some("errors.service.down"),
                    duration = System.currentTimeMillis - start,
                    overhead = (System
                        .currentTimeMillis() - secondStart) + firstOverhead,
                    cbDuration = cbDuration,
                    callAttempts = callAttempts,
                    attrs = attrs
                  )
                }
              } else {
                val upstreamLatency                    = System.currentTimeMillis() - upstreamStart
                val _headersOut: Seq[(String, String)] =
                  HeadersHelper.composeHeadersOut(
                    descriptor = descriptor,
                    req = req,
                    resp = resp,
                    apiKey = apiKey,
                    paUsr = paUsr,
                    elCtx = elCtx,
                    snowflake = snowflake,
                    requestTimestamp = requestTimestamp,
                    headersOutFiltered = headersOutFiltered,
                    overhead = overhead,
                    upstreamLatency = upstreamLatency,
                    canaryId = canaryId,
                    remainingQuotas = remainingQuotas,
                    attrs = attrs
                  )

                val otoroshiResponse = otoroshi.script.HttpResponse(
                  status = resp.status,
                  headers = _headersOut.toMap,
                  cookies = resp.cookies
                )
                descriptor
                  .transformResponse(
                    TransformerResponseContext(
                      index = -1,
                      snowflake = snowflake,
                      rawResponse = rawResponse,
                      otoroshiResponse = otoroshiResponse,
                      descriptor = descriptor,
                      apikey = apiKey,
                      user = paUsr,
                      request = req,
                      config = descriptor.transformerConfig,
                      attrs = attrs
                    )
                  )
                  .flatMap {
                    case Left(badResult)     => {
                      resp.ignore()
                      FastFuture.successful(badResult)
                    }
                    case Right(httpResponse) => {
                      val headersOut                  = httpResponse.headers.toSeq
                      val contentType: Option[String] = httpResponse.headers
                        .get("Content-Type")
                        .orElse(httpResponse.headers.get("content-type"))

                      val noContentLengthHeader: Boolean =
                        resp.contentLength.isEmpty
                      val hasChunkedHeader: Boolean      = resp
                        .header("Transfer-Encoding")
                        .orElse(httpResponse.headers.get("Transfer-Encoding"))
                        .exists(h => h.toLowerCase().contains("chunked"))
                      val isChunked: Boolean             = resp.isChunked() match {
                        case Some(chunked)                                                                         => chunked
                        case None if !env.emptyContentLengthIsChunked                                              =>
                          hasChunkedHeader // false
                        case None if env.emptyContentLengthIsChunked && hasChunkedHeader                           =>
                          true
                        case None if env.emptyContentLengthIsChunked && !hasChunkedHeader && noContentLengthHeader =>
                          true
                        case _                                                                                     => false
                      }

                      val theStream: Source[ByteString, _] = resp.bodyAsSource
                        .concat(snowMonkeyContext.trailingResponseBodyStream)
                        .alsoTo(Sink.onComplete {
                          case Success(_) =>
                            // debugLogger.trace(s"end of stream for ${protocol}://${req.host}${req.relativeUri}")
                            promise.trySuccess(
                              ProxyDone(
                                httpResponse.status,
                                isChunked,
                                upstreamLatency,
                                headersOut = resp.headers.mapValues(_.head).toSeq.map(Header.apply),
                                otoroshiHeadersOut = headersOut.map(Header.apply),
                                otoroshiHeadersIn = headersIn.map(Header.apply)
                              )
                            )
                          case Failure(e) =>
                            if (
                              !(req.relativeUri
                                .startsWith("/api/live/global") && e.getMessage == "Connection reset by peer")
                            ) {
                              logger.error(
                                s"error while transfering stream for ${req.theProtocol}://${req.theHost}${req.relativeUri}",
                                e
                              )
                            }
                            resp.ignore()
                            promise.trySuccess(
                              ProxyDone(
                                httpResponse.status,
                                isChunked,
                                upstreamLatency,
                                headersOut = resp.headers.mapValues(_.head).toSeq.map(Header.apply),
                                otoroshiHeadersOut = headersOut.map(Header.apply),
                                otoroshiHeadersIn = headersIn.map(Header.apply)
                              )
                            )
                        })
                        .map { bs =>
                          counterOut.addAndGet(bs.length)
                          bs
                        }

                      val finalStream = descriptor.transformResponseBody(
                        TransformerResponseBodyContext(
                          index = -1,
                          snowflake = snowflake,
                          rawResponse = rawResponse,
                          otoroshiResponse = otoroshiResponse,
                          descriptor = descriptor,
                          apikey = apiKey,
                          user = paUsr,
                          request = req,
                          config = descriptor.transformerConfig,
                          body = theStream,
                          attrs = attrs
                        )
                      )

                      val cookies = httpResponse.cookies.map {
                        case c: WSCookieWithSameSite =>
                          Cookie(
                            name = c.name,
                            value = c.value,
                            maxAge = c.maxAge.map(_.toInt),
                            path = c.path.getOrElse("/"),
                            domain = c.domain,
                            secure = c.secure,
                            httpOnly = c.httpOnly,
                            sameSite = c.sameSite
                          )
                        case c                       =>
                          Cookie(
                            name = c.name,
                            value = c.value,
                            maxAge = c.maxAge.map(_.toInt),
                            path = c.path.getOrElse("/"),
                            domain = c.domain,
                            secure = c.secure,
                            httpOnly = c.httpOnly,
                            sameSite = None
                          )
                      }

                      if (req.version == "HTTP/1.0") {
                        if (descriptor.allowHttp10) {
                          logger.warn(
                            s"HTTP/1.0 request, storing temporary result in memory :( (${req.theProtocol}://${req.theHost}${req.relativeUri})"
                          )
                          finalStream
                            .via(
                              MaxLengthLimiter(
                                globalConfig.maxHttp10ResponseSize.toInt,
                                str => logger.warn(str)
                              )
                            )
                            .runWith(
                              Sink.reduce[ByteString]((bs, n) => bs.concat(n))
                            )
                            .fast
                            .flatMap { body =>
                              val response: Result = Status(httpResponse.status)(body)
                                .withHeaders(
                                  headersOut.filterNot { h =>
                                    val lower = h._1.toLowerCase()
                                    lower == "content-type" || lower == "set-cookie" || lower == "transfer-encoding"
                                  }: _*
                                )
                                .withCookies(
                                  withTrackingCookies ++ jwtInjection.additionalCookies
                                    .map(t => Cookie(t._1, t._2)) ++ cookies: _*
                                )
                              contentType match {
                                case None      => descriptor.gzip.handleResult(req, response)
                                case Some(ctp) =>
                                  descriptor.gzip.handleResult(req, response.as(ctp))
                              }
                            }
                        } else {
                          resp.ignore()
                          Errors.craftResponseResult(
                            "You cannot use HTTP/1.0 here",
                            HttpVersionNotSupported,
                            req,
                            Some(descriptor),
                            Some("errors.http.10.not.allowed"),
                            duration = System.currentTimeMillis - start,
                            overhead = (System
                                .currentTimeMillis() - secondStart) + firstOverhead,
                            cbDuration = cbDuration,
                            callAttempts = callAttempts,
                            attrs = attrs
                          )
                        }
                      } else {
                        val response: Result = isChunked match {
                          case true  => {
                            // stream out
                            val res = Status(httpResponse.status)
                              .chunked(finalStream)
                              .withHeaders(
                                headersOut.filterNot { h =>
                                  val lower = h._1.toLowerCase()
                                  lower == "content-type" || lower == "set-cookie" || lower == "transfer-encoding"
                                }: _*
                              )
                              .withCookies(
                                (withTrackingCookies ++ jwtInjection.additionalCookies
                                  .map(t => Cookie(t._1, t._2)) ++ cookies): _*
                              )
                            contentType match {
                              case None      => res
                              case Some(ctp) => res.as(ctp)
                            }
                          }
                          case false => {
                            val contentLength: Option[Long] = httpResponse.headers
                              .get("Content-Length")
                              .orElse(httpResponse.headers.get("content-length"))
                              .orElse(resp.contentLengthStr)
                              .map(
                                _.toLong + snowMonkeyContext.trailingResponseBodySize
                              )
                            val actualContentLength: Long   =
                              contentLength.getOrElse(0L)
                            if (actualContentLength == 0L) {
                              // here, Play did not run the body because it's empty, so triggering things manually
                              logger
                                .debug(
                                  "Triggering promise as content length is 0"
                                )
                              promise.trySuccess(
                                ProxyDone(
                                  httpResponse.status,
                                  isChunked,
                                  upstreamLatency,
                                  headersOut = resp.headers.mapValues(_.head).toSeq.map(Header.apply),
                                  otoroshiHeadersOut = headersOut.map(Header.apply),
                                  otoroshiHeadersIn = headersIn.map(Header.apply)
                                )
                              )
                            }
                            // stream out
                            val res                         = Status(httpResponse.status)
                              .sendEntity(
                                HttpEntity.Streamed(
                                  finalStream,
                                  contentLength,
                                  contentType
                                )
                              )
                              .withHeaders(
                                headersOut.filterNot { h =>
                                  val lower = h._1.toLowerCase()
                                  lower == "content-type" || lower == "set-cookie" || lower == "transfer-encoding"
                                }: _*
                              )
                              .withCookies(
                                (withTrackingCookies ++ jwtInjection.additionalCookies
                                  .map(t => Cookie(t._1, t._2)) ++ cookies): _*
                              )
                            contentType match {
                              case None      => res
                              case Some(ctp) => res.as(ctp)
                            }
                          }
                        }
                        descriptor.gzip.handleResult(req, response)
                      }
                    }
                  }
              }
            }
        }
      }
      .map(Right.apply)
  }
}
