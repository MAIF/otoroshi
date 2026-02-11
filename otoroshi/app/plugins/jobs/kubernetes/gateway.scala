package otoroshi.plugins.jobs.kubernetes

import akka.stream.scaladsl.{Sink, Source}
import org.joda.time.DateTime
import otoroshi.cluster.ClusterMode
import otoroshi.env.Env
import otoroshi.next.models.NgRoute
import otoroshi.next.plugins.api.NgPluginCategory
import otoroshi.script._
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

// ─────────────────────────────────────────────────────────────────────────────
// TODO — Remaining work for full Gateway API compliance
//
// STATUS:
// - [ ] Conflict detection on listener hostname/port collisions
//
// ADVANCED:
// - [ ] Support otoroshi specific settings through annotations (mostly flags and additional plugins)
// - [ ] Conformance test suite (gateway-api conformance tests)
// ─────────────────────────────────────────────────────────────────────────────

class KubernetesGatewayApiControllerJob extends Job {

  private val logger           = Logger("otoroshi-plugins-kubernetes-gateway-api-job")
  private val stopCommand      = new AtomicBoolean(false)
  private val watchCommand     = new AtomicBoolean(false)
  private val lastWatchStopped = new AtomicBoolean(true)
  private val lastWatchSync    = new AtomicLong(0L)

  override def categories: Seq[NgPluginCategory] = Seq(NgPluginCategory.Integrations)

  override def uniqueId: JobId = JobId("io.otoroshi.plugins.jobs.kubernetes.KubernetesGatewayApiControllerJob")

  override def name: String = "Kubernetes Gateway API Controller"

  override def defaultConfig: Option[JsObject] = KubernetesConfig.defaultConfig.some

  override def configFlow: Seq[String] = KubernetesConfig.configFlow

  override def configSchema: Option[JsObject] = KubernetesConfig.configSchema

  override def description: Option[String] =
    Some(
      s"""This plugin enables Kubernetes Gateway API support.
         |It watches GatewayClass, Gateway, HTTPRoute, and GRPCRoute resources
         |and translates them into Otoroshi NgRoute entities.
         |
         |```json
         |${Json.prettyPrint(defaultConfig.get)}
         |```
      """.stripMargin
    )

  override def jobVisibility: JobVisibility = JobVisibility.UserLand

  override def kind: JobKind = JobKind.ScheduledEvery

  override def starting: JobStarting = JobStarting.FromConfiguration

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation = {
    Option(env)
      .flatMap(env => env.datastores.globalConfigDataStore.latestSafe.map(c => (env, c)))
      .map { case (env, _) =>
        (env, KubernetesConfig.theConfig(ctx)(env, env.otoroshiExecutionContext))
      }
      .map { case (env, cfg) =>
        env.clusterConfig.mode match {
          case ClusterMode.Off if !cfg.kubeLeader => JobInstantiation.OneInstancePerOtoroshiCluster
          case ClusterMode.Off if cfg.kubeLeader  => JobInstantiation.OneInstancePerOtoroshiInstance
          case _ if cfg.kubeLeader                => JobInstantiation.OneInstancePerOtoroshiLeaderInstance
          case _                                  => JobInstantiation.OneInstancePerOtoroshiCluster
        }
      }
      .getOrElse(JobInstantiation.OneInstancePerOtoroshiCluster)
  }

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = {
    Try(KubernetesConfig.theConfig(ctx)(env, env.otoroshiExecutionContext)) match {
      case Success(conf) => conf.gatewayApiSyncIntervalSeconds.seconds.some
      case Failure(_)    => 60.seconds.some
    }
  }

  override def predicate(ctx: JobContext, env: Env): Option[Boolean] = {
    Try(KubernetesConfig.theConfig(ctx)(env, env.otoroshiExecutionContext)) match {
      case Failure(_)    => Some(false)
      case Success(conf) => if (conf.gatewayApi) None else Some(false)
    }
  }

