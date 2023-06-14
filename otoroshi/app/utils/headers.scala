package otoroshi.utils.http

import otoroshi.env.Env
import otoroshi.gateway.SnowMonkeyContext
import otoroshi.models._
import otoroshi.el.HeadersExpressionLanguage
import otoroshi.utils.TypedMap
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.libs.ws.WSResponse
import play.api.mvc.{RequestHeader, Result}
import otoroshi.security.OtoroshiClaim

import scala.concurrent.ExecutionContext

object HeadersHelper {

  @inline
  def xForwardedHeader(desc: ServiceDescriptor, request: RequestHeader)(implicit env: Env): Seq[(String, String)] = {
    if (desc.xForwardedHeaders && env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
      val xForwardedFor   = request.headers
        .get("X-Forwarded-For")
        .map(v => v + ", " + request.remoteAddress)
        .getOrElse(request.remoteAddress)
      val xForwardedProto = request.theProtocol
      val xForwardedHost  = request.theHost
      Seq(
        "X-Forwarded-For"   -> xForwardedFor,
        "X-Forwarded-Host"  -> xForwardedHost,
        "X-Forwarded-Proto" -> xForwardedProto
      )
    } else if (desc.xForwardedHeaders && !env.datastores.globalConfigDataStore.latestSafe.exists(_.trustXForwarded)) {
      val xForwardedFor   = request.remoteAddress
      val xForwardedProto = request.theProtocol
      val xForwardedHost  = request.theHost
      Seq(
        "X-Forwarded-For"   -> xForwardedFor,
        "X-Forwarded-Host"  -> xForwardedHost,
        "X-Forwarded-Proto" -> xForwardedProto
      )
    } else {
      Seq.empty[(String, String)]
    }
  }

  @inline
  def composeHeadersIn(
      descriptor: ServiceDescriptor,
      req: RequestHeader,
      apiKey: Option[ApiKey],
      paUsr: Option[PrivateAppsUser],
      elCtx: Map[String, String],
      currentReqHasBody: Boolean,
      headersInFiltered: Seq[String],
      snowflake: String,
      requestTimestamp: String,
      host: String,
      claim: OtoroshiClaim,
      stateToken: String,
      fromOtoroshi: Option[String],
      snowMonkeyContext: SnowMonkeyContext,
      jwtInjection: JwtInjection,
      attrs: TypedMap
  )(implicit env: Env, ec: ExecutionContext): Seq[(String, String)] = {

    val stateRequestHeaderName =
      descriptor.secComHeaders.stateRequestName.getOrElse(env.Headers.OtoroshiState)
    val claimRequestHeaderName =
      descriptor.secComHeaders.claimRequestName.getOrElse(env.Headers.OtoroshiClaim)

    if (env.useOldHeadersComposition) {
      oldComposeHeadersIn(
        descriptor,
        req,
        apiKey,
        paUsr,
        elCtx,
        currentReqHasBody,
        headersInFiltered,
        snowflake,
        requestTimestamp,
        host,
        claim,
        stateToken,
        fromOtoroshi,
        snowMonkeyContext,
        jwtInjection,
        stateRequestHeaderName,
        claimRequestHeaderName,
        attrs
      )
    } else {

      val headersFromRequest: Seq[(String, String)] = req.headers.toMap.toSeq
        .flatMap(c => c._2.map(v => (c._1, v)))
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .filterNot(h => h._2 == "null")

      val missingOnlyHeaders: Seq[(String, String)] = descriptor.missingOnlyHeadersIn
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .mapValues(v => HeadersExpressionLanguage(v, Some(req), Some(descriptor), None, apiKey, paUsr, elCtx, attrs, env))
        .filterNot(h => h._2 == "null")
        .toSeq

      val additionalHeaders: Seq[(String, String)] = descriptor.additionalHeaders
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .mapValues(v => HeadersExpressionLanguage(v, Some(req), Some(descriptor), None, apiKey, paUsr, elCtx, attrs, env))
        .filterNot(h => h._2 == "null")
        .toSeq

      val jwtAdditionalHeaders = jwtInjection.additionalHeaders
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .toSeq

      missingOnlyHeaders
        .removeAll(headersFromRequest.map(_._1))
        .appendAll(headersFromRequest)
        .removeIf("content-type", !currentReqHasBody)
        .remove("content-length")
        .removeAll(descriptor.removeHeadersIn)
        .removeAll(headersInFiltered ++ Seq(stateRequestHeaderName, claimRequestHeaderName))
        .appendIfElse(descriptor.overrideHost, "Host", host, req.headers.get("Host").getOrElse("--"))
        .removeAllArgs(
          env.Headers.OtoroshiProxiedHost,
          env.Headers.OtoroshiRequestId,
          env.Headers.OtoroshiRequestTimestamp,
          env.Headers.OtoroshiGatewayParentRequest,
          env.Headers.OtoroshiClientCertChain
        )
        .appendAllArgs(
          env.Headers.OtoroshiProxiedHost      -> req.headers.get("Host").getOrElse("--"),
          env.Headers.OtoroshiRequestId        -> snowflake,
          env.Headers.OtoroshiRequestTimestamp -> requestTimestamp
        )
        .appendOpt(fromOtoroshi, (value: String) => env.Headers.OtoroshiGatewayParentRequest -> value)
        // .appendIf(env.sendClientChainAsPem && req.clientCertificateChain.isDefined,
        //           env.Headers.OtoroshiClientCertChain -> req.clientCertChainPemString)
        // .appendOpt(
        //   req.clientCertificateChain,
        //   (chain: Seq[X509Certificate]) =>
        //     (env.Headers.OtoroshiClientCertChain + "-DNs") -> Json.stringify(
        //       JsArray(chain.map(c => JsString(c.getSubjectDN.getName)))
        //   )
        // )
        .appendIf(
          descriptor.enforceSecureCommunication && descriptor.sendInfoToken,
          claimRequestHeaderName -> claim.serialize(descriptor.algoInfoFromOtoToBack)(env)
        )
        .appendIf(
          descriptor.enforceSecureCommunication && descriptor.sendStateChallenge,
          stateRequestHeaderName -> stateToken
        )
        .appendOpt(
          req.headers.get("Content-Length"),
          (value: String) => "Content-Length" -> (value.toInt + snowMonkeyContext.trailingRequestBodySize).toString
        )
        .removeAll(additionalHeaders.map(_._1))
        .removeAll(jwtAdditionalHeaders.map(_._1))
        .appendAll(additionalHeaders)
        .appendAll(jwtAdditionalHeaders)
        .removeAll(jwtInjection.removeHeaders)
        .appendAll(xForwardedHeader(descriptor, req)(env))
    }
  }

