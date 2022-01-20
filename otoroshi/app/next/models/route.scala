package otoroshi.next.models

import akka.stream.Materializer
import otoroshi.api.OtoroshiEnvHolder
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.plugins._
import otoroshi.next.plugins.api._
import otoroshi.next.proxy.ProxyEngineError.ResultProxyEngineError
import otoroshi.next.proxy.{ProxyEngineError, ReportPluginSequence, ReportPluginSequenceItem}
import otoroshi.next.utils.JsonHelpers
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
import otoroshi.script.NamedPlugin
import otoroshi.script.PluginType

case class DomainAndPath(raw: String) {
  private lazy val parts = raw.split("\\/")
  lazy val domain = parts.head
  lazy val path = if (parts.size == 1) "/" else parts.tail.mkString("/", "/", "")
  def json: JsValue = JsString(raw)
}

case class Frontend(domains: Seq[DomainAndPath], headers: Map[String, String], stripPath: Boolean, apikey: ApiKeyRouteMatcher) {
  def json: JsValue = Json.obj(
    "domains" -> JsArray(domains.map(_.json)),
    "strip_path" -> stripPath,
    "headers" -> headers,
    "apikey" -> apikey.gentleJson
  )
}

object Frontend {
  def readFrom(lookup: JsLookupResult): Frontend = {
    lookup.asOpt[JsObject] match {
      case None => Frontend(Seq.empty, Map.empty, true, ApiKeyRouteMatcher())
      case Some(obj) => Frontend(
        domains = obj.select("domains").asOpt[Seq[String]].map(_.map(DomainAndPath.apply)).getOrElse(Seq.empty),
        stripPath = obj.select("strip_path").asOpt[Boolean].getOrElse(true),
        headers = obj.select("headers").asOpt[Map[String, String]].getOrElse(Map.empty),
        apikey = obj.select("apikey").asOpt[JsValue].flatMap(v => ApiKeyRouteMatcher.format.reads(v).asOpt).getOrElse(ApiKeyRouteMatcher())
      )
    }
  }
}

case class Backend(targets: Seq[NgTarget], targetRefs: Seq[String], root: String, loadBalancing: LoadBalancing) {
  // I know it's not ideal but we'll go with it for now !
  lazy val allTargets: Seq[NgTarget] = targets ++ targetRefs.map(OtoroshiEnvHolder.get().proxyState.backend).collect {
    case Some(backend) => backend
  }
  def json: JsValue = Json.obj(
    "targets" -> JsArray(targets.map(_.json)),
    "target_refs" -> JsArray(targetRefs.map(JsString.apply)),
    "root" -> root,
    "load_balancing" -> loadBalancing.toJson
  )
}

object Backend {
  def readFrom(lookup: JsLookupResult): Backend = readFromJson(lookup.as[JsValue])
  def readFromJson(lookup: JsValue): Backend = {
    lookup.asOpt[JsObject] match {
      case None => Backend(Seq.empty, Seq.empty, "/", RoundRobin)
      case Some(obj) => Backend(
        targets = obj.select("targets").asOpt[Seq[JsValue]].map(_.map(NgTarget.readFrom)).getOrElse(Seq.empty),
        targetRefs = obj.select("target_refs").asOpt[Seq[String]].getOrElse(Seq.empty),
        root = obj.select("root").asOpt[String].getOrElse("/"),
        loadBalancing = LoadBalancing.format.reads(obj.select("load_balancing").asOpt[JsObject].getOrElse(Json.obj())).getOrElse(RoundRobin)
      )
    }
  }
}

