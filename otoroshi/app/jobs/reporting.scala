package otoroshi.jobs

import org.joda.time.DateTime
import otoroshi.cluster.{ClusterMode, StatsView}
import otoroshi.env.Env
import otoroshi.jobs.newengine.NewEngine
import otoroshi.next.models.NgTlsConfig
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script._
import otoroshi.security.IdGenerator
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits._
import play.api.libs.json._
import play.api.libs.ws.{DefaultWSProxyServer, WSProxyServer}
import play.api.{Configuration, Logger}

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class AnonymousReportingJobConfig(enabled: Boolean, redirect: Boolean, url: String, timeout: Duration, proxy: Option[WSProxyServer], tlsConfig: NgTlsConfig)

object AnonymousReportingJobConfig {
  val default = AnonymousReportingJobConfig(
    enabled = true,
    redirect = false,
    url = "https://reporting.otoroshi.io/ingest",
    timeout = 60.seconds,
    proxy = None,
    tlsConfig = NgTlsConfig.default,
  )
  def fromEnv(env: Env): AnonymousReportingJobConfig = {
    val configuration = env.configuration.getOptionalWithFileSupport[Configuration]("otoroshi.anonymous-reporting").getOrElse(Configuration.empty)
    AnonymousReportingJobConfig(
      enabled = configuration.getOptionalWithFileSupport[Boolean]("enabled").getOrElse(default.enabled),
      redirect = configuration.getOptionalWithFileSupport[Boolean]("redirect").getOrElse(default.redirect),
      url = configuration.getOptionalWithFileSupport[String]("url").getOrElse(default.url),
      timeout = configuration.getOptionalWithFileSupport[Long]("timeout").map(v => FiniteDuration(v, TimeUnit.MILLISECONDS)).getOrElse(default.timeout),
      tlsConfig = NgTlsConfig.fromLegacy(MtlsConfig(
        certs = configuration.getOptionalWithFileSupport[Seq[String]]("tls.certs").getOrElse(Seq.empty),
        trustedCerts = configuration.getOptionalWithFileSupport[Seq[String]]("tls.trustedCerts").getOrElse(Seq.empty),
        loose = configuration.getOptionalWithFileSupport[Boolean]("tls.loose").getOrElse(false),
        trustAll = configuration.getOptionalWithFileSupport[Boolean]("tls.trustAll").getOrElse(false),
        mtls = configuration.getOptionalWithFileSupport[Boolean]("tls.enabled").getOrElse(false)
      )),
      proxy = configuration
        .getOptionalWithFileSupport[Boolean]("proxy.enabled")
        .filter(identity)
        .map { _ =>
          DefaultWSProxyServer(
            host = configuration.getOptionalWithFileSupport[String]("proxy.host").getOrElse("localhost"),
            port = configuration.getOptionalWithFileSupport[Int]("proxy.port").getOrElse(1055),
            principal = configuration.getOptionalWithFileSupport[String]("proxy.principal"),
            password = configuration.getOptionalWithFileSupport[String]("proxy.password"),
            ntlmDomain = configuration.getOptionalWithFileSupport[String]("proxy.ntlmDomain"),
            encoding = configuration.getOptionalWithFileSupport[String]("proxy.encoding"),
            nonProxyHosts = None
          )
        },
    )
  }
}

class AnonymousReportingJob extends Job {

  private val logger = Logger("otoroshi-jobs-anonymous-reporting")

  private val showLog = new AtomicBoolean(true)

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.core.jobs.AnonymousReportingJob")

  override def name: String = "Otoroshi anonymous reporting"

  override def defaultConfig: Option[JsObject] = None

  override def description: Option[String] =
    s"""This job will send anonymous Otoroshi usage metrics to the Otoroshi teams in order to define the future of the product more accurately.
       |This job may also capture your current operating system name/version and your current jvm name/version.
       |No personal or sensible data are sent here, your otoroshi configuration is still safe.
       |you can check what is sent on our github repository (https://github.com/MAIF/otoroshi/blob/master/otoroshi/app/jobs/reporting.scala).
       |Of course you can disable this job from config. file, config. env. variables and danger zone.
       |""".stripMargin.some

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiCluster

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 30.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.hour.some

  private def displayLog(): Unit = {
    logger.info("anonymous reporting is ENABLED. If you want to disable it, you can do it")
    logger.info("  from the 'Danger zone'")
    logger.info("  with the 'otoroshi.anonymous-reporting.enabled = false' config.")
    logger.info("  with the 'OTOROSHI_ANONYMOUS_REPORTING_ENABLED=false' env. variable")
  }