  @inline
  def addClaims(
      headers: Map[String, String],
      claim: OtoroshiClaim,
      descriptor: ServiceDescriptor
  )(implicit env: Env, ec: ExecutionContext): Seq[(String, String)] = {
    val claimRequestHeaderName =
      descriptor.secComHeaders.claimRequestName.getOrElse(env.Headers.OtoroshiClaim)
    val doIt                   = descriptor.enforceSecureCommunication && descriptor.sendInfoToken
    headers.toSeq
      .removeIf(claimRequestHeaderName, doIt)
      .appendIf(doIt, claimRequestHeaderName -> claim.serialize(descriptor.algoInfoFromOtoToBack)(env))
  }

  @inline
  def composeHeadersOut(
      descriptor: ServiceDescriptor,
      req: RequestHeader,
      resp: WSResponse,
      apiKey: Option[ApiKey],
      paUsr: Option[PrivateAppsUser],
      elCtx: Map[String, String],
      snowflake: String,
      requestTimestamp: String,
      headersOutFiltered: Seq[String],
      overhead: Long,
      upstreamLatency: Long,
      canaryId: String,
      remainingQuotas: RemainingQuotas,
      attrs: TypedMap
  )(implicit env: Env, ec: ExecutionContext): Seq[(String, String)] = {

    val stateResponseHeaderName = descriptor.secComHeaders.stateResponseName
      .getOrElse(env.Headers.OtoroshiStateResp)

    if (env.useOldHeadersComposition) {
      oldComposeHeadersOut(
        descriptor,
        req,
        resp,
        apiKey,
        paUsr,
        elCtx,
        snowflake,
        requestTimestamp,
        headersOutFiltered,
        overhead,
        upstreamLatency,
        canaryId,
        remainingQuotas,
        stateResponseHeaderName,
        attrs
      )
    } else {

      val headersFromResponse: Seq[(String, String)] = resp.headers.toSeq
        .flatMap(c => c._2.map(v => (c._1, v)))
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .filterNot(h => h._2 == "null")

      val missingOnlyHeadersOut = descriptor.missingOnlyHeadersOut
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .mapValues(v => HeadersExpressionLanguage(v, Some(req), Some(descriptor), None, apiKey, paUsr, elCtx, attrs, env))
        .filterNot(h => h._2 == "null")
        .toSeq

      val additionalHeadersOut = descriptor.additionalHeadersOut
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .mapValues(v => HeadersExpressionLanguage(v, Some(req), Some(descriptor), None, apiKey, paUsr, elCtx, attrs, env))
        .filterNot(h => h._2 == "null")
        .toSeq

      val corsHeaders = descriptor.cors
        .asHeaders(req)
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .map(v =>
          (v._1, HeadersExpressionLanguage(v._2, Some(req), Some(descriptor), None, apiKey, paUsr, elCtx, attrs, env))
        )
        .filterNot(h => h._2 == "null")

      missingOnlyHeadersOut
        .removeAll(headersFromResponse.map(_._1))
        .appendAll(headersFromResponse)
        .removeAll(descriptor.removeHeadersOut)
        .removeAll(headersOutFiltered :+ stateResponseHeaderName)
        .removeAllArgs(
          env.Headers.OtoroshiRequestId,
          env.Headers.OtoroshiRequestTimestamp,
          env.Headers.OtoroshiProxyLatency,
          env.Headers.OtoroshiUpstreamLatency,
          env.Headers.OtoroshiTrackerId,
          env.Headers.OtoroshiDailyCallsRemaining,
          env.Headers.OtoroshiMonthlyCallsRemaining
        )
        .appendAllArgsIf(descriptor.sendOtoroshiHeadersBack)(
          env.Headers.OtoroshiRequestId        -> snowflake,
          env.Headers.OtoroshiRequestTimestamp -> requestTimestamp,
          env.Headers.OtoroshiProxyLatency     -> s"$overhead",
          env.Headers.OtoroshiUpstreamLatency  -> s"$upstreamLatency"
        )
        .appendAllArgsIf(descriptor.sendOtoroshiHeadersBack && apiKey.isDefined)(
          env.Headers.OtoroshiDailyCallsRemaining   -> remainingQuotas.remainingCallsPerDay.toString,
          env.Headers.OtoroshiMonthlyCallsRemaining -> remainingQuotas.remainingCallsPerMonth.toString
        )
        .lazyAppendAllArgsIf(
          descriptor.sendOtoroshiHeadersBack && apiKey.isDefined && apiKey.get.rotation.enabled && attrs
            .get(otoroshi.plugins.Keys.ApiKeyRotationKey)
            .isDefined
        )(
          Seq(
            "Otoroshi-ApiKey-Rotation-At"        -> attrs
              .get(otoroshi.plugins.Keys.ApiKeyRotationKey)
              .get
              .rotationAt
              .toString(),
            "Otoroshi-ApiKey-Rotation-Remaining" -> attrs
              .get(otoroshi.plugins.Keys.ApiKeyRotationKey)
              .get
              .remaining
              .toString
          )
        )
        .appendIf(descriptor.canary.enabled, env.Headers.OtoroshiTrackerId -> s"${env.sign(canaryId)}::$canaryId")
        .removeAll(corsHeaders.map(_._1))
        .appendAll(corsHeaders)
        .removeAll(additionalHeadersOut.map(_._1))
        .appendAll(additionalHeadersOut)
    }
  }

