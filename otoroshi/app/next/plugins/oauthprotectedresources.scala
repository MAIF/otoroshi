package otoroshi.next.plugins

import org.apache.pekko.stream.Materializer
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import otoroshi.auth.OAuth2ModuleConfig
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.NgProxyEngineError
import otoroshi.utils.http.RequestImplicits.EnhancedRequestHeader
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._

import java.security.interfaces.{ECPrivateKey, ECPublicKey, RSAPrivateKey, RSAPublicKey}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

// ---------------------------------------------------------------------------------------------------------------------
// RFC 9728 — OAuth 2.0 Protected Resource Metadata
// https://datatracker.ietf.org/doc/html/rfc9728
//
// Serves the protected resource metadata document. Operators wire this plugin on a route whose frontend matches
// `/.well-known/oauth-protected-resource` (or a path-suffixed variant when several protected resources share a host).
// The plugin is a NgBackendCall: the route's actual backend is never called — the plugin returns the metadata JSON
// (or a signed JWT-bearing JSON when signed_metadata is enabled).
// ---------------------------------------------------------------------------------------------------------------------

case class OAuthProtectedResourceMetadataConfig(
    ref: Option[String] = None,
    resource: Option[String] = None,
    authorizationServersOverride: Seq[String] = Seq.empty,
    jwksUri: Option[String] = None,
    scopesSupported: Seq[String] = Seq.empty,
    bearerMethodsSupported: Seq[String] = Seq("header"),
    resourceSigningAlgValuesSupported: Seq[String] = Seq.empty,
    resourceName: Option[String] = None,
    resourceDocumentation: Option[String] = None,
    resourcePolicyUri: Option[String] = None,
    resourceTosUri: Option[String] = None,
    tlsClientCertificateBoundAccessTokens: Boolean = false,
    dpopSigningAlgValuesSupported: Seq[String] = Seq.empty,
    dpopBoundAccessTokensRequired: Boolean = false,
    authorizationDetailsTypesSupported: Seq[String] = Seq.empty,
    extraMetadata: JsObject = Json.obj(),
    signedMetadata: Boolean = false,
    signingCertRef: Option[String] = None,
    signingAlg: String = "RS256",
    signedMetadataKid: Option[String] = None
) extends NgPluginConfig {
  def json: JsValue = OAuthProtectedResourceMetadataConfig.format.writes(this)
}

object OAuthProtectedResourceMetadataConfig {

