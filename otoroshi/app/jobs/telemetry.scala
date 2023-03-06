package otoroshi.jobs

import org.joda.time.DateTime
import otoroshi.cluster.{ClusterMode, StatsView}
import otoroshi.env.Env
import otoroshi.jobs.newengine.NewEngine
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script.{Job, JobContext, JobId, JobInstantiation, JobKind, JobStarting, JobVisibility}
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString, Json}

import java.io.File
import java.nio.file.Files
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class AnonymousTelemetryJob extends Job {

  private val logger = Logger("otoroshi-jobs-anonymous-telemetry")

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.core.jobs.AnonymousTelemetryJob")

  override def name: String = "Otoroshi anonymous telemetry job"

  override def defaultConfig: Option[JsObject] = None

  override def description: Option[String] =
    s"""This job will send anonymous otoroshi usage metrics to the otoroshi teams in order to define the future of the product more accurately.
       |No personnal or sensible data are sent here, you can check what is sent on our github repository.
       |Of course you can disable this job from config. file, config. env. variables and danger zone.
       |""".stripMargin.some

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiCluster

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 30.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.hour.some

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
    // TODO: a message a boot time to explain what is going on and how to disable it
    // TODO: add doc
    // TODO: add flag in the danger zone
    val globalConfig = env.datastores.globalConfigDataStore.latest()
    if (env.anonymousTelemetry && globalConfig.anonymousTelemetry) {
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
          "java_version" -> env.javaVersion,
          "os_name" -> JsString(Option(System.getProperty("os.name")).getOrElse("--")),
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
          ),
          "stats" -> Json.obj(
            "calls" -> calls,
            "data_in" -> dataIn,
            "data_iut" -> dataOut,
            "rate" -> sumDouble(rate, _.rate, membersStats),
            "duration" -> avgDouble(duration, _.duration, membersStats),
            "overhead" -> avgDouble(overhead, _.overhead, membersStats),
            "data_in_rate" -> sumDouble(dataInRate, _.dataInRate, membersStats),
            "data_out_rate" -> sumDouble(dataOutRate, _.dataOutRate, membersStats),
            "concurrent_handled_requests" -> sumDouble(
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
              json.asObject ++ Json.obj(
                "relay" -> JsBoolean(json.select("relay").select("enabled").asOpt[Boolean].getOrElse(false)),
                "tunnels" -> json.select("tunnels").asOpt[JsArray].getOrElse(Json.arr()).value.size
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
            "routes" -> Json.obj("count" -> env.proxyState.allRawRoutes().size),
            "router_routes" -> Json.obj("count" -> env.proxyState.allRoutes().size),
            "route_compositions" -> Json.obj("count" -> env.proxyState.allRouteCompositions().size),
            "apikeys" -> Json.obj("count" -> env.proxyState.allApikeys().size),
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
            "service_descriptors" -> Json.obj("count" -> env.proxyState.allServices().size),
            "teams" -> Json.obj("count" -> env.proxyState.allTeams().size),
            "tenants" -> Json.obj("count" -> env.proxyState.allTenants().size),
            "service_groups" -> Json.obj("count" -> env.proxyState.allServiceGroups().size),
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
        //Files.writeString(new File("./report.json").toPath, report.prettify)
        if (env.isDev) report.prettify.debugPrintln
        //report.stringify.byteString.size.debugPrintln
        env.Ws
          .url("https://telemetry.otoroshi.io/ingest")
          .withFollowRedirects(false)
          .withRequestTimeout(60.seconds)
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