  @inline
  def composeHeadersOutBadResult(
      descriptor: ServiceDescriptor,
      req: RequestHeader,
      badResult: Result,
      apiKey: Option[ApiKey],
      paUsr: Option[PrivateAppsUser],
      elCtx: Map[String, String],
      snowflake: String,
      requestTimestamp: String,
      headersOutFiltered: Seq[String],
      overhead: Long,
      upstreamLatency: Long,
      canaryId: String,
      remainingQuotas: RemainingQuotas,
      attrs: TypedMap
  )(implicit env: Env, ec: ExecutionContext): Seq[(String, String)] = {

    val stateResponseHeaderName = descriptor.secComHeaders.stateResponseName
      .getOrElse(env.Headers.OtoroshiStateResp)

    if (env.useOldHeadersComposition) {
      oldComposeHeadersOutBadResult(
        descriptor,
        req,
        badResult,
        apiKey,
        paUsr,
        elCtx,
        snowflake,
        requestTimestamp,
        headersOutFiltered,
        overhead,
        upstreamLatency,
        canaryId,
        remainingQuotas,
        stateResponseHeaderName,
        attrs
      )
    } else {

      val headersFromResponse: Seq[(String, String)] = badResult.header.headers.toSeq
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .filterNot(h => h._2 == "null")

      val missingOnlyHeadersOut = descriptor.missingOnlyHeadersOut
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .mapValues(v => HeadersExpressionLanguage(v, Some(req), Some(descriptor), None, apiKey, paUsr, elCtx, attrs, env))
        .filterNot(h => h._2 == "null")
        .toSeq

      val additionalHeadersOut = descriptor.additionalHeadersOut
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .mapValues(v => HeadersExpressionLanguage(v, Some(req), Some(descriptor), None, apiKey, paUsr, elCtx, attrs, env))
        .filterNot(h => h._2 == "null")
        .toSeq

      val corsHeaders = descriptor.cors
        .asHeaders(req)
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .map(v =>
          (v._1, HeadersExpressionLanguage(v._2, Some(req), Some(descriptor), None, apiKey, paUsr, elCtx, attrs, env))
        )
        .filterNot(h => h._2 == "null")

      missingOnlyHeadersOut
        .removeAll(headersFromResponse.map(_._1))
        .appendAll(headersFromResponse)
        .removeAll(descriptor.removeHeadersOut)
        .removeAll(headersOutFiltered :+ stateResponseHeaderName)
        .removeAllArgs(
          env.Headers.OtoroshiRequestId,
          env.Headers.OtoroshiRequestTimestamp,
          env.Headers.OtoroshiProxyLatency,
          env.Headers.OtoroshiUpstreamLatency,
          env.Headers.OtoroshiTrackerId,
          env.Headers.OtoroshiDailyCallsRemaining,
          env.Headers.OtoroshiMonthlyCallsRemaining
        )
        .appendAllArgsIf(descriptor.sendOtoroshiHeadersBack)(
          env.Headers.OtoroshiRequestId        -> snowflake,
          env.Headers.OtoroshiRequestTimestamp -> requestTimestamp,
          env.Headers.OtoroshiProxyLatency     -> s"$overhead",
          env.Headers.OtoroshiUpstreamLatency  -> s"$upstreamLatency"
        )
        .appendAllArgsIf(descriptor.sendOtoroshiHeadersBack && apiKey.isDefined)(
          env.Headers.OtoroshiDailyCallsRemaining   -> remainingQuotas.remainingCallsPerDay.toString,
          env.Headers.OtoroshiMonthlyCallsRemaining -> remainingQuotas.remainingCallsPerMonth.toString
        )
        .appendIf(descriptor.canary.enabled, env.Headers.OtoroshiTrackerId -> s"${env.sign(canaryId)}::$canaryId")
        .removeAll(corsHeaders.map(_._1))
        .appendAll(corsHeaders)
        .removeAll(additionalHeadersOut.map(_._1))
        .appendAll(additionalHeadersOut)

    }
  }