  override def jobStart(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    logger.info("Starting Kubernetes Gateway API controller job")
    stopCommand.set(false)
    lastWatchStopped.set(true)
    watchCommand.set(false)
    val conf = KubernetesConfig.theConfig(ctx)
    handleWatch(conf, ctx)
    ().future
  }

  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    logger.info("Stopping Kubernetes Gateway API controller job")
    stopCommand.set(true)
    watchCommand.set(false)
    lastWatchStopped.set(true)
    ().future
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    Try(KubernetesConfig.theConfig(ctx)) match {
      case Failure(e) =>
        logger.error(s"Failed to read KubernetesConfig: ${e.getMessage}")
        ().future
      case Success(conf) if conf.gatewayApi =>
        handleWatch(conf, ctx)
        KubernetesGatewayApiJob.syncGatewayApi(conf, ctx.attrs, !stopCommand.get())
      case _ =>
        ().future
    }
  }
  
  def getNamespaces(client: KubernetesClient, conf: KubernetesConfig)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Seq[String]] = {
    if (conf.namespacesLabels.isEmpty) {
      conf.namespaces.future
    } else {
      client.fetchNamespacesAndFilterLabels().map { namespaces =>
        namespaces.map(_.name)
      }
    }
  }

  def handleWatch(config: KubernetesConfig, ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Unit = {
    if (config.watch && !watchCommand.get() && lastWatchStopped.get()) {
      logger.info("starting gateway api watch ...")
      implicit val mat = env.otoroshiMaterializer
      watchCommand.set(true)
      lastWatchStopped.set(false)
      env.otoroshiScheduler.scheduleOnce(5.minutes) {
        logger.info("trigger stop gateway api watch after 5 min.")
        watchCommand.set(false)
        lastWatchStopped.set(true)
      }
      val conf         = KubernetesConfig.theConfig(ctx)
      val client       = new KubernetesClient(conf, env)
      val source       = Source
        .future(getNamespaces(client, conf))
        .flatMapConcat { nses =>
          client.watchGatewayApiResources(nses, conf.watchTimeoutSeconds, !watchCommand.get())
        }
      source
        .takeWhile(_ => !watchCommand.get())
        .filterNot(_.isEmpty)
        .alsoTo(Sink.onComplete { case _ =>
          lastWatchStopped.set(true)
        })
        .runWith(Sink.foreach { group =>
          val now = System.currentTimeMillis()
          if ((lastWatchSync.get() + (conf.watchGracePeriodSeconds * 1000L)) < now) {
            if (logger.isDebugEnabled) logger.debug(s"sync triggered by a group of ${group.size} events")
            KubernetesGatewayApiJob.syncGatewayApi(conf, ctx.attrs, !stopCommand.get())
          }
        })
    } else if (!config.watch) {
      logger.info("stopping gateway api watch")
      watchCommand.set(false)
    } else {
      logger.info(s"gateway api watching already ...")
    }
  }
}

object KubernetesGatewayApiJob {

  private val logger  = Logger("otoroshi-plugins-kubernetes-gateway-api-sync")
  private val running = new AtomicBoolean(false)

  val PROVIDER = "kubernetes-gateway-api"

