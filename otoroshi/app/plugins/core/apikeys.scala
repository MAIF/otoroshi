package otoroshi.plugins.core.apikeys

import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import env.Env
import models.ApiKeyHelper.decodeBase64
import otoroshi.script.{PreRouting, PreRoutingContext}
import otoroshi.utils.syntax.implicits._
import ssl.DynamicSSLEngineProvider
import utils.RequestImplicits._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class JwtApikeyExtractor extends PreRouting {

  override def name: String = "[CORE PLUGIN] Extract apikey from a JWT token"

  override def description: Option[String] = {
    s"""This plugin extract an apikey from a JWT token signed by the apikey secret. It uses the service descriptor configuration.""".stripMargin.some
  }

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey) match {
      case Some(_) => ().future
      case None => {
        val req = ctx.request
        val descriptor = ctx.descriptor
        val authByJwtToken = req.headers
          .get(
            descriptor.apiKeyConstraints.jwtAuth.headerName
              .getOrElse(env.Headers.OtoroshiBearer)
          )
          .orElse(
            req.headers.get("Authorization").filter(_.startsWith("Bearer "))
          )
          .map(_.replace("Bearer ", ""))
          .orElse(
            req.queryString
              .get(
                descriptor.apiKeyConstraints.jwtAuth.queryName
                  .getOrElse(env.Headers.OtoroshiBearerAuthorization)
              )
              .flatMap(_.lastOption)
          )
          .orElse(
            req.cookies
              .get(
                descriptor.apiKeyConstraints.jwtAuth.cookieName
                  .getOrElse(env.Headers.OtoroshiJWTAuthorization)
              )
              .map(_.value)
          )
          .filter(_.split("\\.").length == 3)
        if (authByJwtToken.isDefined && descriptor.apiKeyConstraints.jwtAuth.enabled) {
          val jwtTokenValue = authByJwtToken.get
          Try {
            JWT.decode(jwtTokenValue)
          } map { jwt =>
            jwt.claimStr("clientId")
              .orElse(jwt.claimStr("client_id"))
              .orElse(jwt.claimStr("cid"))
              .orElse(jwt.claimStr("iss")) match {
              case Some(clientId) =>
                env.datastores.apiKeyDataStore
                  .findAuthorizeKeyFor(clientId, descriptor.id)
                  .flatMap {
                    case Some(apiKey) => {
                      val possibleKeyPairId = apiKey.metadata.get("jwt-sign-keypair")
                      val kid = Option(jwt.getKeyId)
                        .orElse(possibleKeyPairId)
                        .filter(_ => descriptor.apiKeyConstraints.jwtAuth.keyPairSigned)
                        .filter(id => if (possibleKeyPairId.isDefined) possibleKeyPairId.get == id else true)
                        .flatMap(id => DynamicSSLEngineProvider.certificates.get(id))
                      val kp = kid.map(_.cryptoKeyPair)
                      val algorithmOpt: Option[Algorithm] = Option(jwt.getAlgorithm).collect {
                        case "HS256" if descriptor.apiKeyConstraints.jwtAuth.secretSigned => Algorithm.HMAC256(apiKey.clientSecret)
                        case "HS384" if descriptor.apiKeyConstraints.jwtAuth.secretSigned => Algorithm.HMAC384(apiKey.clientSecret)
                        case "HS512" if descriptor.apiKeyConstraints.jwtAuth.secretSigned => Algorithm.HMAC512(apiKey.clientSecret)
                        case "ES256" if kid.isDefined => Algorithm.ECDSA256(kp.get.getPublic.asInstanceOf[ECPublicKey], kp.get.getPrivate.asInstanceOf[ECPrivateKey])
                        case "ES384" if kid.isDefined => Algorithm.ECDSA384(kp.get.getPublic.asInstanceOf[ECPublicKey], kp.get.getPrivate.asInstanceOf[ECPrivateKey])
                        case "ES512" if kid.isDefined => Algorithm.ECDSA512(kp.get.getPublic.asInstanceOf[ECPublicKey], kp.get.getPrivate.asInstanceOf[ECPrivateKey])
                        case "RS256" if kid.isDefined => Algorithm.RSA256(kp.get.getPublic.asInstanceOf[RSAPublicKey], kp.get.getPrivate.asInstanceOf[RSAPrivateKey])
                        case "RS384" if kid.isDefined => Algorithm.RSA384(kp.get.getPublic.asInstanceOf[RSAPublicKey], kp.get.getPrivate.asInstanceOf[RSAPrivateKey])
                        case "RS512" if kid.isDefined => Algorithm.RSA512(kp.get.getPublic.asInstanceOf[RSAPublicKey], kp.get.getPrivate.asInstanceOf[RSAPrivateKey])
                      } // getOrElse Algorithm.HMAC512(apiKey.clientSecret)
                      val exp =
                        Option(jwt.getClaim("exp")).filterNot(_.isNull).map(_.asLong())
                      val iat =
                        Option(jwt.getClaim("iat")).filterNot(_.isNull).map(_.asLong())
                      val httpPath = Option(jwt.getClaim("httpPath"))
                        .filterNot(_.isNull)
                        .map(_.asString())
                      val httpVerb = Option(jwt.getClaim("httpVerb"))
                        .filterNot(_.isNull)
                        .map(_.asString())
                      val httpHost = Option(jwt.getClaim("httpHost"))
                        .filterNot(_.isNull)
                        .map(_.asString())
                      algorithmOpt match {
                        case Some(algorithm) => {
                          val verifier =
                            JWT.require(algorithm)
                              //.withIssuer(clientId)
                              .acceptLeeway(10)
                              .build
                          Try(verifier.verify(jwtTokenValue))
                            .filter { token =>
                              val xsrfToken = token.getClaim("xsrfToken")
                              val xsrfTokenHeader = req.headers.get("X-XSRF-TOKEN")
                              if (!xsrfToken.isNull && xsrfTokenHeader.isDefined) {
                                xsrfToken.asString() == xsrfTokenHeader.get
                              } else if (!xsrfToken.isNull && xsrfTokenHeader.isEmpty) {
                                false
                              } else {
                                true
                              }
                            }
                            .filter { _ =>
                              descriptor.apiKeyConstraints.jwtAuth.maxJwtLifespanSecs.map { maxJwtLifespanSecs =>
                                if (exp.isEmpty || iat.isEmpty) {
                                  false
                                } else {
                                  if ((exp.get - iat.get) <= maxJwtLifespanSecs) {
                                    true
                                  } else {
                                    false
                                  }
                                }
                              } getOrElse {
                                true
                              }
                            }
                            .filter { _ =>
                              if (descriptor.apiKeyConstraints.jwtAuth.includeRequestAttributes) {
                                val matchPath = httpPath.exists(_ == req.relativeUri)
                                val matchVerb =
                                  httpVerb.exists(_.toLowerCase == req.method.toLowerCase)
                                val matchHost = httpHost.exists(_.toLowerCase == req.theHost)
                                matchPath && matchVerb && matchHost
                              } else {
                                true
                              }
                            } match {
                            case Success(_) => {
                              ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> apiKey)
                              ().future
                            }
                            case Failure(e) => ().future
                          }
                        }
                        case None => ().future
                      }
                    }
                    case None => ().future
                  }
              case None => ().future
            }
          } getOrElse ().future
        } else {
          funit
        }
      }
    }
  }
}