  private def avgDouble(value: Double, extractor: StatsView => Double, stats: Seq[StatsView]): Double = {
    (if (value == Double.NaN || value == Double.NegativeInfinity || value == Double.PositiveInfinity) {
      0.0
    } else {
      stats.map(extractor).:+(value).fold(0.0)(_ + _) / (stats.size + 1)
    }).applyOn {
      case Double.NaN => 0.0
      case Double.NegativeInfinity => 0.0
      case Double.PositiveInfinity => 0.0
      case v if v.toString == "NaN" => 0.0
      case v if v.toString == "Infinity" => 0.0
      case v => v
    }
  }

  private def sumDouble(value: Double, extractor: StatsView => Double, stats: Seq[StatsView]): Double = {
    if (value == Double.NaN || value == Double.NegativeInfinity || value == Double.PositiveInfinity) {
      0.0
    } else {
      stats.map(extractor).:+(value).fold(0.0)(_ + _)
    }.applyOn {
      case Double.NaN => 0.0
      case Double.NegativeInfinity => 0.0
      case Double.PositiveInfinity => 0.0
      case v if v.toString == "NaN" => 0.0
      case v if v.toString == "Infinity" => 0.0
      case v => v
    }
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    // TODO: add doc
    // TODO: add flag in the danger zone
    val globalConfig = env.datastores.globalConfigDataStore.latest()
    val config = AnonymousReportingJobConfig.fromEnv(env)
    if (config.enabled && globalConfig.anonymousReporting) {
      if (showLog.compareAndSet(true, false)) {
        displayLog()
      }
      (for {
        members <- env.datastores.clusterStateDataStore.getMembers()
        calls <- env.datastores.serviceDescriptorDataStore.globalCalls()
        dataIn <- env.datastores.serviceDescriptorDataStore.globalDataIn()
        dataOut <- env.datastores.serviceDescriptorDataStore.globalDataOut()
        rate <- env.datastores.serviceDescriptorDataStore.globalCallsPerSec()
        duration <- env.datastores.serviceDescriptorDataStore.globalCallsDuration()
        overhead <- env.datastores.serviceDescriptorDataStore.globalCallsOverhead()
        dataInRate <- env.datastores.serviceDescriptorDataStore.dataInPerSecFor("global")
        dataOutRate <- env.datastores.serviceDescriptorDataStore.dataOutPerSecFor("global")
        concurrentHandledRequests <- env.datastores.requestsDataStore.asyncGetHandledRequests()
      } yield {
        val (usesNew, usesNewFull) = NewEngine.enabledRawFromConfig(globalConfig, env)
        val membersStats = members.map(_.statsView)
        val now = System.currentTimeMillis()
        val routePlugins: Seq[String] = env.proxyState.allRoutes().flatMap { route =>
          route.plugins.slots.map(slot => slot.plugin)
        }
        val scriptPlugins: Seq[String] = if (globalConfig.scripts.enabled) Seq(
          globalConfig.scripts.transformersRefs,
          globalConfig.scripts.validatorRefs,
          globalConfig.scripts.preRouteRefs,
          globalConfig.scripts.sinkRefs,
          globalConfig.scripts.jobRefs,
        ).flatten else Seq.empty
        val pluginsPlugins = if (globalConfig.plugins.enabled) globalConfig.plugins.refs else Seq.empty
        val plugins = routePlugins ++ scriptPlugins ++ pluginsPlugins
        val counting = plugins.groupBy(identity).mapValues(v => JsNumber(v.size))
        Json.obj(
          "@timestamp" ->  play.api.libs.json.JodaWrites.JodaDateTimeNumberWrites.writes(DateTime.now()),
          "@id" -> IdGenerator.uuid,
          "otoroshi_cluster_id" -> globalConfig.otoroshiId,
          "otoroshi_version" -> env.otoroshiVersion,
          "java_version" -> env.theJavaVersion.json,
          "os" -> env.os.json,
          "datastore" -> env.datastoreKind,
          "env" -> env.env,
          "features" -> Json.obj(
            "snow_monkey" -> globalConfig.snowMonkeyConfig.enabled,
            "clever_cloud" -> globalConfig.cleverSettings.isDefined,
            "kubernetes" -> JsBoolean(
              (globalConfig.plugins.enabled && globalConfig.plugins.refs.exists(l => l.toLowerCase().contains("kube"))) ||
              (globalConfig.scripts.enabled && globalConfig.scripts.jobRefs.exists(l => l.toLowerCase().contains("kube")))
            ),
            "elastic_read" -> globalConfig.elasticReadsConfig.isDefined,
            "lets_encrypt" -> globalConfig.letsEncryptSettings.enabled,
            "auto_certs" -> globalConfig.autoCert.enabled,
            "wasm_manager" -> globalConfig.wasmManagerSettings.isDefined,
            "backoffice_login" -> globalConfig.backOfficeAuthRef.isDefined,
          ),
          "stats" -> Json.obj(
            "calls" -> calls,
            "data_in" -> dataIn,
            "data_out" -> dataOut,
            "rate" -> sumDouble(rate, _.rate, membersStats),
            "duration" -> avgDouble(duration, _.duration, membersStats),
            "overhead" -> avgDouble(overhead, _.overhead, membersStats),
            "data_in_rate" -> sumDouble(dataInRate, _.dataInRate, membersStats),
            "data_out_rate" -> sumDouble(dataOutRate, _.dataOutRate, membersStats),
            "concurrent_requests" -> sumDouble(
              concurrentHandledRequests.toDouble,
              _.concurrentHandledRequests.toDouble,
              membersStats
            ).toLong
          ),
          "engine" -> Json.obj(
            "uses_new" -> usesNew,
            "uses_new_full" -> usesNewFull,
          ),
          "cluster" -> Json.obj(
            "mode" -> env.clusterConfig.mode.name,
            "all_nodes" -> members.size,
            "alive_nodes" -> members.count(m => (m.lastSeen.toDate.getTime + m.timeout.toMillis) > now),
            "leaders_count" -> members.count(mv => mv.memberType == ClusterMode.Leader),
            "workers_count" -> members.count(mv => mv.memberType == ClusterMode.Worker),
            "nodes" -> JsArray(members.map(_.json).map { json =>
              Json.obj(
                "id" -> json.select("id").asValue,
                "name" -> json.select("name").asValue,
                "os" -> json.select("os").asValue,
                "java_version" -> json.select("javaVersion").asValue,
                "version" -> json.select("version").asValue,
                "type" -> json.select("type").asValue,
                "cpu_usage" -> json.select("stats").select("cpu_usage").asValue,
                "load_average" -> json.select("stats").select("load_average").asValue,
                "heap_used" -> json.select("stats").select("heap_used").asValue,
                "heap_size" -> json.select("stats").select("heap_size").asValue,
                "relay" -> JsBoolean(json.select("relay").select("enabled").asOpt[Boolean].getOrElse(false)),
                "tunnels" -> JsNumber(BigDecimal(json.select("tunnels").asOpt[JsArray].map(_.value.size).getOrElse(0))),
                // "live_threads" -> json.select("stats").select("live_threads").asValue,
                // "live_peak_threads" -> json.select("stats").select("live_peak_threads").asValue,
                // "daemon_threads" -> json.select("stats").select("daemon_threads").asValue,
                // "location" -> json.select("location").asValue,
                // "http_port" -> json.select("httpPort").asValue,
                // "https_port" -> json.select("httpsPort").asValue,
                // "internal_http_port" -> json.select("internalHttpPort").asValue,
                // "internal_https_port" -> json.select("internalHttpsPort").asValue,
              )
            }),
          ),
          "entities" -> Json.obj(
            "scripts" -> Json.obj(
              "count" -> env.proxyState.allScripts().size,
              "by_kind" -> env.proxyState.allScripts().foldLeft(Json.obj()) {
                case (obj, script) =>
                  val key = script.`type`.name
                  obj ++ Json.obj(key -> (obj.select(key).asOpt[Int].getOrElse(0) + 1))
              }
            ),
            "routes" -> Json.obj(
              "count" -> env.proxyState.allRawRoutes().size,
              "plugins" -> Json.obj(
                "min" -> env.proxyState.allRawRoutes().map(_.plugins.slots.size).min,
                "max" -> env.proxyState.allRawRoutes().map(_.plugins.slots.size).max,
                "avg" -> env.proxyState.allRawRoutes().avgBy(_.plugins.slots.size),
              )
            ),
            "router_routes" -> Json.obj(
              "count" -> env.proxyState.allRoutes().size,
              "plugins" -> Json.obj(
                "min" -> env.proxyState.allRoutes().map(_.plugins.slots.size).min,
                "max" -> env.proxyState.allRoutes().map(_.plugins.slots.size).max,
                "avg" -> env.proxyState.allRoutes().avgBy(_.plugins.slots.size),
              )
            ),
            "route_compositions" -> Json.obj(
              "count" -> env.proxyState.allRouteCompositions().size,
              "plugins" -> Json.obj(
                "min" -> env.proxyState.allRouteCompositions().map(v => v.plugins.slots.size + v.routes.foldLeft(0)((a, b) => a + b.plugins.slots.size)).min,
                "max" -> env.proxyState.allRouteCompositions().map(v => v.plugins.slots.size + v.routes.foldLeft(0)((a, b) => a + b.plugins.slots.size)).max,
                "avg" -> env.proxyState.allRouteCompositions().avgBy(v => v.plugins.slots.size + v.routes.foldLeft(0)((a, b) => a + b.plugins.slots.size)),
              ),
              "by_kind" -> env.proxyState.allRouteCompositions().foldLeft(Json.obj()) {
                case (obj, rc) =>
                  val localPlugins = rc.routes.exists(_.plugins.slots.nonEmpty)
                  val overridesPlugins = rc.routes.exists(v => v.plugins.slots.nonEmpty && v.overridePlugins)
                  val key = if (localPlugins) {
                    if (overridesPlugins) "local_with_override" else "local"
                  } else {
                    "global"
                  }
                  obj ++ Json.obj(
                    key -> (obj.select(key).asOpt[Int].getOrElse(0) + 1)
                  )
              }
            ),
            "apikeys" -> Json.obj(
              "count" -> env.proxyState.allApikeys().size,
              "by_kind" -> Json.obj(
                "disabled" -> env.proxyState.allApikeys().filterNot(_.enabled).size,
                "with_rotation" -> env.proxyState.allApikeys().count(_.rotation.enabled),
                "with_read_only" -> env.proxyState.allApikeys().count(_.readOnly),
                "with_client_id_only" -> env.proxyState.allApikeys().count(_.allowClientIdOnly),
                "with_constrained_services" -> env.proxyState.allApikeys().count(_.constrainedServicesOnly),
                "with_meta" -> env.proxyState.allApikeys().count(_.metadata.nonEmpty),
                "with_tags" -> env.proxyState.allApikeys().count(_.tags.nonEmpty),
              ),
              "authorized_on" -> Json.obj(
                "min" -> env.proxyState.allApikeys().map(_.authorizedEntities.size).min,
                "max" -> env.proxyState.allApikeys().map(_.authorizedEntities.size).max,
                "avg" -> env.proxyState.allApikeys().avgBy(_.authorizedEntities.size),
              )
            ),
            "jwt_verifiers" -> Json.obj(
              "count" -> env.proxyState.allJwtVerifiers().size,
              "by_strategy" -> env.proxyState.allJwtVerifiers().foldLeft(Json.obj()) {
                case (obj, verifier) =>
                  val key = verifier.strategy.name
                  obj ++ Json.obj(key -> (obj.select(key).asOpt[Int].getOrElse(0) + 1))
              },
              "by_alg" -> env.proxyState.allJwtVerifiers().foldLeft(Json.obj()) {
                case (obj, verifier) =>
                  val key = verifier.algoSettings.name
                  obj ++ Json.obj(key -> (obj.select(key).asOpt[Int].getOrElse(0) + 1))
              }
            ),
            "certificates" -> Json.obj(
              "count" -> env.proxyState.allCertificates().size,
              "by_kind" -> env.proxyState.allCertificates().foldLeft(Json.obj()) {
                case (obj, certificate) =>
                  obj ++ Json.obj()
                    .applyOnIf(certificate.autoRenew) { o => o ++ Json.obj("auto_renew" -> (obj.select("auto_renew").asOpt[Int].getOrElse(0) + 1)) }
                    .applyOnIf(certificate.client) { o => o ++ Json.obj("client" -> (obj.select("client").asOpt[Int].getOrElse(0) + 1)) }
                    .applyOnIf(certificate.keypair) { o => o ++ Json.obj("keypair" -> (obj.select("keypair").asOpt[Int].getOrElse(0) + 1)) }
                    .applyOnIf(certificate.exposed) { o => o ++ Json.obj("exposed" -> (obj.select("exposed").asOpt[Int].getOrElse(0) + 1)) }
                    .applyOnIf(certificate.revoked) { o => o ++ Json.obj("revoked" -> (obj.select("revoked").asOpt[Int].getOrElse(0) + 1)) }
              }
            ),
            "auth_modules" -> Json.obj(
              "count" -> env.proxyState.allAuthModules().size,
              "by_kind" -> env.proxyState.allAuthModules().foldLeft(Json.obj()) {
                case (obj, module) =>
                  val key = module.`type`
                  obj ++ Json.obj(key -> (obj.select(key).asOpt[Int].getOrElse(0) + 1))
              }
            ),
            "service_descriptors" -> Json.obj(
              "count" -> env.proxyState.allServices().size,
              "plugins" -> Json.obj(
                "old" -> env.proxyState.allServices().filter { v =>
                  v.preRouting.enabled || v.accessValidator.enabled || v.transformerRefs.nonEmpty
                }.count(v => v.preRouting.refs.nonEmpty || v.accessValidator.refs.nonEmpty || v.transformerRefs.nonEmpty),
                "new" -> env.proxyState.allServices().filter(_.plugins.enabled).count(_.plugins.refs.size > 0)
              ),
              "by_kind" -> Json.obj(
                "disabled" -> env.proxyState.allServices().count(!_.enabled),
                "fault_injection" -> env.proxyState.allServices().count(_.chaosConfig.enabled),
                "health_check" -> env.proxyState.allServices().count(_.healthCheck.enabled),
                "gzip" ->  env.proxyState.allServices().count(_.gzip.enabled),
                "jwt" ->  env.proxyState.allServices().count(_.jwtVerifier.enabled),
                "cors" ->  env.proxyState.allServices().count(_.cors.enabled),
                "auth" -> env.proxyState.allServices().count(_.privateApp),
                "protocol" ->  env.proxyState.allServices().count(_.enforceSecureCommunication),
                "restrictions" -> env.proxyState.allServices().count(_.restrictions.enabled),
              )
            ),
            "teams" -> Json.obj("count" -> env.proxyState.allTeams().size),
            "tenants" -> Json.obj("count" -> env.proxyState.allTenants().size),
            "service_groups" -> Json.obj("count" -> env.proxyState.allServiceGroups().size),
            //"error_templates" -> Json.obj("count" -> env.proxyState.allErrorTemplates().size),
            "data_exporters" -> Json.obj(
              "count" -> env.proxyState.allDataExporters().size,
              "by_kind" -> env.proxyState.allDataExporters().foldLeft(Json.obj()) {
                case (obj, exporter) =>
                  val key = exporter.typ.name
                  obj ++ Json.obj(key -> (obj.select(key).asOpt[Int].getOrElse(0) + 1))
              }
            ),
            "otoroshi_admins" -> Json.obj(
              "count" -> env.proxyState.allOtoroshiAdmins().size,
              "by_kind" -> env.proxyState.allOtoroshiAdmins().foldLeft(Json.obj()) {
                case (obj, admin) =>
                  val key = admin.typ.name.toLowerCase()
                  obj ++ Json.obj(key -> (obj.select(key).asOpt[Int].getOrElse(0) + 1))
              }
            ),
            "backoffice_sessions" -> Json.obj(
              "count" -> env.proxyState.allBackofficeSessions().size,
              "by_kind" -> env.proxyState.allBackofficeSessions().foldLeft(Json.obj()) {
                case (obj, session) =>
                  val key = env.proxyState.authModule(session.authConfigId).map(_.`type`).getOrElse(if (session.simpleLogin) "simple" else session.authConfigId)
                  obj ++ Json.obj(key -> (obj.select(key).asOpt[Int].getOrElse(0) + 1))
              }
            ),
            "private_apps_sessions" -> Json.obj(
              "count" -> env.proxyState.allPrivateAppsSessions().size,
              "by_kind" -> env.proxyState.allPrivateAppsSessions().foldLeft(Json.obj()) {
                case (obj, session) =>
                  val key = env.proxyState.authModule(session.authConfigId).map(_.`type`).getOrElse("unknown")
                  obj ++ Json.obj(key -> (obj.select(key).asOpt[Int].getOrElse(0) + 1))
              }
            ),
            "tcp_services" -> Json.obj("count" -> env.proxyState.allTcpServices().size),
          ),
          "plugins_usage" -> counting,
          // "metrics" -> env.metrics.jsonRawExport(None),
        )
      }).flatMap { report =>
        if (env.isDev) logger.debug(report.prettify)
        val req = if (config.tlsConfig.enabled) {
          env.MtlsWs.url(config.url, config.tlsConfig.legacy)
        } else {
          env.Ws.url(config.url)
        }
        req
          .withFollowRedirects(config.redirect)
          .withRequestTimeout(config.timeout)
          .applyOnWithOpt(config.proxy) {
            case (r, proxy) => r.withProxyServer(proxy)
          }
          .post(report)
          .map { resp =>
            if (resp.status != 200 && resp.status != 201 && resp.status != 204) {
              logger.error(s"error while sending anonymous reports: ${resp.status} - ${resp.body}")
            }
          }
          .recover {
            case e: Throwable =>
              logger.error("error while sending anonymous reports", e)
              ()
          }
      }
    } else {
      ().vfuture
    }
  }.recover {
    case e: Throwable =>
      logger.error("error job anonymous reports", e)
      ()
  }
}