  /**
   * Main synchronization method.
   *
   * Flow:
   * 1. Fetch all Gateway API resources from the cluster
   * 2. Fetch associated k8s resources (Services, Endpoints, Secrets)
   * 3. Fetch existing Otoroshi managed routes
   * 4. Reconcile GatewayClass -> update status
   * 5. Resolve TLS certificates from HTTPS listener certificateRefs
   * 6. Reconcile Gateway -> validate listeners + cert refs, update status
   * 7. Reconcile HTTPRoute -> convert to NgRoute, save
   * 8. Reconcile GRPCRoute -> convert to NgRoute (HTTP/2 targets), save
   * 9. Delete orphaned Otoroshi routes
   */
  def syncGatewayApi(
      conf: KubernetesConfig,
      attrs: TypedMap,
      jobRunning: => Boolean
  )(implicit env: Env, ec: ExecutionContext): Future[Unit] = {

    if (!jobRunning) { running.set(false) }
    if (jobRunning && running.compareAndSet(false, true)) {
      logger.info("Starting Gateway API sync")
      val client = new KubernetesClient(conf, env)

      val result = for {
        // ─── Phase 1: Fetch ──────────────────────────────────────
        gatewayClasses  <- client.fetchGatewayClasses()
        gateways        <- client.fetchGateways()
        httpRoutes      <- client.fetchHTTPRoutes()
        grpcRoutes          <- client.fetchGRPCRoutes()
        referenceGrants     <- client.fetchReferenceGrants()
        backendTLSPolicies  <- client.fetchBackendTLSPolicies()
        plugins             <- client.fetchPlugins()
        namespaces          <- client.fetchNamespacesAndFilterLabels()
        services            <- client.fetchServices()
        endpoints           <- client.fetchEndpoints()
        endpointSlices      <- client.fetchEndpointSlices()

        // Fetch existing Otoroshi routes managed by this provider
        existingRoutes <- if (conf.useProxyState) env.proxyState.allRoutes().vfuture
                          else env.datastores.routeDataStore.findAll()
        managedRoutes = existingRoutes.filter(
          _.metadata.get("otoroshi-provider").contains(PROVIDER)
        )

        // ─── Phase 2: Reconcile GatewayClasses ──────────────────
        _ <- reconcileGatewayClasses(client, gatewayClasses, conf)

        // ─── Phase 3: Resolve TLS certificates ──────────────────
        resolvedCerts <- resolveGatewayCertificates(client, gateways, gatewayClasses, conf)

        // ─── Phase 4: Reconcile Gateways ────────────────────────
        acceptedGateways <- reconcileGateways(
          client, gateways, gatewayClasses, httpRoutes, grpcRoutes, namespaces, conf, resolvedCerts
        )

        // ─── Phase 4.5: Resolve BackendTLS CA certificates ──────
        resolvedCaCertIds <- resolveBackendTLSCACertificates(client, backendTLSPolicies, conf)

        // ─── Phase 5: Reconcile HTTPRoutes ──────────────────────
        httpGeneratedRoutes <- reconcileHTTPRoutes(
          client, httpRoutes, acceptedGateways, services, endpoints, endpointSlices,
          referenceGrants, backendTLSPolicies, resolvedCaCertIds, plugins, namespaces, conf
        )

        // ─── Phase 6: Reconcile GRPCRoutes ──────────────────────
        grpcGeneratedRoutes <- reconcileGRPCRoutes(
          client, grpcRoutes, acceptedGateways, services, endpoints, endpointSlices,
          referenceGrants, backendTLSPolicies, resolvedCaCertIds, plugins, namespaces, conf
        )

        generatedRoutes = httpGeneratedRoutes ++ grpcGeneratedRoutes

        // ─── Phase 7: Save generated routes ─────────────────────
        _ <- saveGeneratedRoutes(generatedRoutes, managedRoutes)

        // ─── Phase 8: Delete orphaned routes ────────────────────
        _ <- deleteOrphanedRoutes(generatedRoutes, managedRoutes)

        _ = logger.info(s"Gateway API sync done: ${generatedRoutes.size} routes generated " +
          s"(${httpGeneratedRoutes.size} HTTP, ${grpcGeneratedRoutes.size} gRPC)")
      } yield ()

      result.andThen {
        case Failure(e) =>
          logger.error(s"Gateway API sync failed: ${e.getMessage}", e)
          running.set(false)
        case Success(_) =>
          running.set(false)
      }
    } else {
      logger.debug("Gateway API sync already running or stopped, skipping")
      ().future
    }
  }

  // ─── Reconcile GatewayClasses ──────────────────────────────────────────────

  private def reconcileGatewayClasses(
      client: KubernetesClient,
      gatewayClasses: Seq[KubernetesGatewayClass],
      conf: KubernetesConfig
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val ours = gatewayClasses.filter(_.controllerName == conf.gatewayApiControllerName)
    Future
      .sequence(ours.map { gc =>
        val status = Json.obj(
          "conditions" -> Json.arr(
            conditionJson(
              "Accepted",
              "True",
              "Accepted",
              "GatewayClass accepted by Otoroshi",
              gc.raw.select("metadata").select("generation").asOpt[Long]
            )
          )
        )
        client.updateGatewayClassStatus(gc.name, status)
      })
      .map(_ => ())
  }

  // ─── Resolve Gateway TLS Certificates ────────────────────────────────────

  /**
   * Resolve TLS certificateRefs from HTTPS listeners.
   *
   * For each certificateRef, checks if the cert already exists in Otoroshi's
   * cert store (using the kubernetes-certs-import ID convention). If missing,
   * fetches the K8s TLS Secret and imports it via KubernetesCertSyncJob.importCerts.
   *
   * @return set of resolved cert paths "namespace/name"
   */
  private def resolveGatewayCertificates(
      client: KubernetesClient,
      gateways: Seq[KubernetesGateway],
      gatewayClasses: Seq[KubernetesGatewayClass],
      conf: KubernetesConfig
  )(implicit env: Env, ec: ExecutionContext): Future[Set[String]] = {
    val acceptedClassNames = gatewayClasses
      .filter(_.controllerName == conf.gatewayApiControllerName)
      .map(_.name)
      .toSet
    val ourGateways = gateways.filter(gw => acceptedClassNames.contains(gw.gatewayClassName))

    // Collect unique (namespace, name) pairs from HTTPS listener certificateRefs
    val certRefs: Seq[(String, String)] = ourGateways.flatMap { gw =>
      gw.listeners
        .filter(_.protocol == "HTTPS")
        .flatMap { listener =>
          listener.certificateRefs.map { ref =>
            val ns   = (ref \ "namespace").asOpt[String].getOrElse(gw.namespace)
            val name = (ref \ "name").as[String]
            (ns, name)
          }
        }
    }.distinct

    if (certRefs.isEmpty) {
      Future.successful(Set.empty[String])
    } else {
      Future
        .sequence(certRefs.map { case (namespace, name) =>
          val certId = s"kubernetes-certs-import-$namespace-$name".slugifyWithSlash
          val path   = s"$namespace/$name"
          env.datastores.certificatesDataStore.findById(certId).flatMap {
            case Some(_) =>
              // Cert already present in Otoroshi
              Future.successful(Some(path))
            case None =>
              // Try to fetch and import from K8s
              client.fetchSecret(namespace, name).flatMap {
                case Some(secret) if secret.theType == "kubernetes.io/tls" =>
                  val certSecret = secret.cert
                  KubernetesCertSyncJob.importCerts(Seq(certSecret)).map { _ =>
                    logger.info(s"Imported TLS certificate $path for Gateway API listener")
                    Some(path)
                  }
                case Some(secret) =>
                  logger.warn(s"Secret $path is not of type kubernetes.io/tls (got ${secret.theType}), skipping")
                  Future.successful(None)
                case None =>
                  logger.warn(s"TLS Secret $path not found in Kubernetes, cannot resolve certificateRef")
                  Future.successful(None)
              }
          }.recover { case e =>
            logger.error(s"Failed to resolve certificate $path: ${e.getMessage}")
            None
          }
        })
        .map(_.flatten.toSet)
    }
  }

