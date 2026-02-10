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
// CRITICAL:
// - [x] ReferenceGrant enforcement: cross-namespace backendRef validation
//       (see isBackendRefAllowed / hasMatchingReferenceGrant in gateway_converter.scala)
// - [ ] TLS certificate resolution: resolve Gateway listener certificateRefs
//       to Otoroshi Cert entities (reuse KubernetesCertSyncJob pattern)
//
// ROUTE TYPES:
// - [ ] TLSRoute support (gateway.networking.k8s.io/v1alpha2)
// - [ ] TCPRoute support (gateway.networking.k8s.io/v1alpha2)
// - [ ] UDPRoute support (gateway.networking.k8s.io/v1alpha2)
//
// FILTERS:
// - [ ] RequestMirror filter support
// - [ ] ExtensionRef filter support
// - [ ] URLRewrite replaceFullPath (currently partial support)
// - [ ] URLRewrite complex replacePrefixMatch patterns
//
// STATUS:
// - [ ] Gateway addresses status (report Otoroshi's external IP/hostname)
// - [ ] Listener attachedRoutes count in Gateway status
// - [ ] observedGeneration tracking on all status conditions
// - [ ] Conflict detection on listener hostname/port collisions
//
// ADVANCED:
// - [ ] BackendTLSPolicy support
// - [ ] Watch mode (event-driven sync instead of periodic polling)
// - [ ] Endpoint slice resolution (instead of clusterIP only)
// - [ ] Conformance test suite (gateway-api conformance tests)
// ─────────────────────────────────────────────────────────────────────────────

class KubernetesGatewayApiControllerJob extends Job {

