package otoroshi.next.models

import akka.stream.Materializer
import otoroshi.api.OtoroshiEnvHolder
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.plugins._
import otoroshi.next.plugins.api._
import otoroshi.next.plugins.wrappers._
import otoroshi.next.proxy.NgProxyEngineError.NgResultProxyEngineError
import otoroshi.next.proxy.{NgProxyEngineError, NgReportPluginSequence, NgReportPluginSequenceItem}
import otoroshi.next.utils.JsonHelpers
import otoroshi.script.{NamedPlugin, PluginType}
import otoroshi.script.plugins.Plugins
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
  groups: Seq[String] = Seq("default"),
  frontend: NgFrontend,
  backend: NgBackend,
  backendRef: Option[String] = None,
  // client: ClientConfig,
  plugins: NgPlugins
) extends EntityLocationSupport {

  override def internalId: String = id
  override def theName: String = name
  override def theDescription: String = description
  override def theTags: Seq[String] = tags
  override def theMetadata: Map[String, String] = metadata
  override def json: JsValue = location.jsonWithKey ++ Json.obj(
    "id" -> id,
    "name" -> name,
    "description" -> description,
    "tags" -> tags,
    "metadata" -> metadata,
    "enabled" -> enabled,
    "debug_flow" -> debugFlow,
    "groups" -> groups,
    "frontend" -> frontend.json,
    "backend" -> backend.json,
    "backend_ref" -> backendRef.map(JsString.apply).getOrElse(JsNull).as[JsValue],
    // "client" -> client.toJson,
    "plugins" -> plugins.json
  )

  def matches(request: RequestHeader, attrs: TypedMap, matchedPath: String, pathParams: scala.collection.mutable.HashMap[String, String], skipDomainVerif: Boolean, noMoreSegments: Boolean, skipPathVerif: Boolean)(implicit env: Env): Boolean = {
    if (enabled) {
      val path = request.thePath
      val domain = request.theDomain
      val method = request.method
      val methodPasses = if (frontend.methods.isEmpty) true else frontend.methods.contains(method)
      if (methodPasses) {
        val res = frontend.domains
          .applyOnIf(!skipDomainVerif)(_.filter(d => d.domain == domain || RegexPool(d.domain).matches(domain)))
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
            val headers = request.headers.toSimpleMap.map(t => (t._1.toLowerCase, t._2))
            val secondRes = frontend.headers.map(t => (t._1.toLowerCase, t._2)).forall {
              case (key, value) => headers.get(key).contains(value)
            }
            firstRes && secondRes
          }
        val matchers = plugins.routeMatcherPlugins(request)(env.otoroshiExecutionContext, env)
        if (res && matchers.nonEmpty) {
          if (matchers.size == 1) {
            val matcher = matchers.head
            matcher.plugin.matches(NgRouteMatcherContext(
              snowflake = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).get,
              request = request,
              route = this,
              config = matcher.instance.config.raw,
              attrs = attrs,
            ))
          } else {
            matchers.forall { matcher =>
              val ctx = NgRouteMatcherContext(
                snowflake = attrs.get(otoroshi.plugins.Keys.SnowFlakeKey).get,
                request = request,
                route = this,
                config = matcher.instance.config.raw,
                attrs = attrs,
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

  lazy val userFacing: Boolean = metadata.get("otoroshi-core-user-facing").contains("true")
  lazy val useAkkaHttpClient: Boolean = metadata.get("otoroshi-core-use-akka-http-client").contains("true")
  lazy val useAkkaHttpWsClient: Boolean = metadata.get("otoroshi-core-use-akka-http-ws-client").contains("true")
  lazy val issueLetsEncryptCertificate: Boolean = metadata.get("otoroshi-core-issue-lets-encrypt-certificate").contains("true")
  lazy val issueCertificate: Boolean = metadata.get("otoroshi-core-issue-certificate").contains("true")
  lazy val issueCertificateCA: Option[String] = metadata.get("otoroshi-core-issue-certificate-ca").filter(_.nonEmpty)
  lazy val openapiUrl: Option[String] = metadata.get("otoroshi-core-openapi-url").filter(_.nonEmpty)
  lazy val originalRouteId: Option[String] = metadata.get("otoroshi-core-original-route-id").filter(_.nonEmpty)

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
      env = "prod",
      domain = "--",
      subdomain = "--",
      targets = backend.allTargets.map(_.toTarget),
      hosts = frontend.domains.map(_.domain),
      paths = frontend.domains.map(_.path),
      stripPath = frontend.stripPath,
      clientConfig = backend.client, // TODO: maybe backendref too
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
      buildMode =  plugins.getPluginByClass[BuildMode].isDefined,
      strictlyPrivate = plugins.getPluginByClass[PublicPrivatePaths].flatMap(_.config.raw.select("strict").asOpt[Boolean]).getOrElse(false),
      sendOtoroshiHeadersBack =  plugins.getPluginByClass[SendOtoroshiHeadersBack].isDefined,
      readOnly =  plugins.getPluginByClass[ReadOnlyCalls].isDefined,
      xForwardedHeaders = plugins.getPluginByClass[XForwardedHeaders].isDefined,
      overrideHost = plugins.getPluginByClass[OverrideHost].isDefined,
      allowHttp10 = plugins.getPluginByClass[DisableHttp10].isEmpty,
      // ///////////////////////////////////////////////////////////
      enforceSecureCommunication = plugins.getPluginByClass[OtoroshiChallenge].orElse(plugins.getPluginByClass[OtoroshiInfos]).isDefined,
      sendInfoToken = plugins.getPluginByClass[OtoroshiInfos].isDefined,
      sendStateChallenge = plugins.getPluginByClass[OtoroshiChallenge].isDefined,
      secComHeaders = SecComHeaders(
        claimRequestName = plugins.getPluginByClass[OtoroshiInfos].flatMap(_.config.raw.select("header_name").asOpt[String]),
        stateRequestName = plugins.getPluginByClass[OtoroshiChallenge].flatMap(_.config.raw.select("request_header_name").asOpt[String]),
        stateResponseName = plugins.getPluginByClass[OtoroshiChallenge].flatMap(_.config.raw.select("response_header_name").asOpt[String]),
      ),
      secComTtl = plugins.getPluginByClass[OtoroshiChallenge].flatMap(p => p.config.raw.select("ttl").asOpt[Long])
        .orElse(plugins.getPluginByClass[OtoroshiChallenge].flatMap(p => p.config.raw.select("ttl").asOpt[Long]))
        .getOrElse(30L).seconds,
      secComVersion = plugins.getPluginByClass[OtoroshiChallenge].map(p => p.config.raw.select("version").asOpt[String].getOrElse("V2")).flatMap(SecComVersion.apply).getOrElse(SecComVersion.V2),
      secComInfoTokenVersion = plugins.getPluginByClass[OtoroshiInfos].map(p => p.config.raw.select("version").asOpt[String].getOrElse("Latest")).flatMap(SecComInfoTokenVersion.apply).getOrElse(SecComInfoTokenVersion.Latest),
      secComExcludedPatterns = {
        (
          plugins.getPluginByClass[OtoroshiChallenge].map(_.exclude).getOrElse(Seq.empty) ++
            plugins.getPluginByClass[OtoroshiInfos].map(_.exclude).getOrElse(Seq.empty)
        ).distinct
      },
      // not needed because of the next line // secComSettings: AlgoSettings = HSAlgoSettings(512, "secret", false)
      secComUseSameAlgo = false,
      secComAlgoChallengeOtoToBack = plugins.getPluginByClass[OtoroshiChallenge].flatMap(p => AlgoSettings.fromJson(p.config.raw.select("algo_to_backend").asOpt[JsValue].getOrElse(Json.obj())).toOption).getOrElse(HSAlgoSettings(512, "secret", false)),
      secComAlgoChallengeBackToOto = plugins.getPluginByClass[OtoroshiChallenge].flatMap(p => AlgoSettings.fromJson(p.config.raw.select("algo_from_backend").asOpt[JsValue].getOrElse(Json.obj())).toOption).getOrElse(HSAlgoSettings(512, "secret", false)),
      secComAlgoInfoToken = plugins.getPluginByClass[OtoroshiInfos].flatMap(p => AlgoSettings.fromJson(p.config.raw.select("algo").asOpt[JsValue].getOrElse(Json.obj())).toOption).getOrElse(HSAlgoSettings(512, "secret", false)),
      // ///////////////////////////////////////////////////////////
      privateApp = plugins.getPluginByClass[AuthModule].isDefined,
      authConfigRef = plugins.getPluginByClass[AuthModule].flatMap(_.config.raw.select("auth_module").asOpt[String]),
      securityExcludedPatterns  = plugins.getPluginByClass[AuthModule].map(_.exclude).getOrElse(Seq.empty),
      // ///////////////////////////////////////////////////////////
      publicPatterns = plugins.getPluginByClass[PublicPrivatePaths].flatMap(p => p.config.raw.select("public_patterns").asOpt[Seq[String]])
                        .orElse(plugins.getPluginByClass[ApikeyCalls].map(p => p.exclude))
                        .getOrElse(Seq.empty),
      privatePatterns = plugins.getPluginByClass[PublicPrivatePaths].flatMap(p => p.config.raw.select("private_patterns").asOpt[Seq[String]])
                        .orElse(plugins.getPluginByClass[ApikeyCalls].map(p => p.include))
                        .getOrElse(Seq.empty),
      additionalHeaders = plugins.getPluginByClass[AdditionalHeadersIn].flatMap(p => p.config.raw.select("headers").asOpt[Map[String, String]]).getOrElse(Map.empty),
      additionalHeadersOut = plugins.getPluginByClass[AdditionalHeadersOut].flatMap(p => p.config.raw.select("headers").asOpt[Map[String, String]]).getOrElse(Map.empty),
      missingOnlyHeadersIn = plugins.getPluginByClass[MissingHeadersIn].flatMap(p => p.config.raw.select("headers").asOpt[Map[String, String]]).getOrElse(Map.empty),
      missingOnlyHeadersOut = plugins.getPluginByClass[MissingHeadersOut].flatMap(p => p.config.raw.select("headers").asOpt[Map[String, String]]).getOrElse(Map.empty),
      removeHeadersIn = plugins.getPluginByClass[RemoveHeadersIn].flatMap(p => p.config.raw.select("header_names").asOpt[Seq[String]]).getOrElse(Seq.empty),
      removeHeadersOut = plugins.getPluginByClass[RemoveHeadersOut].flatMap(p => p.config.raw.select("header_names").asOpt[Seq[String]]).getOrElse(Seq.empty),
      headersVerification = plugins.getPluginByClass[HeadersValidation].flatMap(p => p.config.raw.select("headers").asOpt[Map[String, String]]).getOrElse(Map.empty),
      ipFiltering = if (plugins.getPluginByClass[IpAddressBlockList].isDefined || plugins.getPluginByClass[IpAddressAllowedList].isDefined) {
        IpFiltering(
          whitelist = plugins.getPluginByClass[IpAddressAllowedList].flatMap(_.config.raw.select("addresses").asOpt[Seq[String]]).getOrElse(Seq.empty),
          blacklist = plugins.getPluginByClass[IpAddressBlockList].flatMap(_.config.raw.select("addresses").asOpt[Seq[String]]).getOrElse(Seq.empty),
        )
      } else {
        IpFiltering()
      },
      api = openapiUrl.map(url => ApiDescriptor(true, url.some)).getOrElse(ApiDescriptor(false, None)),
      jwtVerifier = plugins.getPluginByClass[JwtVerification].flatMap(p => p.config.raw.select("verifiers").asOpt[Seq[String]].map(ids => RefJwtVerifier(ids, true, p.exclude))).getOrElse(RefJwtVerifier()),
      cors = plugins.getPluginByClass[otoroshi.next.plugins.Cors].flatMap(p => CorsSettings.fromJson(p.config.raw).toOption).getOrElse(CorsSettings()),
      redirection = plugins.getPluginByClass[Redirection].flatMap(p => RedirectionSettings.format.reads(p.config.raw).asOpt).getOrElse(RedirectionSettings(false)),
      restrictions = plugins.getPluginByClass[RoutingRestrictions].flatMap(p => Restrictions.format.reads(p.config.raw).asOpt.map(_.copy(enabled = true))).getOrElse(Restrictions()),
      tcpUdpTunneling = Seq(
        plugins.getPluginByClass[TcpTunnel],
        plugins.getPluginByClass[UdpTunnel]
      ).flatten.nonEmpty,
      detectApiKeySooner = plugins.getPluginByClass[ApikeyCalls].flatMap(p => p.config.raw.select("validate").asOpt[Boolean].map(v => !v)).getOrElse(false),
      canary = plugins.getPluginByClass[CanaryMode].flatMap(p => Canary.format.reads(p.config.raw).asOpt.map(_.copy(enabled = true))).getOrElse(Canary()),
      chaosConfig = plugins.getPluginByClass[SnowMonkeyChaos].flatMap(p => ChaosConfig._fmt.reads(p.config.raw).asOpt.map(_.copy(enabled = true))).getOrElse(ChaosConfig(enabled = true)),
      gzip = plugins.getPluginByClass[GzipResponseCompressor].flatMap(p => GzipConfig._fmt.reads(p.config.raw).asOpt.map(_.copy(enabled = true, excludedPatterns = p.exclude))).getOrElse(GzipConfig(enabled = true)),
      apiKeyConstraints = {
        plugins.getPluginByClass[ApikeyCalls].flatMap { plugin =>
          NgApikeyCallsConfig.format.reads(plugin.config.raw).asOpt.map(_.legacy)
        }.getOrElse(ApiKeyConstraints())
      },
      plugins = {
        val possiblePlugins = plugins.slots.filter(slot => slot.plugin.startsWith("cp:otoroshi.next.plugins.wrappers."))
        val refs = possiblePlugins.map(_.config.raw.select("plugin").as[String])
        val exclusion: Seq[String] = if (possiblePlugins.isEmpty) Seq.empty else possiblePlugins.map(_.exclude).reduce((a, b) => a.intersect(b))
        val config = possiblePlugins.flatMap(p => p.config.raw.value.keySet.-("plugin").headOption.map(plug => (p.config.raw, plug))).map {
          case (pconfig, key) => pconfig.select(key).asObject
        }.foldLeft(Json.obj())(_ ++ _)
        Plugins(
          enabled = possiblePlugins.nonEmpty,
          excluded = exclusion,
          refs = refs,
          config = config
        )
      },
    )
  }

  def transformError(__ctx: NgTransformerErrorContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    val all_plugins = __ctx.attrs.get(Keys.ContextualPluginsKey).map(_.transformerPluginsThatTransformsError).getOrElse(plugins.transformerPluginsThatTransformsError(__ctx.request))
    if (all_plugins.nonEmpty) {
      val report = __ctx.report
      var sequence = NgReportPluginSequence(
        size = all_plugins.size,
        kind = "error-transformer-plugins",
        start = System.currentTimeMillis(),
        stop = 0L,
        start_ns = System.nanoTime(),
        stop_ns = 0L,
        plugins = Seq.empty,
      )
      def markPluginItem(item: NgReportPluginSequenceItem, ctx: NgTransformerErrorContext, debug: Boolean, result: JsValue): Unit = {
        val nottrig: Seq[String] = __ctx.attrs.get(Keys.ContextualPluginsKey).map(_.tpwoErrors.map(_.instance.plugin)).getOrElse(Seq.empty[String])
        sequence = sequence.copy(
          plugins = sequence.plugins :+ item.copy(
            stop = System.currentTimeMillis(),
            stop_ns = System.nanoTime(),
            out = Json.obj(
              "not_triggered" -> nottrig,
              "result" -> result,
            ).applyOnIf(debug)(_ ++ Json.obj("ctx" -> ctx.json))
          )
        )
      }
      if (all_plugins.size == 1) {
        val wrapper = all_plugins.head
        val pluginConfig: JsValue = wrapper.plugin.defaultConfig.map(dc => dc ++ wrapper.instance.config.raw).getOrElse(wrapper.instance.config.raw)
        val ctx = __ctx.copy(config = pluginConfig)
        val debug = debugFlow || wrapper.instance.debug
        val in: JsValue = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
        val item = NgReportPluginSequenceItem(wrapper.instance.plugin, wrapper.plugin.name, System.currentTimeMillis(), System.nanoTime(), -1L, -1L, in, JsNull)
        wrapper.plugin.transformError(ctx).transform {
          case Failure(exception) =>
            markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
            report.setContext(sequence.stopSequence().json)
            Success(Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> "an error happened during response-transformation plugins phase", "error" -> JsonHelpers.errToJson(exception))))
          case Success(resp_next) =>
            markPluginItem(item, ctx.copy(otoroshiResponse = resp_next), debug, Json.obj("kind" -> "successful"))
            report.setContext(sequence.stopSequence().json)
            Success(resp_next.asResult)
        }
      } else {
        val promise = Promise[Either[NgProxyEngineError, NgPluginHttpResponse]]()
        def next(_ctx: NgTransformerErrorContext, plugins: Seq[NgPluginWrapper[NgRequestTransformer]]): Unit = {
          plugins.headOption match {
            case None => promise.trySuccess(Right(_ctx.otoroshiResponse))
            case Some(wrapper) => {
              val pluginConfig: JsValue = wrapper.plugin.defaultConfig.map(dc => dc ++ wrapper.instance.config.raw).getOrElse(wrapper.instance.config.raw)
              val ctx = _ctx.copy(config = pluginConfig)
              val debug = debugFlow || wrapper.instance.debug
              val in: JsValue = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
              val item = NgReportPluginSequenceItem(wrapper.instance.plugin, wrapper.plugin.name, System.currentTimeMillis(), System.nanoTime(), -1L, -1L, in, JsNull)
              wrapper.plugin.transformError(ctx).andThen {
                case Failure(exception) =>
                  markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Left(NgResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> "an error happened during response-transformation plugins phase", "error" -> JsonHelpers.errToJson(exception))))))
                case Success(resp_next) if plugins.size == 1 =>
                  markPluginItem(item, ctx.copy(otoroshiResponse = resp_next), debug, Json.obj("kind" -> "successful"))
                  report.setContext(sequence.stopSequence().json)
                  promise.trySuccess(Right(resp_next))
                case Success(resp_next) =>
                  markPluginItem(item, ctx.copy(otoroshiResponse = resp_next), debug, Json.obj("kind" -> "successful"))
                  next(_ctx.copy(otoroshiResponse = resp_next), plugins.tail)
              }
            }
          }
        }
        next(__ctx, all_plugins)
        promise.future.flatMap {
          case Left(err) => err.asResult()
          case Right(res) => res.asResult.vfuture
        }
      }
    } else {
      __ctx.otoroshiResponse.asResult.vfuture
    }
  }

  def contextualPlugins(global_plugins: NgPlugins, request: RequestHeader)(implicit env: Env, ec: ExecutionContext): NgContextualPlugins = {
    NgContextualPlugins(plugins, global_plugins, request, env, ec)
  }
}