  val configFlow: Seq[String] = Seq(
    "ref",
    "resource",
    "authorization_servers_override",
    "jwks_uri",
    "scopes_supported",
    "bearer_methods_supported",
    "resource_signing_alg_values_supported",
    "resource_name",
    "resource_documentation",
    "resource_policy_uri",
    "resource_tos_uri",
    "tls_client_certificate_bound_access_tokens",
    "dpop_signing_alg_values_supported",
    "dpop_bound_access_tokens_required",
    "authorization_details_types_supported",
    "extra_metadata",
    "signed_metadata",
    "signing_cert_ref",
    "signing_alg",
    "signed_metadata_kid"
  )

  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "ref"                                        -> Json.obj(
        "type"  -> "select",
        "label" -> "Auth. module",
        "help"  -> "OAuth2/OIDC auth module used to derive `authorization_servers`. Leave empty if you override it below.",
        "props" -> Json.obj(
          "optionsFrom"        -> "/bo/api/proxy/apis/security.otoroshi.io/v1/auth-modules",
          "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id")
        )
      ),
      "resource"                                   -> Json.obj(
        "type"  -> "string",
        "label" -> "Resource identifier",
        "help"  -> "Resource identifier URL (required field by RFC 9728). When empty, derived from the request scheme+host."
      ),
      "authorization_servers_override"             -> Json.obj(
        "type"  -> "array",
        "label" -> "Authorization servers override",
        "help"  -> "Explicit issuer URLs. When set, takes precedence over the value derived from the auth module."
      ),
      "jwks_uri"                                   -> Json.obj(
        "type"  -> "string",
        "label" -> "JWKS URI",
        "help"  -> "URL of the JWKS endpoint exposing the resource's signing keys (required when `signed_metadata` is enabled)."
      ),
      "scopes_supported"                           -> Json.obj(
        "type"  -> "array",
        "label" -> "Scopes supported",
        "help"  -> "List of scopes this resource recognizes."
      ),
      "bearer_methods_supported"                   -> Json.obj(
        "type"  -> "array",
        "label" -> "Bearer methods supported",
        "help"  -> "Token presentation methods. Typical values: `header`, `body`, `query`."
      ),
      "resource_signing_alg_values_supported"      -> Json.obj(
        "type"  -> "array",
        "label" -> "Resource signing algs",
        "help"  -> "JWS `alg` values the resource supports for signed responses."
      ),
      "resource_name"                              -> Json.obj(
        "type"  -> "string",
        "label" -> "Resource name",
        "help"  -> "Human-readable name shown to end users."
      ),
      "resource_documentation"                     -> Json.obj(
        "type"  -> "string",
        "label" -> "Resource documentation URL"
      ),
      "resource_policy_uri"                        -> Json.obj(
        "type"  -> "string",
        "label" -> "Resource policy URL"
      ),
      "resource_tos_uri"                           -> Json.obj(
        "type"  -> "string",
        "label" -> "Resource ToS URL"
      ),
      "tls_client_certificate_bound_access_tokens" -> Json.obj(
        "type"  -> "bool",
        "label" -> "mTLS-bound access tokens",
        "help"  -> "Set to true if the resource enforces RFC 8705 certificate-bound tokens."
      ),
      "dpop_signing_alg_values_supported"          -> Json.obj(
        "type"  -> "array",
        "label" -> "DPoP signing algs supported"
      ),
      "dpop_bound_access_tokens_required"          -> Json.obj(
        "type"  -> "bool",
        "label" -> "DPoP-bound tokens required"
      ),
      "authorization_details_types_supported"      -> Json.obj(
        "type"  -> "array",
        "label" -> "authorization_details types supported"
      ),
      "extra_metadata"                             -> Json.obj(
        "type"  -> "object",
        "label" -> "Extra metadata",
        "help"  -> "Arbitrary additional fields merged into the response document (and into the signed JWT when applicable)."
      ),
      "signed_metadata"                            -> Json.obj(
        "type"  -> "bool",
        "label" -> "Emit signed_metadata",
        "help"  -> "Sign the metadata as a JWT (RFC 9728 §3.1) and include it as a `signed_metadata` claim."
      ),
      "signing_cert_ref"                           -> Json.obj(
        "type"  -> "select",
        "label" -> "Signing keypair",
        "help"  -> "Otoroshi certificate/keypair entity used to sign the JWT.",
        "props" -> Json.obj(
          "optionsFrom"        -> "/bo/api/proxy/apis/security.otoroshi.io/v1/certificates",
          "optionsTransformer" -> Json.obj("label" -> "name", "value" -> "id")
        )
      ),
      "signing_alg"                                -> Json.obj(
        "type"  -> "select",
        "label" -> "Signing algorithm",
        "props" -> Json.obj(
          "options" -> JsArray(Seq("RS256", "RS384", "RS512", "ES256", "ES384", "ES512").map(JsString))
        )
      ),
      "signed_metadata_kid"                        -> Json.obj(
        "type"  -> "string",
        "label" -> "Signed metadata `kid`",
        "help"  -> "`kid` header inserted in the signed JWT. Defaults to the cert ref."
      )
    )
  )

  val format: Format[OAuthProtectedResourceMetadataConfig] = new Format[OAuthProtectedResourceMetadataConfig] {
    override def reads(json: JsValue): JsResult[OAuthProtectedResourceMetadataConfig] = Try {
      OAuthProtectedResourceMetadataConfig(
        ref = json.select("ref").asOpt[String].filter(_.nonEmpty),
        resource = json.select("resource").asOpt[String].filter(_.nonEmpty),
        authorizationServersOverride =
          json.select("authorization_servers_override").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq,
        jwksUri = json.select("jwks_uri").asOpt[String].filter(_.nonEmpty),
        scopesSupported = json.select("scopes_supported").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq,
        bearerMethodsSupported = json.select("bearer_methods_supported").asOpt[Seq[String]].getOrElse(Seq("header")),
        resourceSigningAlgValuesSupported =
          json.select("resource_signing_alg_values_supported").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq,
        resourceName = json.select("resource_name").asOpt[String].filter(_.nonEmpty),
        resourceDocumentation = json.select("resource_documentation").asOpt[String].filter(_.nonEmpty),
        resourcePolicyUri = json.select("resource_policy_uri").asOpt[String].filter(_.nonEmpty),
        resourceTosUri = json.select("resource_tos_uri").asOpt[String].filter(_.nonEmpty),
        tlsClientCertificateBoundAccessTokens =
          json.select("tls_client_certificate_bound_access_tokens").asOpt[Boolean].getOrElse(false),
        dpopSigningAlgValuesSupported =
          json.select("dpop_signing_alg_values_supported").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq,
        dpopBoundAccessTokensRequired =
          json.select("dpop_bound_access_tokens_required").asOpt[Boolean].getOrElse(false),
        authorizationDetailsTypesSupported =
          json.select("authorization_details_types_supported").asOpt[Seq[String]].getOrElse(Seq.empty).toSeq,
        extraMetadata = json.select("extra_metadata").asOpt[JsObject].getOrElse(Json.obj()),
        signedMetadata = json.select("signed_metadata").asOpt[Boolean].getOrElse(false),
        signingCertRef = json.select("signing_cert_ref").asOpt[String].filter(_.nonEmpty),
        signingAlg = json.select("signing_alg").asOpt[String].getOrElse("RS256"),
        signedMetadataKid = json.select("signed_metadata_kid").asOpt[String].filter(_.nonEmpty)
      )
    } match {
      case Success(v) => JsSuccess(v)
      case Failure(e) => JsError(e.getMessage)
    }

    override def writes(o: OAuthProtectedResourceMetadataConfig): JsValue = Json.obj(
      "ref"                                        -> o.ref.map(JsString.apply).getOrElse(JsNull).asValue,
      "resource"                                   -> o.resource.map(JsString.apply).getOrElse(JsNull).asValue,
      "authorization_servers_override"             -> o.authorizationServersOverride,
      "jwks_uri"                                   -> o.jwksUri.map(JsString.apply).getOrElse(JsNull).asValue,
      "scopes_supported"                           -> o.scopesSupported,
      "bearer_methods_supported"                   -> o.bearerMethodsSupported,
      "resource_signing_alg_values_supported"      -> o.resourceSigningAlgValuesSupported,
      "resource_name"                              -> o.resourceName.map(JsString.apply).getOrElse(JsNull).asValue,
      "resource_documentation"                     -> o.resourceDocumentation.map(JsString.apply).getOrElse(JsNull).asValue,
      "resource_policy_uri"                        -> o.resourcePolicyUri.map(JsString.apply).getOrElse(JsNull).asValue,
      "resource_tos_uri"                           -> o.resourceTosUri.map(JsString.apply).getOrElse(JsNull).asValue,
      "tls_client_certificate_bound_access_tokens" -> o.tlsClientCertificateBoundAccessTokens,
      "dpop_signing_alg_values_supported"          -> o.dpopSigningAlgValuesSupported,
      "dpop_bound_access_tokens_required"          -> o.dpopBoundAccessTokensRequired,
      "authorization_details_types_supported"      -> o.authorizationDetailsTypesSupported,
      "extra_metadata"                             -> o.extraMetadata,
      "signed_metadata"                            -> o.signedMetadata,
      "signing_cert_ref"                           -> o.signingCertRef.map(JsString.apply).getOrElse(JsNull).asValue,
      "signing_alg"                                -> o.signingAlg,
      "signed_metadata_kid"                        -> o.signedMetadataKid.map(JsString.apply).getOrElse(JsNull).asValue
    )
  }
}