  // ─── Resolve BackendTLS CA Certificates ───────────────────────────────────

  /**
   * Resolve CA certificates referenced by BackendTLSPolicy resources.
   *
   * For each unique caCertificateRef across all policies, checks if the cert
   * already exists in Otoroshi's cert store (using the kubernetes-certs-import
   * ID convention). If missing, fetches the K8s Secret and imports the CA
   * certificate (ca.crt field).
   *
   * @return map of "namespace/secretName" -> Otoroshi cert ID
   */
  private def resolveBackendTLSCACertificates(
      client: KubernetesClient,
      backendTLSPolicies: Seq[KubernetesBackendTLSPolicy],
      conf: KubernetesConfig
  )(implicit env: Env, ec: ExecutionContext): Future[Map[String, String]] = {

    // Collect unique (namespace, name) pairs from all caCertificateRefs
    val certRefs: Seq[(String, String)] = backendTLSPolicies.flatMap { policy =>
      policy.validation.toSeq.flatMap { v =>
        v.caCertificateRefs.map { ref =>
          val ns   = (ref \ "namespace").asOpt[String].getOrElse(policy.namespace)
          val name = (ref \ "name").as[String]
          (ns, name)
        }
      }
    }.distinct

    if (certRefs.isEmpty) {
      Future.successful(Map.empty[String, String])
    } else {
      Future
        .sequence(certRefs.map { case (namespace, name) =>
          val certId = s"kubernetes-certs-import-$namespace-$name".slugifyWithSlash
          val path   = s"$namespace/$name"
          env.datastores.certificatesDataStore.findById(certId).flatMap {
            case Some(_) =>
              // CA cert already present in Otoroshi
              Future.successful(Some(path -> certId))
            case None =>
              // Try to fetch the K8s Secret and import the CA certificate
              client.fetchSecret(namespace, name).flatMap {
                case Some(secret) =>
                  val caCrt = secret.data.get("ca.crt")
                    .orElse(secret.data.get("tls.crt")) // fallback to tls.crt if ca.crt not present
                  caCrt match {
                    case Some(certPem) =>
                      import otoroshi.ssl.Cert
                      val newCert = Cert(
                        id = certId,
                        name = s"K8s BackendTLS CA $namespace/$name",
                        description = s"CA certificate imported from BackendTLSPolicy for $namespace/$name",
                        chain = certPem,
                        privateKey = "",
                        caRef = None,
                        autoRenew = false,
                        client = false,
                        exposed = false,
                        revoked = false
                      ).enrich().copy(
                        ca = true,
                        entityMetadata = Map(
                          "otoroshi-provider"    -> "kubernetes-gateway-api",
                          "kubernetes-name"      -> name,
                          "kubernetes-namespace" -> namespace,
                          "kubernetes-path"      -> path
                        )
                      )
                      newCert.save().map { _ =>
                        logger.info(s"Imported CA certificate $path for BackendTLSPolicy")
                        Some(path -> certId)
                      }
                    case None =>
                      logger.warn(s"Secret $path has no ca.crt or tls.crt data, cannot import CA certificate")
                      Future.successful(None)
                  }
                case None =>
                  logger.warn(s"Secret $path not found in Kubernetes, cannot resolve BackendTLSPolicy caCertificateRef")
                  Future.successful(None)
              }
          }.recover { case e =>
            logger.error(s"Failed to resolve BackendTLS CA certificate $path: ${e.getMessage}")
            None
          }
        })
        .map(_.flatten.toMap)
    }
  }