object NgRoute {

  val fake = NgRoute(
    location = EntityLocation.default,
    id = s"route_${IdGenerator.uuid}",
    name = "Fake route",
    description = "A fake route to tryout the new engine",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = true,
    groups = Seq("default"),
    frontend = NgFrontend(
      domains = Seq(NgDomainAndPath("fake-next-gen.oto.tools")),
      headers = Map.empty,
      methods = Seq.empty,
      stripPath = true,
      exact = false,
    ),
    backendRef = None,
    backend = NgBackend(
      targets = Seq(NgTarget(
        id = "tls://mirror.otoroshi.io:443",
        hostname = "mirror.otoroshi.io",
        port = 443,
        tls = true
      )),
      targetRefs = Seq.empty,
      root = "/",
      rewrite = false,
      loadBalancing = RoundRobin,
      healthCheck = None,
      client = ClientConfig(),
    ),
    plugins = NgPlugins(Seq(
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[ApikeyCalls]
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost],
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[AdditionalHeadersOut],
        config = NgPluginInstanceConfig(Json.obj(
          "headers" -> Json.obj(
            "bar" -> "foo"
          )
        ))
      ),
      NgPluginInstance(
        plugin = NgPluginHelper.pluginId[AdditionalHeadersOut],
        config = NgPluginInstanceConfig(Json.obj(
          "headers" -> Json.obj(
            "bar2" -> "foo2"
          )
        ))
      )
    ))
  )

  val fmt = new Format[NgRoute] {
    override def writes(o: NgRoute): JsValue = o.json
    override def reads(json: JsValue): JsResult[NgRoute] = Try {
      val ref = json.select("backend_ref").asOpt[String]
      val refBackend = ref.flatMap(r => OtoroshiEnvHolder.get().proxyState.backend(r)).getOrElse(NgBackend.empty)
      NgRoute(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").as[String],
        name = json.select("id").as[String],
        description = json.select("description").asOpt[String].getOrElse(""),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        debugFlow = json.select("debug_flow").asOpt[Boolean].getOrElse(false),
        groups = json.select("groups").asOpt[Seq[String]].getOrElse(Seq("default")),
        frontend = NgFrontend.readFrom(json.select("frontend")),
        backend = ref match {
          case None => NgBackend.readFrom(json.select("backend"))
          case Some(r) => refBackend
        },
        backendRef = ref,
        // client = (json \ "client").asOpt(ClientConfig.format).getOrElse(ClientConfig()),
        plugins = NgPlugins.readFrom(json.select("plugins")),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(route) => JsSuccess(route)
    }
  }

  def fromServiceDescriptor(service: ServiceDescriptor, debug: Boolean)(implicit ec: ExecutionContext, env: Env): NgRoute = {
    import NgPluginHelper.pluginId
    NgRoute(
      location = service.location,
      id = service.id,
      name = service.name,
      description = service.description,
      tags = service.tags ++ Seq(s"env:${service.env}"),
      metadata = service.metadata.applyOnIf(service.useAkkaHttpClient) { meta =>
        meta ++ Map("otoroshi-core-use-akka-http-client" -> "true")
      }.applyOnIf(service.useNewWSClient) { meta =>
        meta ++ Map("otoroshi-core-use-akka-http-ws-client" -> "true")
      }.applyOnIf(service.letsEncrypt) { meta =>
        meta ++ Map("otoroshi-core-issue-lets-encrypt-certificate" -> "true")
      }.applyOnIf(service.issueCert) { meta =>
        meta ++ Map(
          "otoroshi-core-issue-certificate" -> "true",
          "otoroshi-core-issue-certificate-ca" -> service.issueCertCA.getOrElse("")
        )
      }.applyOnIf(service.userFacing) { meta =>
        meta ++ Map("otoroshi-core-user-facing" -> "true")
      }.applyOnIf(service.api.exposeApi) { meta =>
        meta ++ Map("otoroshi-core-openapi-url" -> service.api.openApiDescriptorUrl.getOrElse(""))
      },
      enabled = service.enabled,
      debugFlow = debug,
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
        methods = Seq.empty, // get from restrictions ???
        stripPath = service.stripPath,
        exact = false,
      ),
      backendRef = None,
      backend = NgBackend(
        targets = service.targets.map(NgTarget.fromTarget),
        targetRefs = Seq.empty,
        root = service.root,
        rewrite = false,
        loadBalancing = service.targetsLoadBalancing,
        healthCheck = if (service.healthCheck.enabled) service.healthCheck.some else None,
        client = service.clientConfig,
      ),
      groups = service.groups,
      plugins = NgPlugins(
        Seq.empty[NgPluginInstance]
          .applyOnIf(service.forceHttps) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[ForceHttpsTraffic],
            )
          }
          .applyOnIf(service.overrideHost) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[OverrideHost],
            )
          }
          .applyOnIf(service.headersVerification.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[HeadersValidation],
              config = NgPluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.headersVerification.mapValues(JsString.apply))
              ))
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
          .applyOnIf(service.ipFiltering.blacklist.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[IpAddressBlockList],
              config = NgPluginInstanceConfig(Json.obj(
                "addresses" -> JsArray(service.ipFiltering.blacklist.map(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.ipFiltering.whitelist.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[IpAddressAllowedList],
              config = NgPluginInstanceConfig(Json.obj(
                "addresses" -> JsArray(service.ipFiltering.whitelist.map(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.redirection.enabled) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[Redirection],
              config = NgPluginInstanceConfig(service.redirection.toJson.as[JsObject])
            )
          }
          .applyOnIf(service.additionalHeaders.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[AdditionalHeadersIn],
              config = NgPluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.additionalHeaders.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.additionalHeadersOut.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[AdditionalHeadersOut],
              config = NgPluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.additionalHeadersOut.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.missingOnlyHeadersIn.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[MissingHeadersIn],
              config = NgPluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.missingOnlyHeadersIn.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.missingOnlyHeadersOut.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[MissingHeadersOut],
              config = NgPluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.missingOnlyHeadersOut.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.removeHeadersIn.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[RemoveHeadersIn],
              config = NgPluginInstanceConfig(Json.obj(
                "headers" -> JsArray(service.removeHeadersIn.map(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.removeHeadersOut.nonEmpty) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[RemoveHeadersOut],
              config = NgPluginInstanceConfig(Json.obj(
                "headers" -> JsArray(service.removeHeadersOut.map(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.sendOtoroshiHeadersBack) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[SendOtoroshiHeadersBack],
            )
          }
          .applyOnIf(service.xForwardedHeaders) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[XForwardedHeaders],
            )
          }
          // .applyOnIf(service.publicPatterns.nonEmpty || service.privatePatterns.nonEmpty) { seq =>
          //   seq :+ PluginInstance(
          //     plugin = pluginId[PublicPrivatePaths],
          //     config = PluginInstanceConfig(Json.obj(
          //       "private_patterns" -> JsArray(service.privatePatterns.map(JsString.apply)),
          //       "public_patterns" -> JsArray(service.publicPatterns.map(JsString.apply)),
          //       "strict" -> service.strictlyPrivate
          //     ))
          //   )
          // }
          .applyOnIf(service.jwtVerifier.enabled && service.jwtVerifier.isRef) { seq =>
            val verifier = service.jwtVerifier.asInstanceOf[RefJwtVerifier]
            seq :+ NgPluginInstance(
              plugin = pluginId[JwtVerification],
              exclude = verifier.excludedPatterns,
              config = NgPluginInstanceConfig(Json.obj(
                "verifiers" -> JsArray(verifier.ids.map(JsString.apply)),
              ))
            )
          }
          .applyOnIf(service.restrictions.enabled) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[RoutingRestrictions],
              config = NgPluginInstanceConfig(service.restrictions.json.asObject)
            )
          }
          .applyOnIf(service.privateApp && service.authConfigRef.isDefined) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[AuthModule],
              exclude = service.securityExcludedPatterns,
              config = NgPluginInstanceConfig(Json.obj(
                "auth_module" -> service.authConfigRef.get,
                "pass_with_apikey" -> !service.strictlyPrivate
              ))
            )
          }
          .applyOnIf(service.enforceSecureCommunication && service.sendStateChallenge) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[OtoroshiChallenge],
              exclude = service.secComExcludedPatterns,
              config = NgPluginInstanceConfig(Json.obj(
                "version" -> service.secComVersion.str,
                "ttl" -> service.secComTtl.toSeconds,
                "request_header_name" -> service.secComHeaders.stateRequestName.getOrElse("Otoroshi-State").json,
                "response_header_name" -> service.secComHeaders.stateResponseName.getOrElse("Otoroshi-State-Resp").json,
                "algo_to_backend" -> (if (service.secComUseSameAlgo) service.secComSettings.asJson else service.secComAlgoChallengeOtoToBack.asJson),
                "algo_from_backend" -> (if (service.secComUseSameAlgo) service.secComSettings.asJson else service.secComAlgoChallengeBackToOto.asJson),
              ))
            )
          }
          .applyOnIf(service.enforceSecureCommunication && service.sendInfoToken) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[OtoroshiInfos],
              exclude = service.secComExcludedPatterns,
              config = NgPluginInstanceConfig(Json.obj(
                "version" -> service.secComInfoTokenVersion.version,
                "ttl" -> service.secComTtl.toSeconds,
                "header_name" -> service.secComHeaders.stateRequestName.getOrElse("Otoroshi-Claim").json,
                "algo" -> (if (service.secComUseSameAlgo) service.secComSettings.asJson else service.secComAlgoInfoToken.asJson),
              ))
            )
          }
          .applyOnIf(service.cors.enabled) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[Cors],
              exclude = service.cors.excludedPatterns,
              config = NgPluginInstanceConfig(service.cors.asJson.asObject)
            )
          }
          .applyOnIf(!service.publicPatterns.contains("/.*")) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[ApikeyCalls],
              include = service.privatePatterns,
              exclude = service.publicPatterns,
              config = NgPluginInstanceConfig(NgApikeyCallsConfig.fromLegacy(service.apiKeyConstraints).copy(
                validate = !service.detectApiKeySooner,
                passWithUser = !service.strictlyPrivate
              ).json.asObject)
            )
          }
          .applyOnIf(service.gzip.enabled) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[GzipResponseCompressor],
              exclude = service.gzip.excludedPatterns,
              config = NgPluginInstanceConfig(service.gzip.asJson.asObject)
            )
          }
          .applyOnIf(service.canary.enabled) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[CanaryMode],
              config = NgPluginInstanceConfig(service.canary.toJson.asObject)
            )
          }
          .applyOnIf(service.chaosConfig.enabled) { seq =>
            seq :+ NgPluginInstance(
              plugin = pluginId[SnowMonkeyChaos],
              config = NgPluginInstanceConfig(service.chaosConfig.asJson.asObject)
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
          .applyOnIf(service.plugins.enabled) { seq =>
            seq ++ service.plugins.refs.map { ref =>
              (ref, env.scriptManager.getAnyScript[NamedPlugin](ref))
            }
            .collect {
              case (ref, Right(plugin)) => (ref, plugin)
            }
            .map {
              case (ref, plugin) =>

                def makeInst(id: String): Option[NgPluginInstance] = {
                  val config: JsValue =  plugin.configRoot
                    .flatMap(r => service.plugins.config.select(r).asOpt[JsValue])
                    .orElse(plugin.defaultConfig)
                    .getOrElse(Json.obj())
                  val configName: String = plugin.configRoot.getOrElse("config")
                  NgPluginInstance(
                    plugin = id,
                    exclude = service.plugins.excluded,
                    config = NgPluginInstanceConfig(Json.obj(
                      "plugin" -> ref,
                      configName -> config
                    ))
                  ).some
                }

                plugin.pluginType match {
                  case PluginType.AppType =>             makeInst(pluginId[RequestTransformerWrapper])
                  case PluginType.TransformerType =>     makeInst(pluginId[RequestTransformerWrapper])
                  case PluginType.AccessValidatorType => makeInst(pluginId[AccessValidatorWrapper])
                  case PluginType.PreRoutingType =>      makeInst(pluginId[PreRoutingWrapper])
                  case PluginType.RequestSinkType =>     makeInst(pluginId[RequestSinkWrapper])
                  case PluginType.CompositeType =>       makeInst(pluginId[CompositeWrapper])
                  case PluginType.EventListenerType =>   None
                  case PluginType.JobType =>             None
                  case PluginType.DataExporterType =>    None
                  case PluginType.RequestHandlerType =>  None
                }
            }
            .collect {
              case Some(plugin) => plugin
            }
          }
      )
    )
  }
}

trait NgRouteDataStore extends BasicStore[NgRoute]

class KvNgRouteDataStore(redisCli: RedisLike, _env: Env)
  extends NgRouteDataStore
    with RedisLikeStore[NgRoute] {
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[NgRoute]               = NgRoute.fmt
  override def key(id: String): Key             = Key.Empty / _env.storageRoot / "routes" / id
  override def extractId(value: NgRoute): String  = value.id
}
