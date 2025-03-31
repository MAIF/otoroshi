package otoroshi.next.models

import akka.stream.Materializer
import otoroshi.actions.ApiActionContext
import otoroshi.api.OtoroshiEnvHolder
import otoroshi.env.Env
import otoroshi.gateway.Errors
import otoroshi.models._
import otoroshi.next.plugins.{NgLegacyApikeyCall, _}
import otoroshi.next.plugins.api._
import otoroshi.next.plugins.wrappers._
import otoroshi.next.proxy.NgProxyEngineError.NgResultProxyEngineError
import otoroshi.next.proxy.{NgProxyEngineError, NgReportPluginSequence, NgReportPluginSequenceItem}
import otoroshi.next.utils.JsonHelpers
import otoroshi.script.plugins.Plugins
import otoroshi.script.{NamedPlugin, PluginType}
import otoroshi.security.IdGenerator
import otoroshi.storage.{BasicStore, RedisLike, RedisLikeStore}
import otoroshi.utils.gzip.GzipConfig
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import otoroshi.utils.{RegexPool, TypedMap}
import play.api.libs.json._
import play.api.mvc.{RequestHeader, Result, Results}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

case class NgRoute(
    location: EntityLocation,
    id: String,
    name: String,
    description: String,
    tags: Seq[String],
    metadata: Map[String, String],
    enabled: Boolean,
    debugFlow: Boolean,
    capture: Boolean,
    exportReporting: Boolean,
    groups: Seq[String] = Seq("default"),
    boundListeners: Seq[String] = Seq.empty,
    frontend: NgFrontend,
    backend: NgBackend,
    backendRef: Option[String] = None,
    // client: ClientConfig,
    plugins: NgPlugins
) extends EntityLocationSupport {

  def save()(implicit env: Env, ec: ExecutionContext): Future[Boolean] = env.datastores.routeDataStore.set(this)
  lazy val cacheableId: String                                         = originalRouteId.getOrElse(id)
  override def internalId: String                                      = id
  override def theName: String                                         = name
  override def theDescription: String                                  = description
  override def theTags: Seq[String]                                    = tags
  override def theMetadata: Map[String, String]                        = metadata
  override def json: JsValue                                           = location.jsonWithKey ++ Json.obj(
    "id"               -> id,
    "name"             -> name,
    "description"      -> description,
    "tags"             -> tags,
    "metadata"         -> metadata,
    "enabled"          -> enabled,
    "debug_flow"       -> debugFlow,
    "export_reporting" -> exportReporting,
    "capture"          -> capture,
    "groups"           -> groups,
    "bound_listeners"  -> boundListeners,
    "frontend"         -> frontend.json,
    "backend"          -> backend.json,
    "backend_ref"      -> backendRef.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    // "client" -> client.toJson,
    "plugins"          -> plugins.json
  )

  // lazy val boundListeners: Seq[String] = metadata.get("Bound-Listeners").map {
  //   case value if value.trim.startsWith("[") && value.trim.endsWith("]") => Json.parse(value).asOpt[Seq[String]].getOrElse(Seq.empty)
  //   case value if value.contains(",") => value.split(",").toSeq.map(_.trim)
  //   case value => Seq(value)
  // }.getOrElse(Seq.empty).map(_.toLowerCase())

  lazy val notBoundToListener = boundListeners.isEmpty

  def boundToListener(listener: String) = boundListeners.contains(listener.toLowerCase())

  def matches(
      request: RequestHeader,
      attrs: TypedMap,
      matchedPath: String,
      pathParams: scala.collection.mutable.HashMap[String, String],
      skipDomainVerif: Boolean,
      noMoreSegments: Boolean,
      skipPathVerif: Boolean
  )(implicit env: Env): Boolean = {
    if (enabled) {
      val path         = request.thePath
      val domain       = request.theDomain
      val method       = request.method
      val methodPasses = if (frontend.methods.isEmpty) true else frontend.methods.contains(method)
      if (methodPasses) {
        val res      = frontend.domains
          .applyOnIf(!skipDomainVerif)(
            _.filter(d => d.domainLowerCase == domain || RegexPool(d.domainLowerCase).matches(domain))
          )
          .applyOn { seq =>
            if (frontend.exact) {
              noMoreSegments
              // val paths = frontend.domains.map(_.path).map { path =>
              //   if (path.contains(":")) {
              //     var finalPath = path
              //     pathParams.map {
              //       case (key, value) =>
              //         finalPath = finalPath.replace(s":$key", value)
              //     }
              //     finalPath
              //   } else if (path.contains("*")) {
              //     path
              //   } else {
              //     path
              //   }
              // }
              // paths.exists(p => p == request.thePath)
            } else if (skipPathVerif) {
              true
            } else {
              seq.exists { d =>
                path.startsWith(d.path) || RegexPool(d.path).matches(path)
              }
            }
          }
          .applyOnIf(frontend.headers.nonEmpty) { firstRes =>
            val headers   = request.headers.toSimpleMap.map(t => (t._1.toLowerCase, t._2))
            val secondRes = frontend.headers.map(t => (t._1.toLowerCase, t._2)).forall {
              case (key, value) if value.startsWith("Regex(")    =>
                headers.get(key).exists(str => RegexPool.regex(value.substring(6).init).matches(str))
              case (key, value) if value.startsWith("Wildcard(") =>
                headers.get(key).exists(str => RegexPool.apply(value.substring(9).init).matches(str))
              case (key, value)                                  => headers.get(key).contains(value)
            }
            firstRes && secondRes
          }
          .applyOnIf(frontend.query.nonEmpty) { firstRes =>
            val query     = request.queryString
            val secondRes = frontend.query.forall {
              case (key, value) if value.startsWith("Regex(")    =>
                query.get(key).exists { values =>
                  val regex = RegexPool.regex(value.substring(6).init)
                  values.exists(str => regex.matches(str))
                }
              case (key, value) if value.startsWith("Wildcard(") =>
                query.get(key).exists { values =>
                  val regex = RegexPool.apply(value.substring(9).init)
                  values.exists(str => regex.matches(str))
                }
              case (key, value)                                  => query.get(key).exists(_.contains(value))
            }
            firstRes && secondRes
          }
        val matchers = plugins.routeMatcherPlugins(request)(env.otoroshiExecutionContext, env)
        if (res && matchers.nonEmpty) {
          if (matchers.size == 1) {
            val matcher = matchers.head
            matcher.plugin.matches(
              NgRouteMatcherContext(
                snowflake = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).get,
                request = request,
                route = this,
                config = matcher.instance.config.raw,
                attrs = attrs
              )
            )
          } else {
            matchers.forall { matcher =>
              val ctx = NgRouteMatcherContext(
                snowflake = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).get,
                request = request,
                route = this,
                config = matcher.instance.config.raw,
                attrs = attrs
              )
              matcher.plugin.matches(ctx)
            }
          }
        } else {
          res
        }
      } else {
        false
      }
    } else {
      false
    }
  }

  lazy val userFacing: Boolean                  = metadata.get("otoroshi-core-user-facing").contains("true")
  lazy val useAhcClient: Boolean                = !useAkkaHttpClient && !useNettyClient
  lazy val useAkkaHttpClient: Boolean           = metadata.get("otoroshi-core-use-akka-http-client").contains("true")
  lazy val useNettyClient: Boolean              = metadata.get("otoroshi-core-use-netty-http-client").contains("true")
  lazy val useAkkaHttpWsClient: Boolean         = metadata.get("otoroshi-core-use-akka-http-ws-client").contains("true")
  lazy val issueLetsEncryptCertificate: Boolean =
    metadata.get("otoroshi-core-issue-lets-encrypt-certificate").contains("true")
  lazy val issueCertificate: Boolean            = metadata.get("otoroshi-core-issue-certificate").contains("true")
  lazy val issueCertificateCA: Option[String]   = metadata.get("otoroshi-core-issue-certificate-ca").filter(_.nonEmpty)
  lazy val openapiUrl: Option[String]           = metadata.get("otoroshi-core-openapi-url").filter(_.nonEmpty)
  lazy val originalRouteId: Option[String]      = metadata.get("otoroshi-core-original-route-id").filter(_.nonEmpty)

  lazy val deploymentProviders: Seq[String]   = metadata
    .get("otoroshi-deployment-providers")
    .filter(_.nonEmpty)
    .map(_.split(",").map(_.trim).toSeq)
    .getOrElse(Seq.empty)
  lazy val hasDeploymentProviders: Boolean    = deploymentProviders.nonEmpty
  lazy val deploymentRegions: Seq[String]     = metadata
    .get("otoroshi-deployment-regions")
    .filter(_.nonEmpty)
    .map(_.split(",").map(_.trim).toSeq)
    .getOrElse(Seq.empty)
  lazy val hasDeploymentRegions: Boolean      = deploymentRegions.nonEmpty
  lazy val deploymentZones: Seq[String]       = metadata
    .get("otoroshi-deployment-zones")
    .filter(_.nonEmpty)
    .map(_.split(",").map(_.trim).toSeq)
    .getOrElse(Seq.empty)
  lazy val hasDeploymentZones: Boolean        = deploymentZones.nonEmpty
  lazy val deploymentDatacenters: Seq[String] =
    metadata.get("otoroshi-deployment-dcs").filter(_.nonEmpty).map(_.split(",").map(_.trim).toSeq).getOrElse(Seq.empty)
  lazy val hasDeploymentDatacenters: Boolean  = deploymentDatacenters.nonEmpty
  lazy val deploymentRacks: Seq[String]       = metadata
    .get("otoroshi-deployment-racks")
    .filter(_.nonEmpty)
    .map(_.split(",").map(_.trim).toSeq)
    .getOrElse(Seq.empty)
  lazy val hasDeploymentRacks: Boolean        = deploymentRacks.nonEmpty

  lazy val legacy: ServiceDescriptor = serviceDescriptor
  lazy val serviceDescriptor: ServiceDescriptor = {
    ServiceDescriptor(
      location = location,
      id = id,
      name = name,
      description = description,
      tags = tags,
      metadata = metadata,
      enabled = enabled,
      groups = groups,
      env = metadata.get("otoroshi-core-env").getOrElse("prod"),
      domain = "--",
      subdomain = "--",
      targets = backend.allTargets.map(_.toTarget),
      hosts = frontend.domains.map(_.domainLowerCase),
      paths = frontend.domains.map(_.path),
      stripPath = frontend.stripPath,
      clientConfig = backend.client.legacy,
      healthCheck = backend.healthCheck.getOrElse(HealthCheck.empty),
      matchingHeaders = frontend.headers,
      handleLegacyDomain = false,
      targetsLoadBalancing = backend.loadBalancing,
      issueCert = issueCertificate,
      issueCertCA = issueCertificateCA,
      useAkkaHttpClient = useAkkaHttpClient,
      useNewWSClient = useAkkaHttpWsClient,
      letsEncrypt = issueLetsEncryptCertificate,
      userFacing = userFacing,
      forceHttps = plugins.getPluginByClass[ForceHttpsTraffic].isDefined,
      maintenanceMode = plugins.getPluginByClass[MaintenanceMode].isDefined,
      buildMode = plugins.getPluginByClass[BuildMode].isDefined,
      strictlyPrivate = plugins
        .getPluginByClass[PublicPrivatePaths]
        .flatMap(p => NgPublicPrivatePathsConfig.format.reads(p.config.raw).asOpt.map(_.strict))
        .orElse(
          plugins
            .getPluginByClass[AuthModule]
            .flatMap(p => NgAuthModuleConfig.format.reads(p.config.raw).asOpt.map(!_.passWithApikey))
        )
        .orElse(
          plugins
            .getPluginByClass[NgLegacyAuthModuleCall]
            .flatMap(p => NgLegacyAuthModuleCallConfig.format.reads(p.config.raw).asOpt.map(!_.config.passWithApikey))
        )
        .orElse(
          plugins
            .getPluginByClass[ApikeyCalls]
            .flatMap(p => NgApikeyCallsConfig.format.reads(p.config.raw).asOpt.map(!_.passWithUser))
        )
        .orElse(
          plugins
            .getPluginByClass[NgLegacyApikeyCall]
            .flatMap(p => NgLegacyApikeyCallConfig.format.reads(p.config.raw).asOpt.map(!_.config.passWithUser))
        )
        .getOrElse(false),
      sendOtoroshiHeadersBack = plugins.getPluginByClass[SendOtoroshiHeadersBack].isDefined,
      readOnly = plugins.getPluginByClass[ReadOnlyCalls].isDefined,
      xForwardedHeaders = plugins.getPluginByClass[XForwardedHeaders].isDefined,
      overrideHost = plugins.getPluginByClass[OverrideHost].isDefined,
      allowHttp10 = plugins.getPluginByClass[DisableHttp10].isEmpty,
      // ///////////////////////////////////////////////////////////
      enforceSecureCommunication =
        plugins.getPluginByClass[OtoroshiChallenge].orElse(plugins.getPluginByClass[OtoroshiInfos]).isDefined,
      sendInfoToken = plugins.getPluginByClass[OtoroshiInfos].isDefined,
      sendStateChallenge = plugins.getPluginByClass[OtoroshiChallenge].isDefined,
      secComHeaders = SecComHeaders(
        claimRequestName =
          plugins.getPluginByClass[OtoroshiInfos].flatMap(p => NgOtoroshiInfoConfig(p.config.raw).headerName),
        stateRequestName = plugins
          .getPluginByClass[OtoroshiChallenge]
          .flatMap(p => NgOtoroshiChallengeConfig(p.config.raw).requestHeaderName),
        stateResponseName = plugins
          .getPluginByClass[OtoroshiChallenge]
          .flatMap(p => NgOtoroshiChallengeConfig(p.config.raw).responseHeaderName)
      ),
      secComTtl = plugins
        .getPluginByClass[OtoroshiChallenge]
        .map(p => NgOtoroshiChallengeConfig(p.config.raw).secComTtl)
        .orElse(plugins.getPluginByClass[OtoroshiInfos].map(p => NgOtoroshiInfoConfig(p.config.raw).secComTtl))
        .getOrElse(30.seconds),
      secComVersion = plugins
        .getPluginByClass[OtoroshiChallenge]
        .map(p => NgOtoroshiChallengeConfig(p.config.raw).secComVersion)
        .getOrElse(SecComVersion.V2),
      secComInfoTokenVersion = plugins
        .getPluginByClass[OtoroshiInfos]
        .map(p => NgOtoroshiInfoConfig(p.config.raw).secComVersion)
        .getOrElse(SecComInfoTokenVersion.Latest),
      secComExcludedPatterns = {
        (
          plugins.getPluginByClass[OtoroshiChallenge].map(_.exclude).getOrElse(Seq.empty) ++
          plugins.getPluginByClass[OtoroshiInfos].map(_.exclude).getOrElse(Seq.empty)
        ).distinct
      },
      // not needed because of the next line // secComSettings: AlgoSettings = HSAlgoSettings(512, "secret", false)
      secComUseSameAlgo = false,
      secComAlgoChallengeOtoToBack = plugins
        .getPluginByClass[OtoroshiChallenge]
        .map(p => NgOtoroshiChallengeConfig(p.config.raw).algoOtoToBackend)
        .getOrElse(HSAlgoSettings(512, "secret", false)),
      secComAlgoChallengeBackToOto = plugins
        .getPluginByClass[OtoroshiChallenge]
        .map(p => NgOtoroshiChallengeConfig(p.config.raw).algoBackendToOto)
        .getOrElse(HSAlgoSettings(512, "secret", false)),
      secComAlgoInfoToken = plugins
        .getPluginByClass[OtoroshiInfos]
        .map(p => NgOtoroshiInfoConfig(p.config.raw).algo)
        .getOrElse(HSAlgoSettings(512, "secret", false)),
      // ///////////////////////////////////////////////////////////
      privateApp = plugins
        .getPluginByClass[AuthModule]
        .orElse(plugins.getPluginByClass[NgLegacyAuthModuleCall])
        .orElse(plugins.getPluginByClass[MultiAuthModule])
        .isDefined,
      authConfigRef = plugins
        .getPluginByClass[AuthModule]
        .flatMap(p => NgAuthModuleConfig.format.reads(p.config.raw).asOpt.flatMap(_.module))
        .orElse(
          plugins
            .getPluginByClass[NgLegacyAuthModuleCall]
            .flatMap(p => NgLegacyAuthModuleCallConfig.format.reads(p.config.raw).asOpt.flatMap(_.config.module))
        ),
//        .orElse(
//          plugins
//            .getPluginByClass[MultiAuthModule]
//            .flatMap(p => NgMultiAuthModuleConfig.format.reads(p.config.raw).asOpt.flatMap(a => a.modules))
//        ),
      securityExcludedPatterns = plugins
        .getPluginByClass[AuthModule]
        .map(_.exclude)
        .orElse(plugins.getPluginByClass[NgLegacyAuthModuleCall].map(_.exclude))
        .getOrElse(Seq.empty),
      // ///////////////////////////////////////////////////////////
      publicPatterns = {
        val notLegacy = !metadata.get("otoroshi-core-legacy").contains("true")
        if (notLegacy) {
          plugins
            .getPluginByClass[PublicPrivatePaths]
            .flatMap(p => NgPublicPrivatePathsConfig.format.reads(p.config.raw).asOpt.map(_.publicPatterns)) match {
            case Some(patterns) => patterns
            case None           =>
              plugins.getPluginByClass[ApikeyCalls] match {
                case None                                => Seq("/.*")
                case Some(apkc) if apkc.exclude.isEmpty  => Seq.empty
                case Some(apkc) if apkc.exclude.nonEmpty => apkc.exclude
              }
          }
        } else {
          plugins
            .getPluginByClass[NgLegacyApikeyCall]
            .flatMap(p => NgLegacyApikeyCallConfig.format.reads(p.config.raw).asOpt.map(_.publicPatterns))
            .getOrElse(Seq.empty)
        }
      },
      privatePatterns = {
        plugins
          .getPluginByClass[PublicPrivatePaths]
          .flatMap(p => NgPublicPrivatePathsConfig.format.reads(p.config.raw).asOpt.map(_.privatePatterns))
          .orElse(plugins.getPluginByClass[ApikeyCalls].map(p => p.include))
          .orElse(
            plugins
              .getPluginByClass[NgLegacyApikeyCall]
              .flatMap(p => NgLegacyApikeyCallConfig.format.reads(p.config.raw).asOpt.map(_.privatePatterns))
          )
          .getOrElse(Seq.empty)
      },
      additionalHeaders = plugins
        .getPluginByClass[AdditionalHeadersIn]
        .flatMap(p => NgHeaderValuesConfig.format.reads(p.config.raw).asOpt.map(_.headers))
        .getOrElse(Map.empty),
      additionalHeadersOut = plugins
        .getPluginByClass[AdditionalHeadersOut]
        .flatMap(p => NgHeaderValuesConfig.format.reads(p.config.raw).asOpt.map(_.headers))
        .getOrElse(Map.empty),
      missingOnlyHeadersIn = plugins
        .getPluginByClass[MissingHeadersIn]
        .flatMap(p => NgHeaderValuesConfig.format.reads(p.config.raw).asOpt.map(_.headers))
        .getOrElse(Map.empty),
      missingOnlyHeadersOut = plugins
        .getPluginByClass[MissingHeadersOut]
        .flatMap(p => NgHeaderValuesConfig.format.reads(p.config.raw).asOpt.map(_.headers))
        .getOrElse(Map.empty),
      removeHeadersIn = plugins
        .getPluginByClass[RemoveHeadersIn]
        .flatMap(p => NgHeaderNamesConfig.format.reads(p.config.raw).asOpt.map(_.names))
        .getOrElse(Seq.empty),
      removeHeadersOut = plugins
        .getPluginByClass[RemoveHeadersOut]
        .flatMap(p => NgHeaderNamesConfig.format.reads(p.config.raw).asOpt.map(_.names))
        .getOrElse(Seq.empty),
      headersVerification = plugins
        .getPluginByClass[HeadersValidation]
        .flatMap(p => NgHeaderValuesConfig.format.reads(p.config.raw).asOpt.map(_.headers))
        .getOrElse(Map.empty),
      ipFiltering =
        if (
          plugins
            .getPluginByClass[IpAddressBlockList]
            .isDefined || plugins.getPluginByClass[IpAddressAllowedList].isDefined
        ) {
          IpFiltering(
            whitelist = plugins
              .getPluginByClass[IpAddressAllowedList]
              .flatMap(p => NgIpAddressesConfig.format.reads(p.config.raw).asOpt.map(_.addresses))
              .getOrElse(Seq.empty),
            blacklist = plugins
              .getPluginByClass[IpAddressBlockList]
              .flatMap(p => NgIpAddressesConfig.format.reads(p.config.raw).asOpt.map(_.addresses))
              .getOrElse(Seq.empty)
          )
        } else {
          IpFiltering()
        },
      api = openapiUrl.map(url => ApiDescriptor(true, url.some)).getOrElse(ApiDescriptor(false, None)),
      jwtVerifier = plugins
        .getPluginByClass[JwtVerification]
        .flatMap(p =>
          NgJwtVerificationConfig.format
            .reads(p.config.raw)
            .asOpt
            .map(_.verifiers)
            .map(ids => RefJwtVerifier(ids, true, p.exclude))
        )
        .getOrElse(RefJwtVerifier()),
      cors = plugins
        .getPluginByClass[otoroshi.next.plugins.Cors]
        .flatMap(p => NgCorsSettings.format.reads(p.config.raw).asOpt.map(_.legacy))
        .getOrElse(CorsSettings()),
      redirection = plugins
        .getPluginByClass[Redirection]
        .flatMap(p => NgRedirectionSettings.format.reads(p.config.raw).asOpt.map(_.legacy))
        .getOrElse(RedirectionSettings()),
      restrictions = plugins
        .getPluginByClass[RoutingRestrictions]
        .flatMap(p => NgRestrictions.format.reads(p.config.raw).asOpt.map(_.legacy))
        .getOrElse(Restrictions()),
      tcpUdpTunneling = Seq(
        plugins.getPluginByClass[TcpTunnel],
        plugins.getPluginByClass[UdpTunnel]
      ).flatten.nonEmpty,
      detectApiKeySooner = plugins
        .getPluginByClass[ApikeyCalls]
        .flatMap(p => NgApikeyCallsConfig.format.reads(p.config.raw).asOpt.map(!_.validate))
        .orElse(
          plugins
            .getPluginByClass[NgLegacyApikeyCall]
            .flatMap(p => NgLegacyApikeyCallConfig.format.reads(p.config.raw).asOpt.map(!_.config.validate))
        )
        .getOrElse(false),
      canary = plugins
        .getPluginByClass[CanaryMode]
        .flatMap(p => NgCanarySettings.format.reads(p.config.raw).asOpt.map(_.legacy))
        .getOrElse(Canary()),
      chaosConfig = plugins
        .getPluginByClass[SnowMonkeyChaos]
        .flatMap(p => NgChaosConfig.format.reads(p.config.raw).asOpt.map(_.legacy))
        .getOrElse(ChaosConfig(enabled = true)),
      gzip = plugins
        .getPluginByClass[GzipResponseCompressor]
        .flatMap(p => NgGzipConfig.format.reads(p.config.raw).asOpt.map(_.legacy))
        .getOrElse(GzipConfig(enabled = true)),
      apiKeyConstraints = {
        plugins
          .getPluginByClass[ApikeyCalls]
          .flatMap { plugin =>
            NgApikeyCallsConfig.format.reads(plugin.config.raw).asOpt.map(_.legacy)
          }
          .orElse(
            plugins
              .getPluginByClass[NgLegacyApikeyCall]
              .flatMap(p => NgLegacyApikeyCallConfig.format.reads(p.config.raw).asOpt.map(_.config.legacy))
          )
          .getOrElse(ApiKeyConstraints())
      },
      plugins = {
        val possiblePlugins        = plugins.slots.filter(slot => slot.plugin.startsWith("cp:otoroshi.next.plugins.wrappers."))
        val refs                   = possiblePlugins.map(_.config.raw.select("plugin").as[String])
        val exclusion: Seq[String] =
          if (possiblePlugins.isEmpty) Seq.empty else possiblePlugins.map(_.exclude).reduce((a, b) => a.intersect(b))
        val config                 = possiblePlugins
          .flatMap(p => p.config.raw.value.keySet.-("plugin").headOption.map(plug => (p.config.raw, plug)))
          .map { case (pconfig, key) =>
            pconfig.select(key).asObject
          }
          .foldLeft(Json.obj())(_ ++ _)
        Plugins(
          enabled = possiblePlugins.nonEmpty,
          excluded = exclusion,
          refs = refs,
          config = config
        )
      }
    )
  }

  private def otoroshiJsonError(error: JsObject, status: Results.Status, __ctx: NgTransformerErrorContext)(implicit
      env: Env,
      ec: ExecutionContext
  ): Result = {
    Errors.craftResponseResultSync(
      message = error.select("error_description").asOpt[String].getOrElse("an error occurred !"),
      status = status,
      req = __ctx.request,
      maybeCauseId = error.select("error").asOpt[String],
      attrs = __ctx.attrs,
      maybeRoute = __ctx.route.some
    )
  }

  def transformError(
      __ctx: NgTransformerErrorContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    val all_plugins = __ctx.attrs
      .get(Keys.ContextualPluginsKey)
      .map(_.transformerPluginsThatTransformsError)
      .getOrElse(plugins.transformerPluginsThatTransformsError(__ctx.request))
    if (all_plugins.nonEmpty) {
      val report   = __ctx.report
      var sequence = NgReportPluginSequence(
        size = all_plugins.size,
        kind = "error-transformer-plugins",
        start = System.currentTimeMillis(),
        stop = 0L,
        start_ns = System.nanoTime(),
        stop_ns = 0L,
        plugins = Seq.empty
      )
      def markPluginItem(
          item: NgReportPluginSequenceItem,
          ctx: NgTransformerErrorContext,
          debug: Boolean,
          result: JsValue
      ): Unit = {
        val nottrig: Seq[String] = __ctx.attrs
          .get(Keys.ContextualPluginsKey)
          .map(_.tpwoErrors.map(_.instance.plugin))
          .getOrElse(Seq.empty[String])
        sequence = sequence.copy(
          plugins = sequence.plugins :+ item.copy(
            stop = System.currentTimeMillis(),
            stop_ns = System.nanoTime(),
            out = Json
              .obj(
                "not_triggered" -> nottrig,
                "result"        -> result
              )
              .applyOnIf(debug)(_ ++ Json.obj("ctx" -> ctx.json))
          )
        )
      }
      if (all_plugins.size == 1) {
        val wrapper               = all_plugins.head
        val pluginConfig: JsValue = wrapper.plugin.defaultConfig
          .map(dc => dc ++ wrapper.instance.config.raw)
          .getOrElse(wrapper.instance.config.raw)
        val ctx                   = __ctx.copy(config = pluginConfig)
        val debug                 = debugFlow || wrapper.instance.debug
        val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
        val item                  = NgReportPluginSequenceItem(
          wrapper.instance.plugin,
          wrapper.plugin.name,
          System.currentTimeMillis(),
          System.nanoTime(),
          -1L,
          -1L,
          in,
          JsNull
        )
        wrapper.plugin.transformError(ctx).transform {
          case Failure(exception) =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
            report.setContext(sequence.stopSequence().json)
            Success(
              otoroshiJsonError(
                Json
                  .obj(
                    "error"             -> "internal_server_error",
                    "error_description" -> "an error happened during response-transformation plugins phase"
                  )
                  .applyOnIf(env.isDev) { obj => obj ++ Json.obj("jvm_error" -> JsonHelpers.errToJson(exception)) },
                Results.InternalServerError,
                __ctx
              )
            )
          case Success(resp_next) =>
            markPluginItem(item, ctx.copy(otoroshiResponse = resp_next), debug, Json.obj("kind" -> "successful"))
            report.setContext(sequence.stopSequence().json)
            Success(resp_next.asResult)
        }
      } else {
        val promise = Promise[Either[NgProxyEngineError, NgPluginHttpResponse]]()
        def next(_ctx: NgTransformerErrorContext, plugins: Seq[NgPluginWrapper[NgRequestTransformer]]): Unit = {
          plugins.headOption match {
            case None          => promise.trySuccess(Right(_ctx.otoroshiResponse))
            case Some(wrapper) => {
              val pluginConfig: JsValue = wrapper.plugin.defaultConfig
                .map(dc => dc ++ wrapper.instance.config.raw)
                .getOrElse(wrapper.instance.config.raw)
              val ctx                   = _ctx.copy(config = pluginConfig, idx = wrapper.instance.instanceId)
              val debug                 = debugFlow || wrapper.instance.debug
              val in: JsValue           = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
              val item                  = NgReportPluginSequenceItem(
                wrapper.instance.plugin,
                wrapper.plugin.name,
                System.currentTimeMillis(),
                System.nanoTime(),
                -1L,
                -1L,
                in,
                JsNull
              )
              wrapper.plugin.transformError(ctx).andThen {
                case Failure(exception)                      =>
                  markPluginItem(
                    item,
                    ctx,
                    debug,
                    Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception))
                  )
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(
                    Left(
                      NgResultProxyEngineError(
                        otoroshiJsonError(
                          Json
                            .obj(
                              "error"             -> "internal_server_error",
                              "error_description" -> "an error happened during response-transformation plugins phase"
                            )
                            .applyOnIf(env.isDev) { obj =>
                              obj ++ Json.obj("jvm_error" -> JsonHelpers.errToJson(exception))
                            },
                          Results.InternalServerError,
                          __ctx
                        )
                      )
                    )
                  )
                case Success(resp_next) if plugins.size == 1 =>
                  markPluginItem(item, ctx.copy(otoroshiResponse = resp_next), debug, Json.obj("kind" -> "successful"))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Right(resp_next))
                case Success(resp_next)                      =>
                  markPluginItem(item, ctx.copy(otoroshiResponse = resp_next), debug, Json.obj("kind" -> "successful"))
                  next(_ctx.copy(otoroshiResponse = resp_next), plugins.tail)
              }
            }
          }
        }
        next(__ctx, all_plugins)
        promise.future.flatMap {
          case Left(err)  => err.asResult()
          case Right(res) => res.asResult.vfuture
        }
      }
    } else {
      __ctx.otoroshiResponse.asResult.vfuture
    }
  }

  def contextualPlugins(global_plugins: NgPlugins, nextPluginsMerge: Boolean, request: RequestHeader)(implicit
      env: Env,
      ec: ExecutionContext
  ): NgContextualPlugins = {
    NgContextualPlugins(plugins, global_plugins, request, nextPluginsMerge, env, ec)
  }
}