class BasicAuthApikeyExtractor extends PreRouting {

  override def name: String = "[CORE PLUGIN] Extract apikey from a Basic Auth header"

  override def description: Option[String] = {
    s"""This plugin extract an apikey from a Basic Auth header. It uses the service descriptor configuration.""".stripMargin.some
  }

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey) match {
      case Some(_) => ().future
      case None => {
        val req = ctx.request
        val descriptor = ctx.descriptor
        val authBasic = req.headers
          .get(
            descriptor.apiKeyConstraints.basicAuth.headerName
              .getOrElse(env.Headers.OtoroshiAuthorization)
          )
          .orElse(
            req.headers.get("Authorization").filter(_.startsWith("Basic "))
          )
          .map(_.replace("Basic ", ""))
          .flatMap(e => Try(decodeBase64(e)).toOption)
          .orElse(
            req.queryString
              .get(
                descriptor.apiKeyConstraints.basicAuth.queryName
                  .getOrElse(env.Headers.OtoroshiBasicAuthorization)
              )
              .flatMap(_.lastOption)
              .flatMap(e => Try(decodeBase64(e)).toOption)
          )
        if (authBasic.isDefined && descriptor.apiKeyConstraints.basicAuth.enabled) {
          val auth = authBasic.get
          val id = auth.split(":").headOption.map(_.trim)
          val secret = auth.split(":").lastOption.map(_.trim)
          (id, secret) match {
            case (Some(apiKeyClientId), Some(apiKeySecret)) => {
              env.datastores.apiKeyDataStore
                .findAuthorizeKeyFor(apiKeyClientId, descriptor.id)
                .flatMap {
                  case None => ().future
                  case Some(key) if key.isInvalid(apiKeySecret) => ().future
                  case Some(key) if key.isValid(apiKeySecret) =>
                    ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> key)
                    ().future
                }
            }
            case _ => ().future
          }
        } else {
          ().future
        }
      }
    }
  }
}