  // old stuff

  @inline
  private def oldComposeHeadersIn(
      descriptor: ServiceDescriptor,
      req: RequestHeader,
      apiKey: Option[ApiKey],
      paUsr: Option[PrivateAppsUser],
      elCtx: Map[String, String],
      currentReqHasBody: Boolean,
      headersInFiltered: Seq[String],
      snowflake: String,
      requestTimestamp: String,
      host: String,
      claim: OtoroshiClaim,
      stateToken: String,
      fromOtoroshi: Option[String],
      snowMonkeyContext: SnowMonkeyContext,
      jwtInjection: JwtInjection,
      stateRequestHeaderName: String,
      claimRequestHeaderName: String,
      attrs: TypedMap
  )(implicit env: Env, ec: ExecutionContext): Seq[(String, String)] = {
    val headersIn: Seq[(String, String)] = {
      (descriptor.missingOnlyHeadersIn
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .mapValues(v =>
          HeadersExpressionLanguage.apply(v, Some(req), Some(descriptor), None, apiKey, paUsr, elCtx, attrs, env)
        )
        .filterNot(h => h._2 == "null") ++
      req.headers.toMap.toSeq
        .flatMap(c => c._2.map(v => (c._1, v))) //.map(tuple => (tuple._1, tuple._2.mkString(","))) //.toSimpleMap
        .filterNot(t =>
          if (t._1.toLowerCase == "content-type" && !currentReqHasBody) true
          else if (t._1.toLowerCase == "content-length") true
          else false
        )
        .filterNot(t => descriptor.removeHeadersIn.contains(t._1))
        .filterNot(t =>
          (headersInFiltered ++ Seq(stateRequestHeaderName, claimRequestHeaderName))
            .contains(t._1.toLowerCase)
        ) ++ Map(
        env.Headers.OtoroshiProxiedHost      -> req.headers.get("Host").getOrElse("--"),
        //"Host"                               -> host,
        "Host"                               -> (if (descriptor.overrideHost) host
                   else req.headers.get("Host").getOrElse("--")),
        env.Headers.OtoroshiRequestId        -> snowflake,
        env.Headers.OtoroshiRequestTimestamp -> requestTimestamp
      ) ++ (if (descriptor.enforceSecureCommunication && descriptor.sendInfoToken) {
              Map(
                claimRequestHeaderName -> claim.serialize(descriptor.algoInfoFromOtoToBack)(env)
              )
            } else {
              Map.empty[String, String]
            }) ++ (if (descriptor.enforceSecureCommunication && descriptor.sendStateChallenge) {
                     Map(
                       stateRequestHeaderName -> stateToken
                     )
                   } else {
                     Map.empty[String, String]
                   }) ++ (req.clientCertificateChain match {
        case Some(chain) =>
          Map(env.Headers.OtoroshiClientCertChain -> req.clientCertChainPemString)
        case None        => Map.empty[String, String]
      }) ++ req.headers
        .get("Content-Length")
        .map(l => {
          Map(
            "Content-Length" -> (l.toInt + snowMonkeyContext.trailingRequestBodySize).toString
          )
        })
        .getOrElse(Map.empty[String, String]) ++
      descriptor.additionalHeaders
        .filter(t => t._1.trim.nonEmpty)
        .mapValues(v =>
          HeadersExpressionLanguage.apply(v, Some(req), Some(descriptor), None, apiKey, paUsr, elCtx, attrs, env)
        )
        .filterNot(h => h._2 == "null") ++ fromOtoroshi
        .map(v => Map(env.Headers.OtoroshiGatewayParentRequest -> fromOtoroshi.get))
        .getOrElse(Map.empty[String, String]) ++ jwtInjection.additionalHeaders).toSeq
        .filterNot(t => jwtInjection.removeHeaders.contains(t._1)) ++ xForwardedHeader(descriptor, req)(env)
    }
    headersIn
  }