object NgRoute {

  val fake  = NgRoute(
    location = EntityLocation.default,
    id = s"route_${IdGenerator.uuid}",
    name = "Fake route",
    description = "A fake route to tryout the new engine",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = true,
    capture = false,
    exportReporting = false,
    groups = Seq("default"),
    frontend = NgFrontend(
      domains = Seq(NgDomainAndPath("fake-next-gen.oto.tools")),
      headers = Map.empty,
      query = Map.empty,
      methods = Seq.empty,
      stripPath = true,
      exact = false
    ),
    backendRef = None,
    backend = NgBackend(
      targets = Seq(
        NgTarget(
          id = "tls://request.otoroshi.io:443",
          hostname = "request.otoroshi.io",
          port = 443,
          tls = true,
          backup = false
        )
      ),
      root = "/",
      rewrite = false,
      loadBalancing = RoundRobin,
      healthCheck = None,
      client = NgClientConfig.default
    ),
    plugins = NgPlugins(
      Seq(
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[ApikeyCalls]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[OverrideHost]
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[AdditionalHeadersOut],
          config = NgPluginInstanceConfig(
            Json.obj(
              "headers" -> Json.obj(
                "bar" -> "foo"
              )
            )
          )
        ),
        NgPluginInstance(
          plugin = NgPluginHelper.pluginId[AdditionalHeadersOut],
          config = NgPluginInstanceConfig(
            Json.obj(
              "headers" -> Json.obj(
                "bar2" -> "foo2"
              )
            )
          )
        )
      )
    )
  )

  def default = {
    val emp = empty
    emp.copy(
      backend = emp.backend.copy(
        targets = Seq(NgTarget.default)
      ),
      plugins = NgPlugins.empty
    )
  }
  def empty = NgRoute(
    location = EntityLocation.default,
    id = s"route_${IdGenerator.uuid}",
    name = "empty route",
    description = "empty route",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = false,
    capture = false,
    exportReporting = false,
    groups = Seq("default"),
    frontend = NgFrontend(
      domains = Seq(NgDomainAndPath("empty.oto.tools")),
      headers = Map.empty,
      query = Map.empty,
      methods = Seq.empty,
      stripPath = true,
      exact = false
    ),
    backendRef = None,
    backend = NgBackend(
      targets = Seq.empty,
      root = "/",
      rewrite = false,
      loadBalancing = RoundRobin,
      healthCheck = None,
      client = NgClientConfig.default
    ),
    plugins = NgPlugins.empty
  )

  def fromJsons(value: JsValue): NgRoute =
    try {
      fmt.reads(value).get
    } catch {
      case e: Throwable => throw e
    }

  val fmt = new Format[NgRoute] {
    override def writes(o: NgRoute): JsValue             = o.json
    override def reads(json: JsValue): JsResult[NgRoute] = Try {
      val ref        = json.select("backend_ref").asOpt[String]
      val refBackend = ref.flatMap(r => OtoroshiEnvHolder.get().proxyState.backend(r)).getOrElse(NgBackend.empty)
      NgRoute(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").as[String],
        name = json.select("name").as[String],
        description = json.select("description").asOpt[String].getOrElse(""),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        debugFlow = json.select("debug_flow").asOpt[Boolean].getOrElse(false),
        capture = json.select("capture").asOpt[Boolean].getOrElse(false),
        exportReporting = json.select("export_reporting").asOpt[Boolean].getOrElse(false),
        groups = json.select("groups").asOpt[Seq[String]].getOrElse(Seq("default")),
        boundListeners = json.select("bound_listeners").asOpt[Seq[String]].getOrElse(Seq.empty),
        frontend = NgFrontend.readFrom(json.select("frontend")),
        backend = ref match {
          case None    => NgBackend.readFrom(json.select("backend"))
          case Some(r) => refBackend
        },
        backendRef = ref,
        // client = (json \ "client").asOpt(ClientConfig.format).getOrElse(ClientConfig()),
        plugins = NgPlugins.readFrom(json.select("plugins"))
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(route)     => JsSuccess(route)
    }
  }

  def fromServiceDescriptor(service: ServiceDescriptor, debug: Boolean)(implicit
      ec: ExecutionContext,
      env: Env
  ): NgRoute = {
    import NgPluginHelper.pluginId
    NgRoute(
      location = service.location,
      id = service.id,
      name = service.name,
      description = service.description,
      tags = service.tags ++ Seq(s"env:${service.env}"),
      metadata = service.metadata
        .applyOn { meta =>
          meta ++ Map("otoroshi-core-legacy" -> "true")
        }
        .applyOn { meta =>
          meta ++ Map("otoroshi-core-env" -> service.env)
        }
        .applyOnIf(service.useAkkaHttpClient) { meta =>
          meta ++ Map("otoroshi-core-use-akka-http-client" -> "true")
        }
        .applyOnIf(service.useNewWSClient) { meta =>
          meta ++ Map("otoroshi-core-use-akka-http-ws-client" -> "true")
        }
        .applyOnIf(service.letsEncrypt) { meta =>
          meta ++ Map("otoroshi-core-issue-lets-encrypt-certificate" -> "true")
        }
        .applyOnIf(service.issueCert) { meta =>
          meta ++ Map(
            "otoroshi-core-issue-certificate"    -> "true",
            "otoroshi-core-issue-certificate-ca" -> service.issueCertCA.getOrElse("")
          )
        }
        .applyOnIf(service.userFacing) { meta =>
          meta ++ Map("otoroshi-core-user-facing" -> "true")
        }
        .applyOnIf(service.api.exposeApi) { meta =>
          meta ++ Map("otoroshi-core-openapi-url" -> service.api.openApiDescriptorUrl.getOrElse(""))
        },
      enabled = service.enabled,
      debugFlow = debug,
      capture = service.metadata.get("otoroshi-core-capture").contains("true"),
      exportReporting = service.metadata.get("otoroshi-core-export-reporting").contains("true"),
      frontend = NgFrontend(
        domains = {
          val dap = if (service.allPaths.isEmpty) {
            service.allHosts.map(h => s"$h${service.matchingRoot.getOrElse("/")}")
          } else {
            service.allPaths.flatMap(path => service.allHosts.map(host => s"$host$path"))
          }
          dap.map(NgDomainAndPath.apply).distinct
        },
        headers = service.matchingHeaders,
        query = Map.empty,
        methods = Seq.empty, // get from restrictions ???
        stripPath = service.stripPath,
        exact = false
      ),
      backendRef = None,
      backend = NgBackend(
        targets = service.targets.map(NgTarget.fromTarget),
        root = service.root,
        rewrite = false,
        loadBalancing = service.targetsLoadBalancing,
        healthCheck = if (service.healthCheck.enabled) service.healthCheck.some else None,
        client = NgClientConfig.fromLegacy(service.clientConfig)
      ),
      groups = service.groups,
      plugins = NgPlugins(
        Seq
          .empty[NgPluginInstance]
          .applyOnIf(service.forceHttps) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[ForceHttpsTraffic]
            )
          }
          .applyOnIf(service.overrideHost) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[OverrideHost]
            )
          }
          .applyOnIf(service.headersVerification.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[HeadersValidation],
              config = NgPluginInstanceConfig(NgHeaderValuesConfig(service.headersVerification).json.asObject)
            )
          }
          .applyOnIf(service.maintenanceMode) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[MaintenanceMode]
            )
          }
          .applyOnIf(service.buildMode) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[BuildMode]
            )
          }
          .applyOnIf(!service.allowHttp10) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[DisableHttp10]
            )
          }
          .applyOnIf(service.readOnly) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[ReadOnlyCalls]
            )
          }
          .applyOnIf(true) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[OtoroshiHeadersIn]
            )
          }
          .applyOnIf(service.ipFiltering.blacklist.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[IpAddressBlockList],
              config = NgPluginInstanceConfig(NgIpAddressesConfig(service.ipFiltering.blacklist).json.asObject)
            )
          }
          .applyOnIf(service.ipFiltering.whitelist.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[IpAddressAllowedList],
              config = NgPluginInstanceConfig(NgIpAddressesConfig(service.ipFiltering.whitelist).json.asObject)
            )
          }
          .applyOnIf(service.redirection.enabled) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[Redirection],
              config = NgPluginInstanceConfig(NgRedirectionSettings.fromLegacy(service.redirection).json.asObject)
            )
          }
          .applyOnIf(service.additionalHeaders.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[AdditionalHeadersIn],
              config = NgPluginInstanceConfig(NgHeaderValuesConfig(service.additionalHeaders).json.asObject)
            )
          }
          .applyOnIf(service.additionalHeadersOut.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[AdditionalHeadersOut],
              config = NgPluginInstanceConfig(NgHeaderValuesConfig(service.additionalHeadersOut).json.asObject)
            )
          }
          .applyOnIf(service.missingOnlyHeadersIn.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[MissingHeadersIn],
              config = NgPluginInstanceConfig(NgHeaderValuesConfig(service.missingOnlyHeadersIn).json.asObject)
            )
          }
          .applyOnIf(service.missingOnlyHeadersOut.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[MissingHeadersOut],
              config = NgPluginInstanceConfig(NgHeaderValuesConfig(service.missingOnlyHeadersOut).json.asObject)
            )
          }
          .applyOnIf(service.removeHeadersIn.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[RemoveHeadersIn],
              config = NgPluginInstanceConfig(NgHeaderNamesConfig(service.removeHeadersIn).json.asObject)
            )
          }
          .applyOnIf(service.removeHeadersOut.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[RemoveHeadersOut],
              config = NgPluginInstanceConfig(NgHeaderNamesConfig(service.removeHeadersOut).json.asObject)
            )
          }
          .applyOnIf(service.sendOtoroshiHeadersBack) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[SendOtoroshiHeadersBack]
            )
          }
          .applyOnIf(service.xForwardedHeaders) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[XForwardedHeaders]
            )
          }
          .applyOnIf(service.jwtVerifier.enabled && service.jwtVerifier.isRef) { seq =>
            val verifier = service.jwtVerifier.asInstanceOf[RefJwtVerifier]
            seq :+ NgPluginInstance(
              plugin = pluginId[JwtVerification],
              exclude = verifier.excludedPatterns,
              config = NgPluginInstanceConfig(NgJwtVerificationConfig(verifier.ids).json.asObject)
            )
          }
          .applyOnIf(service.restrictions.enabled) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[RoutingRestrictions],
              config = NgPluginInstanceConfig(NgRestrictions.fromLegacy(service.restrictions).json.asObject)
            )
          }
          .applyOnIf(service.enforceSecureCommunication && service.sendStateChallenge) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[OtoroshiChallenge],
              exclude = service.secComExcludedPatterns,
              config = NgPluginInstanceConfig(
                NgOtoroshiChallengeConfig(
                  Json.obj(
                    "version"              -> service.secComVersion.str,
                    "ttl"                  -> service.secComTtl.toSeconds,
                    "request_header_name"  -> service.secComHeaders.stateRequestName
                      .getOrElse(env.Headers.OtoroshiState)
                      .json,
                    "response_header_name" -> service.secComHeaders.stateResponseName
                      .getOrElse(env.Headers.OtoroshiStateResp)
                      .json,
                    "algo_to_backend"      -> (if (service.secComUseSameAlgo) service.secComSettings.asJson
                                          else service.secComAlgoChallengeOtoToBack.asJson),
                    "algo_from_backend"    -> (if (service.secComUseSameAlgo) service.secComSettings.asJson
                                            else service.secComAlgoChallengeBackToOto.asJson)
                  )
                ).json.asObject
              )
            )
          }
          .applyOnIf(service.cors.enabled) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[Cors],
              exclude = service.cors.excludedPatterns,
              config = NgPluginInstanceConfig(NgCorsSettings.fromLegacy(service.cors).json.asObject)
            )
          }
          .applyOnIf(service.privateApp && service.authConfigRef.isDefined) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[NgLegacyAuthModuleCall],
              include = Seq.empty,
              exclude = service.securityExcludedPatterns,
              config = NgPluginInstanceConfig(
                NgLegacyAuthModuleCallConfig(
                  service.publicPatterns,
                  service.privatePatterns,
                  NgAuthModuleConfig(service.authConfigRef, !service.strictlyPrivate)
                ).json.asObject
              )
            )
          }
          .applyOn { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[NgLegacyApikeyCall],
              config = NgPluginInstanceConfig(
                NgLegacyApikeyCallConfig(
                  publicPatterns = service.publicPatterns,
                  privatePatterns = service.privatePatterns,
                  config = NgApikeyCallsConfig.fromLegacy(service.apiKeyConstraints)
                ).json.asObject
              )
            )
          }
          //.applyOnIf(
          //  (service.publicPatterns.isEmpty && service.privatePatterns.isEmpty) ||
          //  service.privatePatterns.nonEmpty ||
          //  !service.publicPatterns.contains("/.*") ||
          //  service.detectApiKeySooner
          //) { seq =>
          //  val pluginConfig = NgPluginInstanceConfig(
          //    NgApikeyCallsConfig
          //      .fromLegacy(service.apiKeyConstraints)
          //      .copy(
          //        validate = !service.detectApiKeySooner,
          //        passWithUser = !service.strictlyPrivate
          //      )
          //      .json
          //      .asObject
          //  )
          //  /*
          //  seq :+ NgPluginInstance(
          //    plugin = pluginId[ApikeyCalls],
          //    include = if (service.publicPatterns.nonEmpty) Seq.empty else service.privatePatterns,
          //    exclude =
          //      if (service.detectApiKeySooner) Seq.empty
          //      else (
          //        if (service.publicPatterns.size == 1 && service.publicPatterns.contains("/.*")) Seq.empty
          //        else service.publicPatterns
          //      ),
          //    config = pluginConfig
          //  )
          //  */
          //  val inst = if (service.detectApiKeySooner) {
          //    NgPluginInstance(
          //      plugin = pluginId[ApikeyCalls],
          //      include = if (service.publicPatterns.nonEmpty) Seq.empty else service.privatePatterns,
          //      exclude = Seq.empty,
          //      config = pluginConfig
          //    )
          //  } else if (service.publicPatterns.size == 1 && service.publicPatterns.contains("/.*")) {
          //    NgPluginInstance(
          //      plugin = pluginId[ApikeyCalls],
          //      include = service.privatePatterns,
          //      exclude = Seq.empty,
          //      config = pluginConfig
          //    )
          //  } else {
          //    NgPluginInstance(
          //      plugin = pluginId[ApikeyCalls],
          //      include = service.privatePatterns,
          //      exclude = service.publicPatterns,
          //      config = pluginConfig
          //    )
          //  }
          //  seq :+ inst
          //}
          // .applyOnIf(service.privateApp && service.authConfigRef.isDefined) { seq =>
          //   seq :+ NgPluginInstance(
          //     plugin = pluginId[AuthModule],
          //     include = if (service.publicPatterns.nonEmpty) {
          //       if (service.publicPatterns.size == 1 && service.publicPatterns.contains("/.*")) Seq.empty
          //       else service.publicPatterns
          //     } else {
          //       Seq.empty
          //     },
          //     exclude = (service.securityExcludedPatterns ++ service.privatePatterns).distinct,
          //     config = NgPluginInstanceConfig(
          //       NgAuthModuleConfig(service.authConfigRef, !service.strictlyPrivate).json.asObject
          //     )
          //   )
          //}
          .applyOnIf(service.gzip.enabled) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[GzipResponseCompressor],
              exclude = service.gzip.excludedPatterns,
              config = NgPluginInstanceConfig(NgGzipConfig.fromLegacy(service.gzip).json.asObject)
            )
          }
          .applyOnIf(service.canary.enabled) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[CanaryMode],
              config = NgPluginInstanceConfig(NgCanarySettings.fromLegacy(service.canary).json.asObject)
            )
          }
          .applyOnIf(service.chaosConfig.enabled) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[SnowMonkeyChaos],
              config = NgPluginInstanceConfig(NgChaosConfig.fromLegacy(service.chaosConfig).json.asObject)
            )
          }
          .applyOnIf(service.tcpUdpTunneling) { seq =>
            val udp = service.targets.exists(_.scheme.toLowerCase.contains("udp://"))
            if (udp) {
              seq :+ NgPluginInstance(
                plugin = pluginId[UdpTunnel],
                config = NgPluginInstanceConfig(Json.obj())
              )
            } else {
              seq :+ NgPluginInstance(
                plugin = pluginId[TcpTunnel],
                config = NgPluginInstanceConfig(Json.obj())
              )
            }
          }
          .applyOnIf(service.sendInfoToken) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[OtoroshiInfos],
              // exclude = service.secComExcludedPatterns,
              config = NgPluginInstanceConfig(
                NgOtoroshiInfoConfig(
                  Json.obj(
                    "version"     -> service.secComInfoTokenVersion.version,
                    "ttl"         -> service.secComTtl.toSeconds,
                    "header_name" -> service.secComHeaders.claimRequestName.getOrElse(env.Headers.OtoroshiClaim).json,
                    "algo"        -> (if (service.secComUseSameAlgo) service.secComSettings.asJson
                               else service.secComAlgoInfoToken.asJson)
                  )
                ).json.asObject
              )
            )
          }
          .applyOnIf(service.plugins.enabled) { seq =>
            seq ++ service.plugins.refs
              .map { ref =>
                (ref, env.scriptManager.getAnyScript[NamedPlugin](ref))
              }
              .collect { case (ref, Right(plugin)) =>
                (ref, plugin)
              }
              .map { case (ref, plugin) =>
                // TODO: handle when a ngplugin actually used
                def makeInst(id: String): Option[NgPluginInstance] = {
                  val config: JsValue    = plugin.configRoot
                    .flatMap(r => service.plugins.config.select(r).asOpt[JsValue])
                    .orElse(plugin.defaultConfig)
                    .getOrElse(Json.obj())
                  val configName: String = plugin.configRoot.getOrElse("config")
                  NgPluginInstance(
                    plugin = id,
                    exclude = service.plugins.excluded,
                    config = NgPluginInstanceConfig(
                      Json.obj(
                        "plugin"   -> ref,
                        configName -> config
                      )
                    )
                  ).some
                }
                plugin match {
                  case p: NgPlugin => {
                    val config: JsObject = service.plugins.config
                      .select(p.getClass.getSimpleName)
                      .asOpt[JsValue]
                      .orElse(plugin.defaultConfig)
                      .map(_.asObject)
                      .getOrElse(Json.obj())
                    NgPluginInstance(
                      plugin = ref,
                      exclude = service.plugins.excluded,
                      config = NgPluginInstanceConfig(config)
                    ).some
                  }
                  case _           => {
                    plugin.pluginType match {
                      case PluginType.AppType             => makeInst(pluginId[RequestTransformerWrapper])
                      case PluginType.TransformerType     => makeInst(pluginId[RequestTransformerWrapper])
                      case PluginType.AccessValidatorType => makeInst(pluginId[AccessValidatorWrapper])
                      case PluginType.PreRoutingType      => makeInst(pluginId[PreRoutingWrapper])
                      case PluginType.RequestSinkType     => makeInst(pluginId[RequestSinkWrapper])
                      case PluginType.CompositeType       => makeInst(pluginId[CompositeWrapper])
                      case PluginType.TunnelHandlerType   => None
                      case PluginType.EventListenerType   => None
                      case PluginType.JobType             => None
                      case PluginType.DataExporterType    => None
                      case PluginType.RequestHandlerType  => None
                    }
                  }
                }
              }
              .collect { case Some(plugin) =>
                plugin
              }
          }
      )
    ) /*.debug { route =>
      if (route.id == "service_dev_4357b93e-f952-4de8-9d30-4c69650b22c4") {
        route.json.prettify.debugPrintln
      }
    }*/
  }
}