  private val logger      = Logger("otoroshi-plugins-kubernetes-gateway-api-job")
  private val stopCommand = new AtomicBoolean(false)

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
    ().future
  }

  override def jobStop(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    logger.info("Stopping Kubernetes Gateway API controller job")
    stopCommand.set(true)
    ().future
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    Try(KubernetesConfig.theConfig(ctx)) match {
      case Failure(e) =>
        logger.error(s"Failed to read KubernetesConfig: ${e.getMessage}")
        ().future
      case Success(conf) if conf.gatewayApi =>
        KubernetesGatewayApiJob.syncGatewayApi(conf, ctx.attrs, !stopCommand.get())
      case _ =>
        ().future
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
   * 5. Reconcile Gateway -> validate listeners, update status
   * 6. Reconcile HTTPRoute -> convert to NgRoute, save
   * 7. Reconcile GRPCRoute -> convert to NgRoute (HTTP/2 targets), save
   * 8. Delete orphaned Otoroshi routes
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
        grpcRoutes      <- client.fetchGRPCRoutes()
        referenceGrants <- client.fetchReferenceGrants()
        namespaces      <- client.fetchNamespacesAndFilterLabels()
        services        <- client.fetchServices()
        endpoints       <- client.fetchEndpoints()

        // Fetch existing Otoroshi routes managed by this provider
        existingRoutes <- if (conf.useProxyState) env.proxyState.allRoutes().vfuture
                          else env.datastores.routeDataStore.findAll()
        managedRoutes = existingRoutes.filter(
          _.metadata.get("otoroshi-provider").contains(PROVIDER)
        )

        // ─── Phase 2: Reconcile GatewayClasses ──────────────────
        _ <- reconcileGatewayClasses(client, gatewayClasses, conf)

        // ─── Phase 3: Reconcile Gateways ────────────────────────
        acceptedGateways <- reconcileGateways(client, gateways, gatewayClasses, conf)

        // ─── Phase 4: Reconcile HTTPRoutes ──────────────────────
        httpGeneratedRoutes <- reconcileHTTPRoutes(
          client, httpRoutes, acceptedGateways, services, endpoints, referenceGrants, namespaces, conf
        )

        // ─── Phase 5: Reconcile GRPCRoutes ──────────────────────
        grpcGeneratedRoutes <- reconcileGRPCRoutes(
          client, grpcRoutes, acceptedGateways, services, endpoints, referenceGrants, namespaces, conf
        )

        generatedRoutes = httpGeneratedRoutes ++ grpcGeneratedRoutes

        // ─── Phase 6: Save generated routes ─────────────────────
        _ <- saveGeneratedRoutes(generatedRoutes, managedRoutes)

        // ─── Phase 7: Delete orphaned routes ────────────────────
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

  // ─── Reconcile Gateways ───────────────────────────────────────────────────

  private def reconcileGateways(
      client: KubernetesClient,
      gateways: Seq[KubernetesGateway],
      gatewayClasses: Seq[KubernetesGatewayClass],
      conf: KubernetesConfig
  )(implicit ec: ExecutionContext): Future[Seq[KubernetesGateway]] = {
    val acceptedClassNames = gatewayClasses
      .filter(_.controllerName == conf.gatewayApiControllerName)
      .map(_.name)
      .toSet
    val ourGateways = gateways.filter(gw => acceptedClassNames.contains(gw.gatewayClassName))

    Future
      .sequence(ourGateways.map { gw =>
        val listenerStatuses = gw.listeners.map { listener =>
          val portOk = listener.protocol match {
            case "HTTP"  => listener.port == conf.gatewayApiHttpListenerPort
            case "HTTPS" => listener.port == conf.gatewayApiHttpsListenerPort
            case _       => false
          }
          val protocolOk = Seq("HTTP", "HTTPS").contains(listener.protocol)

          val conditions = if (portOk && protocolOk) {
            Json.arr(
              conditionJson("Accepted", "True", "Accepted", "Listener accepted"),
              conditionJson("Programmed", "True", "Programmed", "Listener programmed"),
              conditionJson("ResolvedRefs", "True", "ResolvedRefs", "References resolved")
            )
          } else if (!protocolOk) {
            Json.arr(
              conditionJson(
                "Accepted",
                "False",
                "UnsupportedProtocol",
                s"Protocol ${listener.protocol} not supported"
              ),
              conditionJson("Programmed", "False", "Invalid", "Listener not programmed")
            )
          } else {
            Json.arr(
              conditionJson(
                "Accepted",
                "False",
                "PortUnavailable",
                s"Port ${listener.port} does not match Otoroshi listener ports " +
                  s"(HTTP:${conf.gatewayApiHttpListenerPort}, HTTPS:${conf.gatewayApiHttpsListenerPort})"
              ),
              conditionJson("Programmed", "False", "Invalid", "Listener not programmed")
            )
          }

          Json.obj(
            "name"           -> listener.name,
            "conditions"     -> conditions,
            "attachedRoutes" -> 0, // TODO: compute actual attached routes count
            "supportedKinds" -> Json.arr(
              Json.obj("group" -> "gateway.networking.k8s.io", "kind" -> "HTTPRoute"),
              Json.obj("group" -> "gateway.networking.k8s.io", "kind" -> "GRPCRoute")
            )
          )
        }

        val gatewayAccepted = listenerStatuses.nonEmpty
        val gatewayStatus = Json.obj(
          "conditions" -> Json.arr(
            conditionJson(
              "Accepted",
              if (gatewayAccepted) "True" else "False",
              if (gatewayAccepted) "Accepted" else "NotReconciled",
              if (gatewayAccepted) "Gateway accepted by Otoroshi" else "No valid listeners"
            ),
            conditionJson(
              "Programmed",
              if (gatewayAccepted) "True" else "False",
              if (gatewayAccepted) "Programmed" else "NotReconciled",
              if (gatewayAccepted) "Gateway programmed" else "Gateway not programmed"
            )
          ),
          "listeners" -> JsArray(listenerStatuses)
        )

        client.updateGatewayStatus(gw.namespace, gw.name, gatewayStatus).map(_ => gw)
      })
  }

  // ─── Reconcile HTTPRoutes ─────────────────────────────────────────────────

  private def reconcileHTTPRoutes(
      client: KubernetesClient,
      httpRoutes: Seq[KubernetesHTTPRoute],
      acceptedGateways: Seq[KubernetesGateway],
      services: Seq[KubernetesService],
      endpoints: Seq[KubernetesEndpoint],
      referenceGrants: Seq[KubernetesReferenceGrant],
      namespaces: Seq[KubernetesNamespace],
      conf: KubernetesConfig
  )(implicit env: Env, ec: ExecutionContext): Future[Seq[NgRoute]] = {
    implicit val mat = env.otoroshiMaterializer

    Source(httpRoutes.toList)
      .mapAsync(1) { httpRoute =>
        val result = GatewayApiConverter.httpRouteToNgRoutes(
          httpRoute, acceptedGateways, services, endpoints, referenceGrants, namespaces, conf
        )
        val generatedRoutes = result.routes

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
                else s"Gateway ${gwNamespace}/${parentRef.name} not found"
              ),
              conditionJson("ResolvedRefs", resolvedStatus, resolvedReason, resolvedMessage)
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
      referenceGrants: Seq[KubernetesReferenceGrant],
      namespaces: Seq[KubernetesNamespace],
      conf: KubernetesConfig
  )(implicit env: Env, ec: ExecutionContext): Future[Seq[NgRoute]] = {
    implicit val mat = env.otoroshiMaterializer

    Source(grpcRoutes.toList)
      .mapAsync(1) { grpcRoute =>
        val result = GatewayApiConverter.grpcRouteToNgRoutes(
          grpcRoute, acceptedGateways, services, endpoints, referenceGrants, namespaces, conf
        )
        val generatedRoutes = result.routes

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
                else s"Gateway ${gwNamespace}/${parentRef.name} not found"
              ),
              conditionJson("ResolvedRefs", resolvedStatus, resolvedReason, resolvedMessage)
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