  // ─── Reconcile Gateways ───────────────────────────────────────────────────

  /**
   * Resolves the addresses to report in Gateway status.
   *
   * Resolution priority:
   * 1. Static addresses from `gatewayApiAddresses` config (if non-empty)
   * 2. Dynamic resolution from the Kubernetes Service identified by
   *    `gatewayApiGatewayServiceName` (or `otoroshiServiceName` fallback):
   *    - LoadBalancer ingress IPs/hostnames from `status.loadBalancer.ingress`
   *    - ClusterIP as last resort
   * 3. Empty array if resolution fails
   */
  private def resolveGatewayAddresses(
      client: KubernetesClient,
      conf: KubernetesConfig
  )(implicit ec: ExecutionContext): Future[JsArray] = {
    if (conf.gatewayApiAddresses.nonEmpty) {
      Future.successful(JsArray(conf.gatewayApiAddresses))
    } else {
      val svcName = conf.gatewayApiGatewayServiceName.getOrElse(conf.otoroshiServiceName)
      val svcNamespace = conf.otoroshiNamespace
      client.fetchService(svcNamespace, svcName).map {
        case None =>
          logger.warn(s"Gateway address resolution: Service $svcNamespace/$svcName not found")
          Json.arr()
        case Some(svc) =>
          val lbIngress = svc.raw.select("status").select("loadBalancer").select("ingress")
            .asOpt[Seq[JsObject]].getOrElse(Seq.empty)
          if (lbIngress.nonEmpty) {
            JsArray(lbIngress.flatMap { ingress =>
              val ip = (ingress \ "ip").asOpt[String]
              val hostname = (ingress \ "hostname").asOpt[String]
              ip.map(v => Json.obj("type" -> "IPAddress", "value" -> v))
                .orElse(hostname.map(v => Json.obj("type" -> "Hostname", "value" -> v)))
            })
          } else {
            // Fallback to clusterIP
            Json.arr(Json.obj("type" -> "IPAddress", "value" -> svc.clusterIP))
          }
      }
    }
  }