trait NgRouteDataStore extends BasicStore[NgRoute] {
  def template(ctx: Option[ApiActionContext[_]] = None)(implicit env: Env): NgRoute = {
    val default = NgRoute(
      location = EntityLocation.ownEntityLocation(ctx),
      id = s"route_${IdGenerator.uuid}",
      name = "New route",
      description = "A new route",
      tags = Seq.empty,
      metadata = Map.empty,
      enabled = true,
      debugFlow = false,
      capture = false,
      exportReporting = false,
      groups = Seq("default"),
      frontend = NgFrontend(
        domains = Seq(NgDomainAndPath(env.routeBaseDomain)),
        headers = Map.empty,
        query = Map.empty,
        methods = Seq.empty,
        stripPath = true,
        exact = false
      ),
      backend = NgBackend(
        targets = Seq(
          NgTarget(
            id = "target_1",
            hostname = "request.otoroshi.io",
            port = 443,
            tls = true,
            backup = false
          )
        ),
        root = "/",
        rewrite = false,
        loadBalancing = RoundRobin,
        client = NgClientConfig.default
      ),
      plugins = NgPlugins(
        Seq(
          NgPluginInstance(
            plugin = NgPluginHelper.pluginId[OverrideHost]
          )
        )
      )
    )
      .copy(location = EntityLocation.ownEntityLocation(ctx)(env))
    env.datastores.globalConfigDataStore
      .latest()(env.otoroshiExecutionContext, env)
      .templates
      .route
      .map { template =>
        NgRoute.fmt.reads(default.json.asObject.deepMerge(template)).get
      }
      .getOrElse {
        default
      }
  }
}

class KvNgRouteDataStore(redisCli: RedisLike, _env: Env) extends NgRouteDataStore with RedisLikeStore[NgRoute] {
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[NgRoute]                    = NgRoute.fmt
  override def key(id: String): String                 = s"${_env.storageRoot}:routes:${id}"
  override def extractId(value: NgRoute): String       = value.id
}