  @inline
  private def oldComposeHeadersOut(
      descriptor: ServiceDescriptor,
      req: RequestHeader,
      resp: WSResponse,
      apiKey: Option[ApiKey],
      paUsr: Option[PrivateAppsUser],
      elCtx: Map[String, String],
      snowflake: String,
      requestTimestamp: String,
      headersOutFiltered: Seq[String],
      overhead: Long,
      upstreamLatency: Long,
      canaryId: String,
      remainingQuotas: RemainingQuotas,
      stateResponseHeaderName: String,
      attrs: TypedMap
  )(implicit env: Env, ec: ExecutionContext): Seq[(String, String)] = {

    val _headersForOut: Seq[(String, String)] = resp.headers.toSeq.flatMap(c => c._2.map(v => (c._1, v)))
    val _headersOut: Seq[(String, String)] = {
      descriptor.missingOnlyHeadersOut
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .mapValues(v =>
          HeadersExpressionLanguage.apply(v, Some(req), Some(descriptor), None, apiKey, paUsr, elCtx, attrs, env)
        )
        .filterNot(h => h._2 == "null")
        .toSeq ++
      _headersForOut
        .filterNot(t => descriptor.removeHeadersOut.contains(t._1))
        .filterNot(t => headersOutFiltered.contains(t._1.toLowerCase)) ++ (
        if (descriptor.sendOtoroshiHeadersBack) {
          Seq(
            env.Headers.OtoroshiRequestId        -> snowflake,
            env.Headers.OtoroshiRequestTimestamp -> requestTimestamp,
            env.Headers.OtoroshiProxyLatency     -> s"$overhead",
            env.Headers.OtoroshiUpstreamLatency  -> s"$upstreamLatency" //,
            //env.Headers.OtoroshiTrackerId              -> s"${env.sign(trackingId)}::$trackingId"
          )
        } else {
          Seq.empty[(String, String)]
        }
      ) ++ Some(canaryId)
        .filter(_ => descriptor.canary.enabled)
        .map(_ => env.Headers.OtoroshiTrackerId -> s"${env.sign(canaryId)}::$canaryId") ++ (if (
                                                                                              descriptor.sendOtoroshiHeadersBack && apiKey.isDefined
                                                                                            ) {
                                                                                              Seq(
                                                                                                env.Headers.OtoroshiDailyCallsRemaining   -> remainingQuotas.remainingCallsPerDay.toString,
                                                                                                env.Headers.OtoroshiMonthlyCallsRemaining -> remainingQuotas.remainingCallsPerMonth.toString
                                                                                              )
                                                                                            } else {
                                                                                              Seq
                                                                                                .empty[(String, String)]
                                                                                            }) ++ descriptor.cors
        .asHeaders(req) ++ descriptor.additionalHeadersOut
        .mapValues(v =>
          HeadersExpressionLanguage.apply(v, Some(req), Some(descriptor), None, apiKey, paUsr, elCtx, attrs, env)
        )
        .filterNot(h => h._2 == "null")
        .toSeq
    }
    _headersOut
  }