case class Route(
  location: EntityLocation,
  id: String,
  name: String,
  description: String,
  tags: Seq[String],
  metadata: Map[String, String],
  enabled: Boolean,
  debugFlow: Boolean,
  groups: Seq[String] = Seq("default"),
  frontend: Frontend,
  backend: Backend,
  client: ClientConfig,
  healthCheck: HealthCheck,
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
    "client" -> client.toJson,
    "health_check" -> healthCheck.toJson,
    "plugins" -> plugins.json
  )

  def matches(request: RequestHeader, attrs: TypedMap, skipDomainVerif: Boolean)(implicit env: Env): Boolean = {
    if (enabled) {
      val path = request.thePath
      val domain = request.theDomain
      val res = frontend.domains
        .applyOnIf(!skipDomainVerif)(_.filter(d => d.domain == domain || RegexPool(d.domain).matches(domain)))
        .exists { d =>
          path.startsWith(d.path) || RegexPool(d.path).matches(path)
        }
        .applyOnIf(frontend.headers.nonEmpty) { firstRes =>
          val headers = request.headers.toSimpleMap.map(t => (t._1.toLowerCase, t._2))
          val secondRes = frontend.headers.map(t => (t._1.toLowerCase, t._2)).forall {
            case (key, value) => headers.get(key).contains(value)
          }
          firstRes && secondRes
        }
      val matchers = plugins.routeMatcherPlugins(request)(env.otoroshiExecutionContext, env)
      if (matchers.nonEmpty) {
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
      } else {
        res
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
      clientConfig = client,
      healthCheck = healthCheck,
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
          ApiKeyConstraints.format.reads(plugin.config.raw).asOpt
        }.map { constraints =>
          constraints.copy(routing = frontend.apikey)
        }.getOrElse(ApiKeyConstraints())
      }
      // TODO: handle plugins
    )
  }

  def transformError(__ctx: NgTransformerErrorContext)(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Result] = {
    val all_plugins = plugins.transformerPlugins(__ctx.request)
    if (all_plugins.nonEmpty) {
      val promise = Promise[Either[ProxyEngineError, NgPluginHttpResponse]]()
      val report = __ctx.report
      var sequence = ReportPluginSequence(
        size = all_plugins.size,
        kind = "error-transformer-plugins",
        start = System.currentTimeMillis(),
        stop = 0L ,
        start_ns = System.nanoTime(),
        stop_ns = 0L,
        plugins = Seq.empty,
      )
      def markPluginItem(item: ReportPluginSequenceItem, ctx: NgTransformerErrorContext, debug: Boolean, result: JsValue): Unit = {
        sequence = sequence.copy(
          plugins = sequence.plugins :+ item.copy(
            stop = System.currentTimeMillis(),
            stop_ns = System.nanoTime(),
            out = Json.obj(
              "result" -> result,
            ).applyOnIf(debug)(_ ++ Json.obj("ctx" -> ctx.json))
          )
        )
      }
      def next(_ctx: NgTransformerErrorContext, plugins: Seq[PluginWrapper[NgRequestTransformer]]): Unit = {
        plugins.headOption match {
          case None => promise.trySuccess(Right(_ctx.otoroshiResponse))
          case Some(wrapper) => {
            val pluginConfig: JsValue = wrapper.plugin.defaultConfig.map(dc => dc ++ wrapper.instance.config.raw).getOrElse(wrapper.instance.config.raw)
            val ctx = _ctx.copy(config = pluginConfig)
            val debug = debugFlow || wrapper.instance.debug
            val in: JsValue = if (debug) Json.obj("ctx" -> ctx.json) else JsNull
            val item = ReportPluginSequenceItem(wrapper.instance.plugin, wrapper.plugin.name, System.currentTimeMillis(), System.nanoTime(), -1L, -1L, in, JsNull)
            wrapper.plugin.transformError(ctx).andThen {
              case Failure(exception) =>
                markPluginItem(item, ctx, debug, Json.obj("kind" -> "failure", "error" -> JsonHelpers.errToJson(exception)))
                report.setContext(sequence.stopSequence().json)
                promise.trySuccess(Left(ResultProxyEngineError(Results.InternalServerError(Json.obj("error" -> "internal_server_error", "error_description" -> "an error happened during response-transformation plugins phase", "error" -> JsonHelpers.errToJson(exception))))))
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
    } else {
      __ctx.otoroshiResponse.asResult.vfuture
    }
  }
}

object Route {

  val fake = Route(
    location = EntityLocation.default,
    id = s"route_${IdGenerator.uuid}",
    name = "Fake route",
    description = "A fake route to tryout the new engine",
    tags = Seq.empty,
    metadata = Map.empty,
    enabled = true,
    debugFlow = true,
    groups = Seq("default"),
    frontend = Frontend(
      domains = Seq(DomainAndPath("fake-next-gen.oto.tools")),
      headers = Map.empty,
      stripPath = true,
      apikey = ApiKeyRouteMatcher()
    ),
    backend = Backend(
      targets = Seq(NgTarget(
        id = "tls://mirror.otoroshi.io:443",
        hostname = "mirror.otoroshi.io",
        port = 443,
        tls = true
      )),
      targetRefs = Seq.empty,
      root = "/",
      loadBalancing = RoundRobin
    ),
    client = ClientConfig(),
    healthCheck = HealthCheck(false, "/"),
    plugins = NgPlugins(Seq(
      PluginInstance(
        plugin = NgPluginHelper.pluginId[ApikeyCalls]
      ),
      PluginInstance(
        plugin = NgPluginHelper.pluginId[OverrideHost],
      ),
      PluginInstance(
        plugin = NgPluginHelper.pluginId[AdditionalHeadersOut],
        config = PluginInstanceConfig(Json.obj(
          "headers" -> Json.obj(
            "bar" -> "foo"
          )
        ))
      ),
      PluginInstance(
        plugin = NgPluginHelper.pluginId[AdditionalHeadersOut],
        config = PluginInstanceConfig(Json.obj(
          "headers" -> Json.obj(
            "bar2" -> "foo2"
          )
        ))
      )
    ))
  )

  val fmt = new Format[Route] {
    override def writes(o: Route): JsValue = o.json
    override def reads(json: JsValue): JsResult[Route] = Try {
      Route(
        location = otoroshi.models.EntityLocation.readFromKey(json),
        id = json.select("id").as[String],
        name = json.select("id").as[String],
        description = json.select("description").asOpt[String].getOrElse(""),
        tags = json.select("tags").asOpt[Seq[String]].getOrElse(Seq.empty),
        metadata = json.select("metadata").asOpt[Map[String, String]].getOrElse(Map.empty),
        enabled = json.select("enabled").asOpt[Boolean].getOrElse(true),
        debugFlow = json.select("debug_flow").asOpt[Boolean].getOrElse(false),
        groups = json.select("groups").asOpt[Seq[String]].getOrElse(Seq("default")),
        frontend = Frontend.readFrom(json.select("frontend")),
        backend = Backend.readFrom(json.select("backend")),
        healthCheck = (json \ "health_check").asOpt(HealthCheck.format).getOrElse(HealthCheck(false, "/")),
        client = (json \ "client").asOpt(ClientConfig.format).getOrElse(ClientConfig()),
        plugins = NgPlugins.readFrom(json.select("plugins")),
      )
    } match {
      case Failure(exception) => JsError(exception.getMessage)
      case Success(route) => JsSuccess(route)
    }
  }

  def fromServiceDescriptor(service: ServiceDescriptor, debug: Boolean)(implicit ec: ExecutionContext, env: Env): Route = {
    import NgPluginHelper.pluginId
    Route(
      location = service.location,
      id = service.id,
      name = service.name,
      description = service.description,
      tags = service.tags,
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
      frontend = Frontend(
        domains = {
          val dap = if (service.allPaths.isEmpty) {
            service.allHosts.map(h => s"$h${service.matchingRoot.getOrElse("/")}")
          } else {
            service.allPaths.flatMap(path => service.allHosts.map(host => s"$host$path"))
          }
          dap.map(DomainAndPath.apply).distinct
        },
        headers = service.matchingHeaders,
        stripPath = service.stripPath,
        apikey = service.apiKeyConstraints.routing,
      ),
      backend = Backend(
        targets = service.targets.map(NgTarget.fromTarget),
        targetRefs = Seq.empty,
        root = service.root,
        loadBalancing = service.targetsLoadBalancing
      ),
      groups = service.groups,
      client = service.clientConfig,
      healthCheck = service.healthCheck,
      plugins = NgPlugins(
        Seq.empty[PluginInstance]
          .applyOnIf(service.forceHttps) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[ForceHttpsTraffic],
            )
          }
          .applyOnIf(service.overrideHost) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[OverrideHost],
            )
          }
          .applyOnIf(service.headersVerification.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[HeadersValidation],
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.headersVerification.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.maintenanceMode) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[MaintenanceMode]
            )
          }
          .applyOnIf(service.buildMode) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[BuildMode]
            )
          }
          .applyOnIf(!service.allowHttp10) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[DisableHttp10]
            )
          }
          .applyOnIf(service.readOnly) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[ReadOnlyCalls]
            )
          }
          .applyOnIf(service.ipFiltering.blacklist.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[IpAddressBlockList],
              config = PluginInstanceConfig(Json.obj(
                "addresses" -> JsArray(service.ipFiltering.blacklist.map(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.ipFiltering.whitelist.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[IpAddressAllowedList],
              config = PluginInstanceConfig(Json.obj(
                "addresses" -> JsArray(service.ipFiltering.whitelist.map(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.redirection.enabled) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[Redirection],
              config = PluginInstanceConfig(service.redirection.toJson.as[JsObject])
            )
          }
          .applyOnIf(service.additionalHeaders.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[AdditionalHeadersIn],
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.additionalHeaders.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.additionalHeadersOut.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[AdditionalHeadersOut],
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.additionalHeadersOut.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.missingOnlyHeadersIn.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[MissingHeadersIn],
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.missingOnlyHeadersIn.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.missingOnlyHeadersOut.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[MissingHeadersOut],
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsObject(service.missingOnlyHeadersOut.mapValues(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.removeHeadersIn.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[RemoveHeadersIn],
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsArray(service.removeHeadersIn.map(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.removeHeadersOut.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[RemoveHeadersOut],
              config = PluginInstanceConfig(Json.obj(
                "headers" -> JsArray(service.removeHeadersOut.map(JsString.apply))
              ))
            )
          }
          .applyOnIf(service.sendOtoroshiHeadersBack) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[SendOtoroshiHeadersBack],
            )
          }
          .applyOnIf(service.xForwardedHeaders) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[XForwardedHeaders],
            )
          }
          .applyOnIf(service.publicPatterns.nonEmpty || service.privatePatterns.nonEmpty) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[PublicPrivatePaths],
              config = PluginInstanceConfig(Json.obj(
                "private_patterns" -> JsArray(service.privatePatterns.map(JsString.apply)),
                "public_patterns" -> JsArray(service.publicPatterns.map(JsString.apply)),
                "strict" -> service.strictlyPrivate
              ))
            )
          }
          .applyOnIf(service.jwtVerifier.enabled && service.jwtVerifier.isRef) { seq =>
            val verifier = service.jwtVerifier.asInstanceOf[RefJwtVerifier]
            seq :+ PluginInstance(
              plugin = pluginId[JwtVerification],
              exclude = verifier.excludedPatterns,
              config = PluginInstanceConfig(Json.obj(
                "verifiers" -> JsArray(verifier.ids.map(JsString.apply)),
              ))
            )
          }
          .applyOnIf(service.restrictions.enabled) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[RoutingRestrictions],
              config = PluginInstanceConfig(service.restrictions.json.asObject)
            )
          }
          .applyOnIf(service.privateApp && service.authConfigRef.isDefined) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[AuthModule],
              exclude = service.securityExcludedPatterns,
              config = PluginInstanceConfig(Json.obj(
                "auth_module" -> service.authConfigRef.get,
                "pass_with_apikey" -> !service.strictlyPrivate
              ))
            )
          }
          .applyOnIf(service.enforceSecureCommunication && service.sendStateChallenge) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[OtoroshiChallenge],
              exclude = service.secComExcludedPatterns,
              config = PluginInstanceConfig(Json.obj(
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
            seq :+ PluginInstance(
              plugin = pluginId[OtoroshiInfos],
              exclude = service.secComExcludedPatterns,
              config = PluginInstanceConfig(Json.obj(
                "version" -> service.secComInfoTokenVersion.version,
                "ttl" -> service.secComTtl.toSeconds,
                "header_name" -> service.secComHeaders.stateRequestName.getOrElse("Otoroshi-Claim").json,
                "algo" -> (if (service.secComUseSameAlgo) service.secComSettings.asJson else service.secComAlgoInfoToken.asJson),
              ))
            )
          }
          .applyOnIf(service.cors.enabled) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[Cors],
              exclude = service.cors.excludedPatterns,
              config = PluginInstanceConfig(service.cors.asJson.asObject)
            )
          }
          .applyOnIf(true) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[ApikeyCalls],
              include = service.privatePatterns,
              exclude = service.publicPatterns,
              config = PluginInstanceConfig(service.apiKeyConstraints.json.asObject ++ Json.obj(
                "validate" -> !service.detectApiKeySooner,
                "pass_with_user" -> !service.strictlyPrivate
              ))
            )
          }
          .applyOnIf(service.gzip.enabled) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[GzipResponseCompressor],
              exclude = service.gzip.excludedPatterns,
              config = PluginInstanceConfig(service.gzip.asJson.asObject)
            )
          }
          .applyOnIf(service.canary.enabled) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[CanaryMode],
              config = PluginInstanceConfig(service.canary.toJson.asObject)
            )
          }
          .applyOnIf(service.chaosConfig.enabled) { seq =>
            seq :+ PluginInstance(
              plugin = pluginId[SnowMonkeyChaos],
              config = PluginInstanceConfig(service.chaosConfig.asJson.asObject)
            )
          }
          .applyOnIf(service.tcpUdpTunneling) { seq =>
            val udp = service.targets.exists(_.scheme.toLowerCase.contains("udp://"))
            if (udp) {
              seq :+ PluginInstance(
                plugin = pluginId[UdpTunnel],
                config = PluginInstanceConfig(Json.obj())
              )
            } else {
              seq :+ PluginInstance(
                plugin = pluginId[TcpTunnel],
                config = PluginInstanceConfig(Json.obj())
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
                plugin.pluginType match {
                  case PluginType.AppType =>             PluginInstance(plugin = pluginId[RequestTransformerWrapper], exclude = service.plugins.excluded, config = PluginInstanceConfig(Json.obj("plugin" -> ref))).some
                  case PluginType.TransformerType =>     PluginInstance(plugin = pluginId[RequestTransformerWrapper], exclude = service.plugins.excluded, config = PluginInstanceConfig(Json.obj("plugin" -> ref))).some
                  case PluginType.AccessValidatorType => PluginInstance(plugin = pluginId[AccessValidatorWrapper], exclude = service.plugins.excluded, config = PluginInstanceConfig(Json.obj("plugin" -> ref))).some
                  case PluginType.PreRoutingType =>      PluginInstance(plugin = pluginId[PreRoutingWrapper], exclude = service.plugins.excluded, config = PluginInstanceConfig(Json.obj("plugin" -> ref))).some
                  case PluginType.RequestSinkType =>     PluginInstance(plugin = pluginId[RequestSinkWrapper], exclude = service.plugins.excluded, config = PluginInstanceConfig(Json.obj("plugin" -> ref))).some
                  case PluginType.EventListenerType =>   None
                  case PluginType.JobType =>             None
                  case PluginType.DataExporterType =>    None
                  case PluginType.RequestHandlerType =>  None
                  case PluginType.CompositeType =>       PluginInstance(plugin = pluginId[CompositeWrapper], exclude = service.plugins.excluded, config = PluginInstanceConfig(Json.obj("plugin" -> ref))).some
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

trait RouteDataStore extends BasicStore[Route]

class KvRouteDataStore(redisCli: RedisLike, _env: Env)
  extends RouteDataStore
    with RedisLikeStore[Route] {
  override def redisLike(implicit env: Env): RedisLike = redisCli
  override def fmt: Format[Route]               = Route.fmt
  override def key(id: String): Key             = Key.Empty / _env.storageRoot / "routes" / id
  override def extractId(value: Route): String  = value.id
}