  private def reconcileGateways(
      client: KubernetesClient,
      gateways: Seq[KubernetesGateway],
      gatewayClasses: Seq[KubernetesGatewayClass],
      httpRoutes: Seq[KubernetesHTTPRoute],
      grpcRoutes: Seq[KubernetesGRPCRoute],
      namespaces: Seq[KubernetesNamespace],
      conf: KubernetesConfig,
      resolvedCerts: Set[String]
  )(implicit ec: ExecutionContext): Future[Seq[KubernetesGateway]] = {
    val acceptedClassNames = gatewayClasses
      .filter(_.controllerName == conf.gatewayApiControllerName)
      .map(_.name)
      .toSet
    val ourGateways = gateways.filter(gw => acceptedClassNames.contains(gw.gatewayClassName))

    resolveGatewayAddresses(client, conf).flatMap { addresses =>
    Future
      .sequence(ourGateways.map { gw =>
        val listenerStatuses = gw.listeners.map { listener =>
          val portOk = listener.protocol match {
            case "HTTP"  => conf.gatewayApiHttpListenerPort.contains(listener.port)
            case "HTTPS" => conf.gatewayApiHttpsListenerPort.contains(listener.port)
            case _       => false
          }
          val protocolOk = Seq("HTTP", "HTTPS").contains(listener.protocol)

          // Check TLS certificate resolution for HTTPS listeners
          val (refsResolved, refsReason, refsMessage) = if (listener.protocol == "HTTPS" && listener.certificateRefs.nonEmpty) {
            val unresolvedRefs = listener.certificateRefs.filterNot { ref =>
              val ns   = (ref \ "namespace").asOpt[String].getOrElse(gw.namespace)
              val name = (ref \ "name").as[String]
              resolvedCerts.contains(s"$ns/$name")
            }
            if (unresolvedRefs.isEmpty) {
              ("True", "ResolvedRefs", "All references resolved")
            } else {
              val names = unresolvedRefs.map(r => (r \ "name").as[String]).mkString(", ")
              ("False", "InvalidCertificateRef", s"Certificate(s) not found: $names")
            }
          } else {
            ("True", "ResolvedRefs", "References resolved")
          }

          val gwGeneration = gw.raw.select("metadata").select("generation").asOpt[Long]

          val conditions = if (portOk && protocolOk) {
            Json.arr(
              conditionJson("Accepted", "True", "Accepted", "Listener accepted", gwGeneration),
              conditionJson("Programmed", "True", "Programmed", "Listener programmed", gwGeneration),
              conditionJson("ResolvedRefs", refsResolved, refsReason, refsMessage, gwGeneration)
            )
          } else if (!protocolOk) {
            Json.arr(
              conditionJson(
                "Accepted",
                "False",
                "UnsupportedProtocol",
                s"Protocol ${listener.protocol} not supported",
                gwGeneration
              ),
              conditionJson("Programmed", "False", "Invalid", "Listener not programmed", gwGeneration)
            )
          } else {
            Json.arr(
              conditionJson(
                "Accepted",
                "False",
                "PortUnavailable",
                s"Port ${listener.port} does not match Otoroshi listener ports " +
                  s"(HTTP:${conf.gatewayApiHttpListenerPort.mkString(",")}, HTTPS:${conf.gatewayApiHttpsListenerPort.mkString(",")})",
                gwGeneration
              ),
              conditionJson("Programmed", "False", "Invalid", "Listener not programmed", gwGeneration)
            )
          }

          // Count routes attached to this specific listener.
          // A route is attached if its parentRef matches this gateway and listener,
          // and the listener's allowedRoutes accepts routes from the route's namespace.
          val attachedRouteCount = countAttachedRoutes(gw, listener, httpRoutes, grpcRoutes, namespaces)

          Json.obj(
            "name"           -> listener.name,
            "conditions"     -> conditions,
            "attachedRoutes" -> attachedRouteCount,
            "supportedKinds" -> Json.arr(
              Json.obj("group" -> "gateway.networking.k8s.io", "kind" -> "HTTPRoute"),
              Json.obj("group" -> "gateway.networking.k8s.io", "kind" -> "GRPCRoute")
            )
          )
        }

        val gatewayAccepted = listenerStatuses.nonEmpty
        val gwGen = gw.raw.select("metadata").select("generation").asOpt[Long]
        val gatewayStatus = Json.obj(
          "conditions" -> Json.arr(
            conditionJson(
              "Accepted",
              if (gatewayAccepted) "True" else "False",
              if (gatewayAccepted) "Accepted" else "NotReconciled",
              if (gatewayAccepted) "Gateway accepted by Otoroshi" else "No valid listeners",
              gwGen
            ),
            conditionJson(
              "Programmed",
              if (gatewayAccepted) "True" else "False",
              if (gatewayAccepted) "Programmed" else "NotReconciled",
              if (gatewayAccepted) "Gateway programmed" else "Gateway not programmed",
              gwGen
            )
          ),
          "addresses"  -> addresses,
          "listeners"  -> JsArray(listenerStatuses)
        )

        client.updateGatewayStatus(gw.namespace, gw.name, gatewayStatus).map(_ => gw)
      })
    }
  }

  /**
   * Counts how many routes (HTTPRoute + GRPCRoute) are attached to a specific listener.
   *
   * A route is considered attached to a listener when:
   * 1. One of its parentRefs matches the gateway name and namespace
   * 2. If parentRef.sectionName is set, it must match the listener name
   * 3. The listener's allowedRoutes accepts the route's namespace
   */
  private def countAttachedRoutes(
      gw: KubernetesGateway,
      listener: GatewayListener,
      httpRoutes: Seq[KubernetesHTTPRoute],
      grpcRoutes: Seq[KubernetesGRPCRoute],
      namespaces: Seq[KubernetesNamespace]
  ): Int = {
    def routeAttaches(parentRefs: Seq[HTTPRouteParentRef], routeNamespace: String): Boolean = {
      parentRefs.exists { ref =>
        val refGwNamespace = ref.namespace.getOrElse(routeNamespace)
        val gwMatches = ref.name == gw.name && refGwNamespace == gw.namespace
        val listenerMatches = ref.sectionName match {
          case Some(sn) => sn == listener.name
          case None     => true // no sectionName means all listeners
        }
        val namespaceAllowed = listener.allowedRoutesNamespacesFrom match {
          case "All"  => true
          case "Same" => routeNamespace == gw.namespace
          case "Selector" =>
            listener.allowedRoutesNamespacesSelector match {
              case None => true
              case Some(selector) =>
                namespaces.find(_.name == routeNamespace).exists { ns =>
                  val matchLabels = (selector \ "matchLabels").asOpt[Map[String, String]].getOrElse(Map.empty)
                  matchLabels.forall { case (k, v) => ns.labels.get(k).contains(v) }
                }
            }
          case _ => false
        }
        gwMatches && listenerMatches && namespaceAllowed
      }
    }

    val httpCount = httpRoutes.count(r => routeAttaches(r.parentRefs, r.namespace))
    val grpcCount = grpcRoutes.count(r => routeAttaches(r.parentRefs, r.namespace))
    httpCount + grpcCount
  }