class CustomHeadersApikeyExtractor extends PreRouting {

  override def name: String = "[CORE PLUGIN] Extract apikey from custom headers"

  override def description: Option[String] = {
    s"""This plugin extract an apikey from custom headers. It uses the service descriptor configuration.""".stripMargin.some
  }

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey) match {
      case Some(_) => ().future
      case None => {
        val req = ctx.request
        val descriptor = ctx.descriptor
        val authByCustomHeaders = req.headers
          .get(
            descriptor.apiKeyConstraints.customHeadersAuth.clientIdHeaderName
              .getOrElse(env.Headers.OtoroshiClientId)
          )
          .flatMap(
            id =>
              req.headers
                .get(
                  descriptor.apiKeyConstraints.customHeadersAuth.clientSecretHeaderName
                    .getOrElse(env.Headers.OtoroshiClientSecret)
                )
                .map(s => (id, s))
          )
        if (authByCustomHeaders.isDefined && descriptor.apiKeyConstraints.customHeadersAuth.enabled) {
          val (clientId, clientSecret) = authByCustomHeaders.get
          env.datastores.apiKeyDataStore
            .findAuthorizeKeyFor(clientId, descriptor.id)
            .flatMap {
              case None => ().future
              case Some(key) if key.isInvalid(clientSecret) => ().future
              case Some(key) if key.isValid(clientSecret) =>
                ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> key)
                ().future
            }
        } else {
          ().future
        }
      }
    }
  }
}

class ClientIdApikeyExtractor extends PreRouting {

  override def name: String = "[CORE PLUGIN] Extract client_id only apikey from custom headers"

  override def description: Option[String] = {
    s"""This plugin extract a client_id only apikey from custom headers. It uses the service descriptor configuration.""".stripMargin.some
  }

  override def preRoute(ctx: PreRoutingContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    ctx.attrs.get(otoroshi.plugins.Keys.ApiKeyKey) match {
      case Some(_) => ().future
      case None => {
        val req = ctx.request
        val descriptor = ctx.descriptor
        val authBySimpleApiKeyClientId = req.headers
          .get(
            descriptor.apiKeyConstraints.clientIdAuth.headerName
              .getOrElse(env.Headers.OtoroshiSimpleApiKeyClientId)
          )
          .orElse(
            req.queryString
              .get(
                descriptor.apiKeyConstraints.clientIdAuth.queryName
                  .getOrElse(env.Headers.OtoroshiSimpleApiKeyClientId)
              )
              .flatMap(_.lastOption)
          )
        if (authBySimpleApiKeyClientId.isDefined && descriptor.apiKeyConstraints.clientIdAuth.enabled) {
          val clientId = authBySimpleApiKeyClientId.get
          env.datastores.apiKeyDataStore
            .findAuthorizeKeyFor(clientId, descriptor.id)
            .flatMap {
              case None => ().future
              case Some(key) if !key.allowClientIdOnly => ().future
              case Some(key) if key.allowClientIdOnly =>
                ctx.attrs.put(otoroshi.plugins.Keys.ApiKeyKey -> key)
                ().future
            }
        } else {
          ().future
        }
      }
    }
  }
}