  @inline
  def oldComposeHeadersOutBadResult(
      descriptor: ServiceDescriptor,
      req: RequestHeader,
      badResult: Result,
      apiKey: Option[ApiKey],
      paUsr: Option[PrivateAppsUser],
      elCtx: Map[String, String],
      snowflake: String,
      requestTimestamp: String,
      headersOutFiltered: Seq[String],
      overhead: Long,
      upstreamLatency: Long,
      canaryId: String,
      remainingQuotas: RemainingQuotas,
      stateResponseHeaderName: String,
      attrs: TypedMap
  )(implicit env: Env, ec: ExecutionContext): Seq[(String, String)] = {
    val _headersOut: Seq[(String, String)] = {
      descriptor.missingOnlyHeadersOut
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .mapValues(v =>
          HeadersExpressionLanguage.apply(v, Some(req), Some(descriptor), None, apiKey, paUsr, elCtx, attrs, env)
        )
        .filterNot(h => h._2 == "null")
        .toSeq ++
      badResult.header.headers.toSeq
        .filterNot(t => descriptor.removeHeadersOut.contains(t._1))
        .filterNot(t =>
          (headersOutFiltered :+ stateResponseHeaderName)
            .contains(t._1.toLowerCase)
        ) ++ (
        if (descriptor.sendOtoroshiHeadersBack) {
          Seq(
            env.Headers.OtoroshiRequestId        -> snowflake,
            env.Headers.OtoroshiRequestTimestamp -> requestTimestamp,
            env.Headers.OtoroshiProxyLatency     -> s"$overhead",
            env.Headers.OtoroshiUpstreamLatency  -> s"$upstreamLatency"
          )
        } else {
          Seq.empty[(String, String)]
        }
      ) ++ Some(canaryId)
        .filter(_ => descriptor.canary.enabled)
        .map(_ => env.Headers.OtoroshiTrackerId -> s"${env.sign(canaryId)}::$canaryId") ++ (if (
                                                                                              descriptor.sendOtoroshiHeadersBack && apiKey.isDefined
                                                                                            ) {
                                                                                              Seq(
                                                                                                env.Headers.OtoroshiDailyCallsRemaining   -> remainingQuotas.remainingCallsPerDay.toString,
                                                                                                env.Headers.OtoroshiMonthlyCallsRemaining -> remainingQuotas.remainingCallsPerMonth.toString
                                                                                              )
                                                                                            } else {
                                                                                              Seq
                                                                                                .empty[(String, String)]
                                                                                            }) ++ descriptor.cors
        .asHeaders(req) ++ descriptor.additionalHeadersOut
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .mapValues(v =>
          HeadersExpressionLanguage.apply(v, Some(req), Some(descriptor), None, apiKey, paUsr, elCtx, attrs, env)
        )
        .filterNot(h => h._2 == "null")
        .toSeq
    }
    _headersOut
  }
}