  // ─── Reconcile HTTPRoutes ─────────────────────────────────────────────────

  private def reconcileHTTPRoutes(
      client: KubernetesClient,
      httpRoutes: Seq[KubernetesHTTPRoute],
      acceptedGateways: Seq[KubernetesGateway],
      services: Seq[KubernetesService],
      endpoints: Seq[KubernetesEndpoint],
      endpointSlices: Seq[KubernetesEndpointSlice],
      referenceGrants: Seq[KubernetesReferenceGrant],
      backendTLSPolicies: Seq[KubernetesBackendTLSPolicy],
      resolvedCaCertIds: Map[String, String],
      plugins: Seq[KubernetesPlugin],
      namespaces: Seq[KubernetesNamespace],
      conf: KubernetesConfig
  )(implicit env: Env, ec: ExecutionContext): Future[Seq[NgRoute]] = {
    implicit val mat = env.otoroshiMaterializer

    Source(httpRoutes.toList)
      .mapAsync(1) { httpRoute =>
        val result = GatewayApiConverter.httpRouteToNgRoutes(
          httpRoute, acceptedGateways, services, endpoints, endpointSlices,
          referenceGrants, backendTLSPolicies, resolvedCaCertIds, plugins, namespaces, conf
        )
        val generatedRoutes = result.routes

        val httpRouteGen = httpRoute.raw.select("metadata").select("generation").asOpt[Long]
        val parentStatuses = httpRoute.parentRefs.map { parentRef =>
          val gwNamespace = parentRef.namespace.getOrElse(httpRoute.namespace)
          val gatewayFound = acceptedGateways.exists(gw =>
            gw.name == parentRef.name && gw.namespace == gwNamespace
          )

          // Determine the ResolvedRefs condition with precise reasons:
          //   - RefNotPermitted: a cross-namespace backendRef was denied (missing ReferenceGrant)
          //   - BackendNotFound: a backend Service could not be resolved
          //   - ResolvedRefs: all references resolved successfully
          val (resolvedStatus, resolvedReason, resolvedMessage) = if (result.refNotPermitted) {
            ("False", "RefNotPermitted",
              "One or more cross-namespace backend references are denied (missing ReferenceGrant)")
          } else if (generatedRoutes.nonEmpty && generatedRoutes.exists(_.backend.targets.isEmpty)) {
            ("False", "BackendNotFound",
              "Some backend references could not be resolved to existing Services")
          } else {
            ("True", "ResolvedRefs", "All references resolved")
          }

          Json.obj(
            "parentRef" -> Json.obj(
              "group"     -> "gateway.networking.k8s.io",
              "kind"      -> "Gateway",
              "namespace" -> gwNamespace,
              "name"      -> parentRef.name
            ),
            "controllerName" -> conf.gatewayApiControllerName,
            "conditions"     -> Json.arr(
              conditionJson(
                "Accepted",
                if (gatewayFound) "True" else "False",
                if (gatewayFound) "Accepted" else "NoMatchingParent",
                if (gatewayFound) "Route accepted"
                else s"Gateway ${gwNamespace}/${parentRef.name} not found",
                httpRouteGen
              ),
              conditionJson("ResolvedRefs", resolvedStatus, resolvedReason, resolvedMessage, httpRouteGen)
            )
          )
        }

        val routeStatus = Json.obj("parents" -> JsArray(parentStatuses))
        client
          .updateHTTPRouteStatus(httpRoute.namespace, httpRoute.name, routeStatus)
          .map(_ => generatedRoutes)
      }
      .runWith(Sink.seq)
      .map(_.flatten)
  }

  // ─── Reconcile GRPCRoutes ──────────────────────────────────────────────────