object OAuthProtectedResourceMetadata {

  private val logger = play.api.Logger("otoroshi-plugin-rfc9728")

  // Try to extract the AS issuer from an OIDC auth module. Preference order:
  // 1. `oidConfig` URL stripped of `/.well-known/openid-configuration` (or `/.well-known/oauth-authorization-server`)
  // 2. scheme + authority of `tokenUrl` (best-effort fallback)
  def issuerFromModule(m: OAuth2ModuleConfig): Option[String] = {
    m.oidConfig.flatMap(stripWellKnownSuffix).orElse(authorityBase(m.tokenUrl))
  }

  private val wellKnownSuffixes = Seq(
    "/.well-known/openid-configuration",
    "/.well-known/oauth-authorization-server"
  )

  private def stripWellKnownSuffix(url: String): Option[String] =
    wellKnownSuffixes.find(url.endsWith).map(suf => url.dropRight(suf.length))

  private def authorityBase(url: String): Option[String] = Try {
    val u    = new java.net.URI(url)
    val port = if (u.getPort > 0) s":${u.getPort}" else ""
    if (u.getScheme == null || u.getHost == null) None
    else Some(s"${u.getScheme}://${u.getHost}$port")
  }.toOption.flatten
}

class OAuthProtectedResourceMetadata extends NgBackendCall {

  import OAuthProtectedResourceMetadata._

