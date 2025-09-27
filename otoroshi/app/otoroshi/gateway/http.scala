package otoroshi.gateway

import org.apache.pekko.actor.{ActorRef, ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.util.FastFuture
import org.apache.pekko.http.scaladsl.util.FastFuture.*
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.apache.pekko.util.ByteString
import org.joda.time.DateTime
import otoroshi.el.TargetExpressionLanguage
import otoroshi.env.Env
import otoroshi.events.*
import otoroshi.models.*
import otoroshi.script.Implicits.*
import otoroshi.script.{TransformerRequestBodyContext, TransformerRequestContext, TransformerResponseBodyContext, TransformerResponseContext}
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.utils.UrlSanitizer
import otoroshi.utils.http.Implicits.*
import otoroshi.utils.http.RequestImplicits.*
import otoroshi.utils.http.{HeadersHelper, WSCookieWithSameSite}
import otoroshi.utils.streams.MaxLengthLimiter
import otoroshi.utils.syntax.implicits.BetterSyntax
import play.api.Logger
import play.api.http.HttpEntity
import play.api.libs.json.*
import play.api.libs.streams.Accumulator
import play.api.libs.ws.WSBodyWritables.*
import play.api.libs.ws.{DefaultWSCookie, EmptyBody, SourceBody}
import play.api.mvc.*
import play.api.mvc.Results.{BadGateway, Forbidden, HttpVersionNotSupported, NotFound, Status}
import play.api.mvc._
import otoroshi.security.{IdGenerator, OtoroshiClaim}
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.http.ResponseImplicits._
import otoroshi.utils.http.{HeadersHelper, WSCookieWithSameSite}
import otoroshi.utils.http.Implicits._
import otoroshi.utils.streams.MaxLengthLimiter
import otoroshi.utils.syntax.implicits.BetterSyntax

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

class HttpHandler()(using env: Env) {

  implicit lazy val currentEc: ExecutionContext       = env.otoroshiExecutionContext
  implicit lazy val currentScheduler: Scheduler       = env.otoroshiScheduler
  implicit lazy val currentSystem: ActorSystem        = env.otoroshiActorSystem
  implicit lazy val currentMaterializer: Materializer = env.otoroshiMaterializer

  lazy val logger: Logger = Logger("otoroshi-http-handler")

  val sourceBodyParser: BodyParser[Source[ByteString, ?]] = BodyParser("Http BodyParser") { _ =>
    Accumulator.source[ByteString].map(Right.apply)
  }

  def rawForwardCall(
      reverseProxyAction: ReverseProxyAction,
      analyticsQueue: ActorRef,
      snowMonkey: SnowMonkey,
      headersInFiltered: Seq[String],
      headersOutFiltered: Seq[String]
  ): (RequestHeader, Source[ByteString, ?]) => Future[Result] = { (req, body) =>
    {
      reverseProxyAction
        .async[Result](
          ReverseProxyActionContext(req, body, snowMonkey, logger),
          ws = false,
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
  ): Action[Source[ByteString, ?]] =
    actionBuilder.async(sourceBodyParser) { req =>
      env.metrics.withTimerAsync("handle-request")(
        reverseProxyAction
          .async[Result](
            ReverseProxyActionContext(req, req.body, snowMonkey, logger),
            ws = false,
            c => actuallyCallDownstream(c, analyticsQueue, headersInFiltered, headersOutFiltered)
          )
          .map {
            case Left(r)  => r
            case Right(r) => r
          }
      )
    }

  def forwardAction(
      reverseProxyAction: ReverseProxyAction,
      analyticsQueue: ActorRef,
      snowMonkey: SnowMonkey,
      headersInFiltered: Seq[String],
      headersOutFiltered: Seq[String]
  ): Request[Source[ByteString, ?]] => Future[Result] = (req: Request[Source[ByteString, ?]]) => {
    reverseProxyAction
      .async[Result](
        ReverseProxyActionContext(req, req.body, snowMonkey, logger),
        ws = false,
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
      callAttempts,
      attempts,
      alreadyFailed
    ) = ctx

    val counterIn  = attrs.get(otoroshi.plugins.Keys.RequestCounterInKey).get
    val counterOut = attrs.get(otoroshi.plugins.Keys.RequestCounterOutKey).get
    val canaryId   = attrs.get(otoroshi.plugins.Keys.RequestCanaryIdKey).get
    val callDate   = attrs.get(otoroshi.plugins.Keys.RequestTimestampKey).get
    val start      = attrs.get(otoroshi.plugins.Keys.RequestStartKey).get

    val requestTimestamp                                    = callDate.toString("yyyy-MM-dd'T'HH:mm:ss.SSSZZ")
    val jti                                                 = IdGenerator.uuid
    val stateValue                                          = IdGenerator.extendedToken(128)
    val stateToken: String                                  = descriptor.secComVersion match {
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
    val rawUri                                              = req.relativeUri.substring(1)
    val uriParts                                            = rawUri.split("/").toSeq
    val uri: String                                         = descriptor.maybeStrippedUri(req, rawUri)
    val scheme                                              =
      if (descriptor.redirectToLocal) descriptor.localScheme else _target.scheme
    val host                                                = TargetExpressionLanguage(
      if (descriptor.redirectToLocal)
        descriptor.localHost
      else _target.host,
      Some(req),
      Some(descriptor),
      None,
      apiKey,
      paUsr,
      elCtx,
      attrs,
      env
    )
    val root                                                = descriptor.root
    val url                                                 = TargetExpressionLanguage(
      s"$scheme://$host$root$uri",
      Some(req),
      Some(descriptor),
      None,
      apiKey,
      paUsr,
      elCtx,
      attrs,
      env
    )
    lazy val (currentReqHasBody, shouldInjectContentLength) = req.theHasBodyWithoutLength
    // val queryString = req.queryString.toSeq.flatMap { case (key, values) => values.map(v => (key, v)) }
    val fromOtoroshi                                        = req.headers
      .get(env.Headers.OtoroshiRequestId)
      .orElse(req.headers.get(env.Headers.OtoroshiGatewayParentRequest))
    val promise                                             = Promise[ProxyDone]()

    val claim = descriptor.generateInfoToken(apiKey, paUsr, Some(req))
    if (logger.isTraceEnabled) logger.trace(s"Claim is : $claim")
    attrs.put(otoroshi.plugins.Keys.OtoTokenKey -> claim.payload)

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
        serviceDescriptor = descriptor.some,
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
    promise.future.andThen { case Success(resp) =>
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

      quotas.andThen { case Success(q) =>
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
          backendDuration = attrs.get(otoroshi.plugins.Keys.BackendDurationKey).getOrElse(-1L),
          duration = duration,
          overhead = overhead,
          cbDuration = cbDuration,
          overheadWoCb = Math.abs(overhead - cbDuration),
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
                label = k.clientName,
                tags = k.tags,
                metadata = k.metadata
              )
            )
            .orElse(
              paUsr.map(k =>
                Identity(
                  identityType = "PRIVATEAPP",
                  identity = k.email,
                  label = k.name,
                  tags = k.tags,
                  metadata = k.metadata
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
          err = attrs.get(otoroshi.plugins.Keys.GwErrorKey).isDefined,
          gwError = attrs.get(otoroshi.plugins.Keys.GwErrorKey).map(_.message),
          userAgentInfo = attrs.get[JsValue](otoroshi.plugins.Keys.UserAgentInfoKey),
          geolocationInfo = attrs.get[JsValue](otoroshi.plugins.Keys.GeolocationInfoKey),
          extraAnalyticsData = attrs.get(otoroshi.plugins.Keys.ExtraAnalyticsDataKey),
          matchedJwtVerifier = attrs.get(otoroshi.plugins.Keys.JwtVerifierKey)
        )
        evt.toAnalytics()
        if (descriptor.logAnalyticsOnServer) {
          evt.log()(using env, env.analyticsExecutionContext) // pressure EC
        }
      }(using env.analyticsExecutionContext) // pressure EC
    }(using env.analyticsExecutionContext) // pressure EC
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
      claims = claim,
      body = () => requestBody
    )
    val otoroshiRequest                 = otoroshi.script.HttpRequest(
      url = url,
      method = req.method,
      headers = headersIn.toMap,
      cookies = wsCookiesIn,
      version = req.version,
      clientCertificateChain = req.clientCertificateChain,
      target = Some(_target),
      claims = claim,
      body = () => requestBody
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
        case Left(badResult)    =>
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
            badResult.withHeaders(_headersOut*)
          }
        case Right(httpRequest) =>
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

          val extractedTimeout =
            descriptor.clientConfig.extractTimeout(req.relativeUri, _.callAndStreamTimeout, _.callAndStreamTimeout)
          if (ClientConfig.logger.isDebugEnabled)
            ClientConfig.logger.debug(s"[gateway] using callAndStreamTimeout: $extractedTimeout")
          val builder          = clientReq
            .withRequestTimeout(extractedTimeout)
            .withFailureIndicator(alreadyFailed)
            //.withRequestTimeout(env.requestTimeout) // we should monitor leaks
            .withMethod(httpRequest.method)
            // .withHttpHeaders(httpRequest.headers.toSeq.filterNot(_._1 == "Cookie")*)
            .withHttpHeaders(
              HeadersHelper
                .addClaims(httpRequest.headers, httpRequest.claims, descriptor)
                .filterNot(_._1 == "Cookie")*
            )
            .withCookies(wsCookiesIn*)
            .withFollowRedirects(false)
            .withMaybeProxyServer(
              descriptor.clientConfig.proxy.orElse(globalConfig.proxies.services)
            )

          // because writeableOf_WsBody always add a 'Content-Type: application/octet-stream' header
          val builderWithBody = if (currentReqHasBody) {
            if (shouldInjectContentLength) {
              builder.addHttpHeaders("Content-Length" -> "0").withBody(body)
            } else {
              builder.withBody(body)
            }
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
              val headers                               = resp.headers.view.mapValues(_.head)
              val _headersForOut: Seq[(String, String)] =
                resp.headers.toSeq.flatMap(c =>
                  c._2.map(v => (c._1, v))
                ) //.map(tuple => (tuple._1, tuple._2.mkString(","))) //.toSimpleMap // .mapValues(_.head)
              val rawResponse         = otoroshi.script.HttpResponse(
                status = resp.status,
                headers = headers.toMap,
                cookies = resp.safeCookies(env),
                body = () => resp.bodyAsSource
              )
              val stateRespHeaderName = descriptor.secComHeaders.stateResponseName
                .getOrElse(env.Headers.OtoroshiStateResp)
              val stateResp           = headers
                .get(stateRespHeaderName)
                .orElse(headers.get(stateRespHeaderName.toLowerCase))
              ReverseProxyActionHelper.stateRespValidM(stateValue, stateResp, jti, descriptor, uri, req) match {
                case Left(stateRespInvalid) =>
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
                    logger.error(stateRespInvalid.errorMessage(resp.status, resp.headers.view.mapValues(_.last).toMap))
                    val extraInfos    = attrs
                      .get(otoroshi.plugins.Keys.GatewayEventExtraInfosKey)
                      .map(_.as[JsObject])
                      .getOrElse(Json.obj())
                    val newExtraInfos =
                      extraInfos ++ Json.obj(
                        "stateRespInvalid" -> stateRespInvalid
                          .exchangePayload(resp.status, resp.headers.view.mapValues(_.last).toMap)
                      )
                    attrs.put(otoroshi.plugins.Keys.GatewayEventExtraInfosKey -> newExtraInfos)
                    Errors.craftResponseResult(
                      "Backend server does not seems to be secured. Cancelling request !",
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
                case Right(_)               =>
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
                    cookies = resp.safeCookies(env),
                    body = () => resp.bodyAsSource
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
                      case Left(badResult)     =>
                        resp.ignore()
                        FastFuture.successful(badResult)
                      case Right(httpResponse) =>
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
                        val isContentLengthZero: Boolean   =
                          resp.header("Content-Length").orElse(httpResponse.headers.get("Content-Length")).contains("0")
                        val isChunked: Boolean             = resp.isChunked match {
                          case _ if isContentLengthZero                                                              => false
                          case Some(chunked)                                                                         => chunked
                          case None if !env.emptyContentLengthIsChunked                                              =>
                            hasChunkedHeader // false
                          case None if env.emptyContentLengthIsChunked && hasChunkedHeader                           =>
                            true
                          case None if env.emptyContentLengthIsChunked && !hasChunkedHeader && noContentLengthHeader =>
                            true
                          case _                                                                                     => false
                        }

                        val theStream: Source[ByteString, ?] = resp.bodyAsSource
                          .concat(snowMonkeyContext.trailingResponseBodyStream)
                          .alsoTo(Sink.onComplete {
                            case Success(_) =>
                              // debugLogger.trace(s"end of stream for ${protocol}://${req.host}${req.relativeUri}")
                              promise.trySuccess(
                                ProxyDone(
                                  httpResponse.status,
                                  isChunked,
                                  upstreamLatency,
                                  headersOut = resp.headers.view.mapValues(_.head).toSeq.map(Header.apply),
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
                                  headersOut = resp.headers.view.mapValues(_.head).toSeq.map(Header.apply),
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
                            val sameSite: Option[Cookie.SameSite] = resp.headers.get("Set-Cookie").flatMap { values =>
                              values
                                .find { sc =>
                                  sc.startsWith(s"${c.name}=${c.value}")
                                }
                                .flatMap { sc =>
                                  sc.split(";")
                                    .map(_.trim)
                                    .find(p => p.toLowerCase.startsWith("samesite="))
                                    .map(_.replace("samesite=", "").replace("SameSite=", ""))
                                    .flatMap(Cookie.SameSite.parse)
                                }
                            }
                            Cookie(
                              name = c.name,
                              value = c.value,
                              maxAge = c.maxAge.map(_.toInt),
                              path = c.path.getOrElse("/"),
                              domain = c.domain,
                              secure = c.secure,
                              httpOnly = c.httpOnly,
                              sameSite = sameSite
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
                                val response: Result = Status(
                                  attrs.get(otoroshi.plugins.Keys.StatusOverrideKey).getOrElse(httpResponse.status)
                                )(body)
                                  .withHeaders(
                                    headersOut.filterNot { h =>
                                      val lower = h._1.toLowerCase()
                                      lower == "content-type" || lower == "set-cookie" || lower == "transfer-encoding"
                                    }*
                                  )
                                  .withCookies(
                                    withTrackingCookies ++ jwtInjection.additionalCookies
                                      .map(t => Cookie(t._1, t._2)) ++ cookies*
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
                            case true  =>
                              // stream out
                              val res = Status(
                                attrs.get(otoroshi.plugins.Keys.StatusOverrideKey).getOrElse(httpResponse.status)
                              )
                                .chunked(finalStream)
                                .withHeaders(
                                  headersOut.filterNot { h =>
                                    val lower = h._1.toLowerCase()
                                    lower == "content-type" || lower == "set-cookie" || lower == "transfer-encoding"
                                  }*
                                )
                                .withCookies(
                                  (withTrackingCookies ++ jwtInjection.additionalCookies
                                    .map(t => Cookie(t._1, t._2)) ++ cookies)*
                                )
                              contentType match {
                                case None      => res
                                case Some(ctp) => res.as(ctp)
                              }
                            case false =>
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
                                    headersOut = resp.headers.view.mapValues(_.head).toSeq.map(Header.apply),
                                    otoroshiHeadersOut = headersOut.map(Header.apply),
                                    otoroshiHeadersIn = headersIn.map(Header.apply)
                                  )
                                )
                              }
                              // stream out
                              val res                         = Status(
                                attrs.get(otoroshi.plugins.Keys.StatusOverrideKey).getOrElse(httpResponse.status)
                              )
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
                                  }*
                                )
                                .withCookies(
                                  (withTrackingCookies ++ jwtInjection.additionalCookies
                                    .map(t => Cookie(t._1, t._2)) ++ cookies)*
                                )
                              contentType match {
                                case None      => res
                                case Some(ctp) => res.as(ctp)
                              }
                          }
                          descriptor.gzip.handleResult(req, response)
                        }
                    }
              }
            }
      }
      .map(Right.apply)
  }
}