  private def reconcileGRPCRoutes(
      client: KubernetesClient,
      grpcRoutes: Seq[KubernetesGRPCRoute],
      acceptedGateways: Seq[KubernetesGateway],
      services: Seq[KubernetesService],
      endpoints: Seq[KubernetesEndpoint],
      endpointSlices: Seq[KubernetesEndpointSlice],
      referenceGrants: Seq[KubernetesReferenceGrant],
      backendTLSPolicies: Seq[KubernetesBackendTLSPolicy],
      resolvedCaCertIds: Map[String, String],
      plugins: Seq[KubernetesPlugin],
      namespaces: Seq[KubernetesNamespace],
      conf: KubernetesConfig
  )(implicit env: Env, ec: ExecutionContext): Future[Seq[NgRoute]] = {
    implicit val mat = env.otoroshiMaterializer

    Source(grpcRoutes.toList)
      .mapAsync(1) { grpcRoute =>
        val result = GatewayApiConverter.grpcRouteToNgRoutes(
          grpcRoute, acceptedGateways, services, endpoints, endpointSlices,
          referenceGrants, backendTLSPolicies, resolvedCaCertIds, plugins, namespaces, conf
        )
        val generatedRoutes = result.routes

        val grpcRouteGen = grpcRoute.raw.select("metadata").select("generation").asOpt[Long]
        val parentStatuses = grpcRoute.parentRefs.map { parentRef =>
          val gwNamespace = parentRef.namespace.getOrElse(grpcRoute.namespace)
          val gatewayFound = acceptedGateways.exists(gw =>
            gw.name == parentRef.name && gw.namespace == gwNamespace
          )

          // Same ResolvedRefs logic as HTTPRoute (see reconcileHTTPRoutes)
          val (resolvedStatus, resolvedReason, resolvedMessage) = if (result.refNotPermitted) {
            ("False", "RefNotPermitted",
              "One or more cross-namespace backend references are denied (missing ReferenceGrant)")
          } else if (generatedRoutes.nonEmpty && generatedRoutes.exists(_.backend.targets.isEmpty)) {
            ("False", "BackendNotFound",
              "Some backend references could not be resolved to existing Services")
          } else {
            ("True", "ResolvedRefs", "All references resolved")
          }

          Json.obj(
            "parentRef" -> Json.obj(
              "group"     -> "gateway.networking.k8s.io",
              "kind"      -> "Gateway",
              "namespace" -> gwNamespace,
              "name"      -> parentRef.name
            ),
            "controllerName" -> conf.gatewayApiControllerName,
            "conditions"     -> Json.arr(
              conditionJson(
                "Accepted",
                if (gatewayFound) "True" else "False",
                if (gatewayFound) "Accepted" else "NoMatchingParent",
                if (gatewayFound) "Route accepted"
                else s"Gateway ${gwNamespace}/${parentRef.name} not found",
                grpcRouteGen
              ),
              conditionJson("ResolvedRefs", resolvedStatus, resolvedReason, resolvedMessage, grpcRouteGen)
            )
          )
        }

        val routeStatus = Json.obj("parents" -> JsArray(parentStatuses))
        client
          .updateGRPCRouteStatus(grpcRoute.namespace, grpcRoute.name, routeStatus)
          .map(_ => generatedRoutes)
      }
      .runWith(Sink.seq)
      .map(_.flatten)
  }

  // ─── Save and cleanup ────────────────────────────────────────────────────

  private def saveGeneratedRoutes(
      generatedRoutes: Seq[NgRoute],
      existingManagedRoutes: Seq[NgRoute]
  )(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val existingMap = existingManagedRoutes.map(r => (r.id, r)).toMap
    val toSave = generatedRoutes.filter { route =>
      existingMap.get(route.id) match {
        case None    => true
        case Some(existing) => existing.json != route.json
      }
    }
    if (toSave.nonEmpty) {
      logger.info(s"Saving ${toSave.size} Gateway API routes")
    }
    implicit val mat = env.otoroshiMaterializer
    Source(toSave.toList)
      .mapAsync(1)(route =>
        route.save().recover { case e =>
          logger.error(s"Failed to save route ${route.id}: ${e.getMessage}")
          false
        }
      )
      .runWith(Sink.ignore)
      .map(_ => ())
  }

  private def deleteOrphanedRoutes(
      generatedRoutes: Seq[NgRoute],
      existingManagedRoutes: Seq[NgRoute]
  )(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val generatedIds = generatedRoutes.map(_.id).toSet
    val toDelete = existingManagedRoutes
      .filter(r => r.metadata.get("otoroshi-provider").contains(PROVIDER))
      .filterNot(r => generatedIds.contains(r.id))
    if (toDelete.nonEmpty) {
      logger.info(s"Deleting ${toDelete.size} orphaned Gateway API routes: ${toDelete.map(_.id).mkString(", ")}")
      env.datastores.routeDataStore.deleteByIds(toDelete.map(_.id)).map(_ => ())
    } else {
      ().future
    }
  }

  // ─── Helpers ─────────────────────────────────────────────────────────────

  private def conditionJson(
      condType: String,
      status: String,
      reason: String,
      message: String,
      observedGeneration: Option[Long] = None
  ): JsObject = {
    val base = Json.obj(
      "type"               -> condType,
      "status"             -> status,
      "reason"             -> reason,
      "message"            -> message,
      "lastTransitionTime" -> DateTime.now().toString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    )
    observedGeneration match {
      case Some(gen) => base ++ Json.obj("observedGeneration" -> gen)
      case None      => base
    }
  }
}