  override def useDelegates: Boolean                       = false
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "OAuth 2.0 Protected Resource Metadata (RFC 9728)"
  override def description: Option[String]                 =
    "Serves the OAuth 2.0 Protected Resource Metadata document at the well-known endpoint as defined by RFC 9728".some
  override def defaultConfigObject: Option[NgPluginConfig] = OAuthProtectedResourceMetadataConfig().some
  override def noJsForm: Boolean                           = true

  override def visibility: NgPluginVisibility    = NgPluginVisibility.NgUserLand
  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Authentication)
  override def steps: Seq[NgStep]                = Seq(NgStep.CallBackend)

  override def configFlow: Seq[String]        = OAuthProtectedResourceMetadataConfig.configFlow
  override def configSchema: Option[JsObject] = OAuthProtectedResourceMetadataConfig.configSchema

  // Compose the metadata claims (used for both the JSON document and the signed_metadata JWT payload).
  private def buildClaims(
      config: OAuthProtectedResourceMetadataConfig,
      resourceId: String,
      authorizationServers: Seq[String]
  ): JsObject = {
    val base     = Json.obj("resource" -> resourceId)
    val opt      = Seq[(String, JsValue)](
      "authorization_servers"                      -> (if (authorizationServers.nonEmpty)
                                    JsArray(authorizationServers.map(JsString.apply))
                                  else JsNull),
      "jwks_uri"                                   -> config.jwksUri.map(JsString.apply).getOrElse(JsNull),
      "scopes_supported"                           -> (if (config.scopesSupported.nonEmpty)
                               JsArray(config.scopesSupported.map(JsString.apply))
                             else JsNull),
      "bearer_methods_supported"                   -> (if (config.bearerMethodsSupported.nonEmpty)
                                       JsArray(config.bearerMethodsSupported.map(JsString.apply))
                                     else JsNull),
      "resource_signing_alg_values_supported"      -> (if (config.resourceSigningAlgValuesSupported.nonEmpty)
                                                    JsArray(
                                                      config.resourceSigningAlgValuesSupported.map(JsString.apply)
                                                    )
                                                  else JsNull),
      "resource_name"                              -> config.resourceName.map(JsString.apply).getOrElse(JsNull),
      "resource_documentation"                     -> config.resourceDocumentation.map(JsString.apply).getOrElse(JsNull),
      "resource_policy_uri"                        -> config.resourcePolicyUri.map(JsString.apply).getOrElse(JsNull),
      "resource_tos_uri"                           -> config.resourceTosUri.map(JsString.apply).getOrElse(JsNull),
      "tls_client_certificate_bound_access_tokens" -> JsBoolean(config.tlsClientCertificateBoundAccessTokens),
      "dpop_signing_alg_values_supported"          -> (if (config.dpopSigningAlgValuesSupported.nonEmpty)
                                                JsArray(config.dpopSigningAlgValuesSupported.map(JsString.apply))
                                              else JsNull),
      "dpop_bound_access_tokens_required"          -> JsBoolean(config.dpopBoundAccessTokensRequired),
      "authorization_details_types_supported"      -> (if (config.authorizationDetailsTypesSupported.nonEmpty)
                                                    JsArray(
                                                      config.authorizationDetailsTypesSupported.map(JsString.apply)
                                                    )
                                                  else JsNull)
    ).filterNot { case (_, v) => v == JsNull }
    val withOpts = opt.foldLeft(base) { case (acc, (k, v)) => acc + (k -> v) }
    // Extra metadata last so operators can override fields if they really need to.
    withOpts.deepMerge(config.extraMetadata)
  }

  // Resolve the Algorithm + kid used to sign the JWT. Returns Left with a human-readable reason when the
  // configured cert/algorithm combination is not signable.
  private def resolveSigningAlgo(
      config: OAuthProtectedResourceMetadataConfig
  )(implicit env: Env): Either[String, (Algorithm, String)] = {
    config.signingCertRef match {
      case None        => Left("signed_metadata is enabled but no signing keypair was configured")
      case Some(refId) =>
        env.proxyState.certificate(refId) match {
          case None       => Left(s"signing keypair '$refId' not found")
          case Some(cert) =>
            val kp  = cert.cryptoKeyPair
            val pub = kp.getPublic
            val pri = kp.getPrivate
            val kid = config.signedMetadataKid.getOrElse(refId)
            (config.signingAlg.toUpperCase, pub, pri) match {
              case ("RS256", p: RSAPublicKey, k: RSAPrivateKey) => Right(Algorithm.RSA256(p, k) -> kid)
              case ("RS384", p: RSAPublicKey, k: RSAPrivateKey) => Right(Algorithm.RSA384(p, k) -> kid)
              case ("RS512", p: RSAPublicKey, k: RSAPrivateKey) => Right(Algorithm.RSA512(p, k) -> kid)
              case ("ES256", p: ECPublicKey, k: ECPrivateKey)   => Right(Algorithm.ECDSA256(p, k) -> kid)
              case ("ES384", p: ECPublicKey, k: ECPrivateKey)   => Right(Algorithm.ECDSA384(p, k) -> kid)
              case ("ES512", p: ECPublicKey, k: ECPrivateKey)   => Right(Algorithm.ECDSA512(p, k) -> kid)
              case _                                            =>
                Left(s"signing_alg '${config.signingAlg}' is not compatible with keypair '$refId'")
            }
        }
    }
  }

  private def signMetadata(
      config: OAuthProtectedResourceMetadataConfig,
      claims: JsObject,
      resourceId: String
  )(implicit env: Env): Either[String, String] = {
    resolveSigningAlgo(config).map { case (algo, kid) =>
      val now     = System.currentTimeMillis() / 1000L
      // The JWT carries the metadata claims plus `iss` (the resource itself, per §3.1), `iat`, and `sub`=resource.
      val builder = JWT
        .create()
        .withKeyId(kid)
        .withIssuer(resourceId)
        .withSubject(resourceId)
        .withIssuedAt(new java.util.Date(now * 1000L))
      claims.fields.foreach {
        case ("iss", _) | ("sub", _) | ("iat", _) => () // reserved for the builder
        case (k, JsString(v))                     => builder.withClaim(k, v); ()
        case (k, JsBoolean(v))                    => builder.withClaim(k, java.lang.Boolean.valueOf(v)); ()
        case (k, JsNumber(v))                     => builder.withClaim(k, java.lang.Long.valueOf(v.toLong)); ()
        case (k, arr: JsArray)                    =>
          // auth0-jwt only takes typed arrays; serialize anything else as a stringified JSON payload to keep fidelity.
          val items = arr.value
          if (items.forall(_.isInstanceOf[JsString])) {
            builder.withArrayClaim(k, items.map(_.as[String]).toArray)
          } else {
            builder.withClaim(k, Json.stringify(arr))
          }
          ()
        case (k, obj: JsObject)                   =>
          builder.withClaim(k, Json.stringify(obj))
          ()
        case (_, JsNull)                          => ()
      }
      builder.sign(algo)
    }
  }

  override def callBackend(
      ctx: NgbBackendCallContext,
      delegates: () => Future[Either[NgProxyEngineError, BackendCallResponse]]
  )(implicit
      env: Env,
      ec: ExecutionContext,
      mat: Materializer
  ): Future[Either[NgProxyEngineError, BackendCallResponse]] = {

    val config = ctx
      .cachedConfig(internalName)(OAuthProtectedResourceMetadataConfig.format)
      .getOrElse(OAuthProtectedResourceMetadataConfig())

    val resourceId = config.resource.getOrElse {
      val scheme = ctx.rawRequest.theProtocol
      val host   = ctx.rawRequest.theHost
      s"$scheme://$host/"
    }

    val authorizationServers: Seq[String] = {
      if (config.authorizationServersOverride.nonEmpty) config.authorizationServersOverride
      else
        config.ref
          .flatMap(env.proxyState.authModule)
          .collect { case m: OAuth2ModuleConfig => m }
          .flatMap(issuerFromModule)
          .toSeq
    }

    if (config.signedMetadata && config.jwksUri.isEmpty) {
      logger.warn(
        s"route '${ctx.route.id}': signed_metadata is enabled but jwks_uri is empty — receivers will not be able to verify the JWT signature"
      )
    }

    val baseClaims = buildClaims(config, resourceId, authorizationServers)

    val document: JsObject = if (config.signedMetadata) {
      signMetadata(config, baseClaims, resourceId) match {
        case Right(jwt) => baseClaims + ("signed_metadata" -> JsString(jwt))
        case Left(err)  =>
          logger.warn(s"route '${ctx.route.id}': cannot produce signed_metadata — $err")
          baseClaims
      }
    } else baseClaims

    val body = Json.stringify(document)
    inMemoryBodyResponse(
      200,
      Map(
        "Content-Type"  -> "application/json",
        "Cache-Control" -> "public, max-age=3600"
      ),
      body.byteString
    ).future
  }
}
