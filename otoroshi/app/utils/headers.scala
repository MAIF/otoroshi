package utils

import env.Env
import gateway.SnowMonkeyContext
import models.{ApiKey, JwtInjection, PrivateAppsUser, ServiceDescriptor}
import otoroshi.el.HeadersExpressionLanguage
import play.api.mvc.RequestHeader

import scala.concurrent.ExecutionContext
import utils.RequestImplicits._

object HeadersHelperImplicits {
  implicit class BetterSeq(val seq: Seq[(String, String)]) extends AnyVal {
    def appendOpt(opt: Option[String], f: String => (String, String)): Seq[(String, String)] = opt.map(k => seq :+ f(k)).getOrElse(seq)
    def appendIf(f: => Boolean, header: (String, String)): Seq[(String, String)] =  if (f) {
      seq :+ header
    } else {
      seq
    }
    def appendIfElse(f: => Boolean, name: String, ten: String, els: String): Seq[(String, String)] = if (f) {
      seq :+ (name, ten)
    } else {
      seq :+ (name, els)
    }
    def removeIf(name: String, f: => Boolean): Seq[(String, String)] = if (f) seq.filterNot(_._1.toLowerCase() == name.toLowerCase()) else seq
    def remove(name: String): Seq[(String, String)] = seq.filterNot(_._1.toLowerCase() == name.toLowerCase())
    def removeAll(names: Seq[String]): Seq[(String, String)] = {
      val lowerNames = names.map(_.toLowerCase)
      seq.filterNot(h => lowerNames.contains(h._1.toLowerCase()))
    }
    def removeAllArgs(names: String*): Seq[(String, String)] = {
      val lowerNames = names.map(_.toLowerCase)
      seq.filterNot(h => lowerNames.contains(h._1.toLowerCase()))
    }
    def appendAll(other: Seq[(String, String)]): Seq[(String, String)] = seq ++ other
    def appendAllArgs(other: (String, String)*): Seq[(String, String)] = seq ++ other
    def debug(name: String): Seq[(String, String)] = {
      println(s"\n\n$name\n")
      println(seq.mkString("\n"))
      seq
    }
  }
}

object HeadersHelper {

  import HeadersHelperImplicits._

  def xForwardedHeader(desc: ServiceDescriptor, request: RequestHeader): Seq[(String, String)] = {
    if (desc.xForwardedHeaders) {
      val xForwardedFor = request.headers
        .get("X-Forwarded-For")
        .map(v => v + ", " + request.remoteAddress)
        .getOrElse(request.remoteAddress)
      val xForwardedProto = request.theProtocol
      val xForwardedHost  = request.headers.get("X-Forwarded-Host").getOrElse(request.host)
      Seq("X-Forwarded-For"   -> xForwardedFor,
        "X-Forwarded-Host"  -> xForwardedHost,
        "X-Forwarded-Proto" -> xForwardedProto)
    } else {
      Seq.empty[(String, String)]
    }
  }

  def composeHeadersIn(
    descriptor: ServiceDescriptor,
    req: RequestHeader,
    apiKey:  Option[ApiKey],
    paUsr:    Option[PrivateAppsUser],
    elCtx: Map[String, String],
    currentReqHasBody: Boolean,
    headersInFiltered: Seq[String],
    snowflake: String,
    requestTimestamp: String,
    host: String,
    claim: String,
    stateToken: String,
    fromOtoroshi: Option[String],
    snowMonkeyContext: SnowMonkeyContext,
    jwtInjection: JwtInjection
  )(implicit env: Env, ec: ExecutionContext): Seq[(String, String)] = {

    val stateRequestHeaderName =
      descriptor.secComHeaders.stateRequestName.getOrElse(env.Headers.OtoroshiState)
    val claimRequestHeaderName =
      descriptor.secComHeaders.claimRequestName.getOrElse(env.Headers.OtoroshiClaim)

    if (env.useOldHeadersComposition) {
      val headersIn: Seq[(String, String)] = {
        (descriptor.missingOnlyHeadersIn.filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
          .mapValues(v => HeadersExpressionLanguage.apply(v, Some(req), Some(descriptor), apiKey, paUsr, elCtx)).filterNot(h => h._2 == "null") ++
          req.headers.toMap.toSeq
            .flatMap(c => c._2.map(v => (c._1, v))) //.map(tuple => (tuple._1, tuple._2.mkString(","))) //.toSimpleMap
            .filterNot(
            t =>
              if (t._1.toLowerCase == "content-type" && !currentReqHasBody) true
              else if (t._1.toLowerCase == "content-length") true
              else false
          )
            .filterNot(t => descriptor.removeHeadersIn.contains(t._1))
            .filterNot(
              t =>
                (headersInFiltered ++ Seq(stateRequestHeaderName, claimRequestHeaderName))
                  .contains(t._1.toLowerCase)
            ) ++ Map(
          env.Headers.OtoroshiProxiedHost -> req.headers.get("Host").getOrElse("--"),
          //"Host"                               -> host,
          "Host" -> (if (descriptor.overrideHost) host
          else req.headers.get("Host").getOrElse("--")),
          env.Headers.OtoroshiRequestId        -> snowflake,
          env.Headers.OtoroshiRequestTimestamp -> requestTimestamp
        ) ++ (if (descriptor.enforceSecureCommunication && descriptor.sendInfoToken) {
          Map(
            claimRequestHeaderName -> claim
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
          case None => Map.empty[String, String]
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
            .mapValues(v => HeadersExpressionLanguage.apply(v, Some(req), Some(descriptor), apiKey, paUsr, elCtx)).filterNot(h => h._2 == "null") ++ fromOtoroshi
          .map(v => Map(env.Headers.OtoroshiGatewayParentRequest -> fromOtoroshi.get))
          .getOrElse(Map.empty[String, String]) ++ jwtInjection.additionalHeaders).toSeq
          .filterNot(t => jwtInjection.removeHeaders.contains(t._1)) ++ xForwardedHeader(
          descriptor,
          req
        )
      }
      headersIn
    } else {

      val headersFromRequest: Seq[(String, String)] = req.headers.toMap.toSeq
        .flatMap(c => c._2.map(v => (c._1, v)))
        .filter(t => t._1.trim.nonEmpty && t._2.trim().nonEmpty)
        .filterNot(h => h._2 == "null")

      val missingOnlyHeaders: Seq[(String, String)] = descriptor.missingOnlyHeadersIn
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .mapValues(v => HeadersExpressionLanguage(v, Some(req), Some(descriptor), apiKey, paUsr, elCtx))
        .filterNot(h => h._2 == "null")
        .toSeq

      val additionalHeaders: Seq[(String, String)] = descriptor.additionalHeaders
        .filter(t => t._1.trim.nonEmpty && t._2.trim.nonEmpty)
        .mapValues(v => HeadersExpressionLanguage(v, Some(req), Some(descriptor), apiKey, paUsr, elCtx))
        .filterNot(h => h._2 == "null")
        .toSeq

      missingOnlyHeaders
        .removeAll(headersFromRequest.map(_._1))
        .appendAll(headersFromRequest)
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
        .appendOpt(fromOtoroshi, value => env.Headers.OtoroshiGatewayParentRequest -> value)
        .appendIf(req.clientCertificateChain.isDefined, env.Headers.OtoroshiClientCertChain -> req.clientCertChainPemString)
        .appendIf(descriptor.enforceSecureCommunication && descriptor.sendInfoToken, claimRequestHeaderName -> claim)
        .appendIf(descriptor.enforceSecureCommunication && descriptor.sendStateChallenge, stateRequestHeaderName -> stateToken)
        .appendOpt(req.headers.get("Content-Length"), value => "Content-Length" -> (value.toInt + snowMonkeyContext.trailingRequestBodySize).toString)
        .removeAll(additionalHeaders.map(_._1))
        .appendAll(additionalHeaders)
        .appendAll(xForwardedHeader(descriptor, req))
        .removeIf("content-type", !currentReqHasBody)
        .remove("content-length")
    }
  }
}
