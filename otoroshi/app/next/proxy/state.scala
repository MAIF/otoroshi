package otoroshi.next.proxy

import otoroshi.auth.AuthModuleConfig
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{AdditionalHeadersOut, OverrideHost}
import otoroshi.script._
import otoroshi.ssl.Cert
import otoroshi.utils.TypedMap
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.RequestHeader

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

class NgProxyState(env: Env) {

  private val logger = Logger("otoroshi-proxy-state")

  private val routes = TrieMap.newBuilder[String, NgRoute]
    .+=(NgRoute.fake.id -> NgRoute.fake)
    .result()
  private val apikeys = new TrieMap[String, ApiKey]()
  private val targets = new TrieMap[String, NgTarget]()
  private val backends = new TrieMap[String, NgBackend]()
  private val jwtVerifiers = new TrieMap[String, GlobalJwtVerifier]()
  private val certificates = new TrieMap[String, Cert]()
  private val authModules = new TrieMap[String, AuthModuleConfig]()
  private val routesByDomain = new TrieMap[String, Seq[NgRoute]]()
  private val domainPathTreeRef = new AtomicReference[NgTreeRouter](NgTreeRouter.empty)

  def findRoutes(domain: String, path: String): Option[Seq[NgRoute]] = domainPathTreeRef.get().find(domain, path).map(_.routes)
  def findRoute(request: RequestHeader, attrs: TypedMap): Option[NgMatchedRoute] = domainPathTreeRef.get().findRoute(request, attrs)(env)
  def getDomainRoutes(domain: String): Option[Seq[NgRoute]] = routesByDomain.get(domain) match {
    case s @ Some(_) => s
    case None => domainPathTreeRef.get().findWildcard(domain).map(_.routes)
  }

  def backend(id: String): Option[NgBackend] = backends.get(id)
  def target(id: String): Option[NgTarget] = targets.get(id)
  def route(id: String): Option[NgRoute] = routes.get(id)
  def apikey(id: String): Option[ApiKey] = apikeys.get(id)
  def jwtVerifier(id: String): Option[GlobalJwtVerifier] = jwtVerifiers.get(id)
  def certificate(id: String): Option[Cert] = certificates.get(id)
  def authModule(id: String): Option[AuthModuleConfig] = authModules.get(id)

  def allApikeys(): Seq[ApiKey] = apikeys.values.toSeq
  def allJwtVerifiers(): Seq[GlobalJwtVerifier] = jwtVerifiers.values.toSeq
  def allCertificates(): Seq[Cert] = certificates.values.toSeq
  def allAuthModules(): Seq[AuthModuleConfig] = authModules.values.toSeq

  def updateRoutes(values: Seq[NgRoute]): Unit = {
    routes.++=(values.map(v => (v.id, v))).--=(routes.keySet.toSeq.diff(values.map(_.id)))
    val routesByDomainRaw: Map[String, Seq[NgRoute]] = values
      .flatMap(r => r.frontend.domains.map(d => NgRouteDomainAndPathWrapper(r, d.domain, d.path)))
      .filterNot(_.domain.contains("*"))
      .groupBy(_.domain)
      .mapValues(_.sortWith((r1, r2) => r1.path.length.compareTo(r2.path.length) > 0).map(_.route))
    routesByDomain.++=(routesByDomainRaw).--=(routesByDomain.keySet.toSeq.diff(routesByDomainRaw.keySet.toSeq))
    val s = System.currentTimeMillis()
    domainPathTreeRef.set(NgTreeRouter.build(values))
    val d = System.currentTimeMillis() - s
    logger.debug(s"built TreeRouter(${values.size} routes) in ${d} ms.")
    // java.nio.file.Files.writeString(new java.io.File("./tree-router-config.json").toPath, domainPathTreeRef.get().json.prettify)
  }

  def updateTargets(values: Seq[StoredNgTarget]): Unit = {
    targets.++=(values.map(v => (v.id, v.target))).--=(targets.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateBackends(values: Seq[StoredNgBackend]): Unit = {
    backends.++=(values.map(v => (v.id, v.backend))).--=(backends.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateApikeys(values: Seq[ApiKey]): Unit = {
    apikeys.++=(values.map(v => (v.clientId, v))).--=(apikeys.keySet.toSeq.diff(values.map(_.clientId)))
  }

  def updateJwtVerifiers(values: Seq[GlobalJwtVerifier]): Unit = {
    jwtVerifiers.++=(values.map(v => (v.id, v))).--=(jwtVerifiers.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateCertificates(values: Seq[Cert]): Unit = {
    certificates.++=(values.map(v => (v.id, v))).--=(certificates.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateAuthModules(values: Seq[AuthModuleConfig]): Unit = {
    authModules.++=(values.map(v => (v.id, v))).--=(authModules.keySet.toSeq.diff(values.map(_.id)))
  }
}

object NgProxyStateLoaderJob {
  val firstSync = new AtomicBoolean(false)
}

class NgProxyStateLoaderJob extends Job {

  private val fakeRoutesCount = 10000 // 300000

  override def uniqueId: JobId = JobId("io.otoroshi.next.core.jobs.NgProxyStateLoaderJob")

  override def name: String = "proxy state loader job"

  override def visibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.millisecond.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiInstance

  def generateRoutesByDomain(env: Env): Future[Seq[NgRoute]] = {
    import NgPluginHelper.pluginId
    if (env.env == "dev") {
      (0 until fakeRoutesCount).map { idx =>
        NgRoute(
          location = EntityLocation.default,
          id = s"route_generated-domain-${idx}",
          name = s"generated_fake_route_domain_${idx}",
          description = s"generated_fake_route_domain_${idx}",
          tags = Seq.empty,
          metadata = Map.empty,
          enabled = true,
          debugFlow = true,
          frontend = NgFrontend(
            domains = Seq(NgDomainAndPath(s"${idx}-generated-next-gen.oto.tools")),
            headers = Map.empty,
            methods = Seq.empty,
            stripPath = true,
            strict = false,
          ),
          backend = NgBackend(
            targets = Seq(NgTarget(
              id = "mirror-1",
              hostname = "mirror.otoroshi.io",
              port = 443,
              tls = true
            )),
            targetRefs = Seq.empty,
            root = s"/gen-${idx}",
            rewrite = false,
            loadBalancing = RoundRobin
          ),
          client = ClientConfig(),
          healthCheck = HealthCheck(false, "/"),
          plugins = NgPlugins(Seq(
            NgPluginInstance(
              plugin = pluginId[OverrideHost],
              enabled = true,
              include = Seq.empty,
              exclude = Seq.empty,
              config = NgPluginInstanceConfig(Json.obj())
            ),
            NgPluginInstance(
              plugin = pluginId[AdditionalHeadersOut],
              enabled = true,
              include = Seq.empty,
              exclude = Seq.empty,
              config = NgPluginInstanceConfig(Json.obj(
                "headers" -> Json.obj(
                  "bar" -> "foo"
                )
              ))
            )
          ))
        )
      }.vfuture
    } else {
      Seq.empty.vfuture
    }
  }

  def generateRoutesByName(env: Env): Future[Seq[NgRoute]] = {
    import NgPluginHelper.pluginId
    if (env.env == "dev") {
      (0 until fakeRoutesCount).map { idx =>
        NgRoute(
          location = EntityLocation.default,
          id = s"route_generated-path-${idx}",
          name = s"generated_fake_route_path_${idx}",
          description = s"generated_fake_route_path_${idx}",
          tags = Seq.empty,
          metadata = Map.empty,
          enabled = true,
          debugFlow = true,
          frontend = NgFrontend(
            domains = Seq(NgDomainAndPath(s"path-generated-next-gen.oto.tools/api/${idx}")),
            headers = Map.empty,
            methods = Seq.empty,
            stripPath = true,
            strict = false,
          ),
          backend = NgBackend(
            targets = Seq(NgTarget(
              id = "mirror-1",
              hostname = "mirror.otoroshi.io",
              port = 443,
              tls = true
            )),
            targetRefs = Seq.empty,
            root = s"/path-${idx}",
            rewrite = false,
            loadBalancing = RoundRobin
          ),
          client = ClientConfig(),
          healthCheck = HealthCheck(false, "/"),
          plugins = NgPlugins(Seq(
            NgPluginInstance(
              plugin = pluginId[OverrideHost],
              config = NgPluginInstanceConfig(Json.obj())
            ),
            NgPluginInstance(
              plugin = pluginId[AdditionalHeadersOut],
              config = NgPluginInstanceConfig(Json.obj(
                "headers" -> Json.obj(
                  "bar" -> "foo"
                )
              ))
            )
          ))
        )
      }.vfuture
    } else {
      Seq.empty.vfuture
    }
  }

  def generateRandomRoutes(env: Env): Future[Seq[NgRoute]] = {
    import NgPluginHelper.pluginId
    if (env.env == "dev") {
      (0 until ((Math.random() * 50) + 10).toInt).map { idx =>
        NgRoute(
          location = EntityLocation.default,
          id = s"route_generated-random-${idx}",
          name = s"generated_fake_route_random_${idx}",
          description = s"generated_fake_route_random_${idx}",
          tags = Seq.empty,
          metadata = Map.empty,
          enabled = true,
          debugFlow = true,
          frontend = NgFrontend(
            domains = Seq(NgDomainAndPath(s"random-generated-next-gen.oto.tools/api/${idx}")),
            headers = Map.empty,
            methods = Seq.empty,
            stripPath = true,
            strict = false,
          ),
          backend = NgBackend(
            targets = Seq(NgTarget(
              id = "mirror-1",
              hostname = "mirror.otoroshi.io",
              port = 443,
              tls = true
            )),
            targetRefs = Seq.empty,
            root = s"/path-${idx}",
            rewrite = false,
            loadBalancing = RoundRobin
          ),
          client = ClientConfig(),
          healthCheck = HealthCheck(false, "/"),
          plugins = NgPlugins(Seq(
            NgPluginInstance(
              plugin = pluginId[OverrideHost],
              config = NgPluginInstanceConfig(Json.obj())
            ),
            NgPluginInstance(
              plugin = pluginId[AdditionalHeadersOut],
              config = NgPluginInstanceConfig(Json.obj(
                "headers" -> Json.obj(
                  "bar" -> "foo"
                )
              ))
            )
          ))
        )
      }.vfuture
    } else {
      Seq.empty.vfuture
    }
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val start = System.currentTimeMillis()
    val config = env.datastores.globalConfigDataStore.latest().plugins.config.select("NextGenProxyEngine").asObject
    val debug = config.select("debug").asOpt[Boolean].getOrElse(false)
    val debugHeaders = config.select("debug_headers").asOpt[Boolean].getOrElse(false)
    for {
      routes <- env.datastores.routeDataStore.findAll() // Seq.empty[Route].vfuture //
      genRoutesDomain <- generateRoutesByDomain(env)
      genRoutesPath <- generateRoutesByName(env)
      genRandom <- generateRandomRoutes(env)
      descriptors <- env.datastores.serviceDescriptorDataStore.findAll()
      fakeRoutes = if (env.env == "dev") Seq(NgRoute.fake) else Seq.empty
      newRoutes = (genRoutesDomain ++ genRoutesPath ++ genRandom ++ descriptors.map(d => NgRoute.fromServiceDescriptor(d, debug || debugHeaders).seffectOn(_.serviceDescriptor)) ++ routes ++ fakeRoutes).filter(_.enabled)
      apikeys <- env.datastores.apiKeyDataStore.findAll()
      certs <- env.datastores.certificatesDataStore.findAll()
      verifiers <- env.datastores.globalJwtVerifierDataStore.findAll()
      modules <- env.datastores.authConfigsDataStore.findAll()
      targets <- env.datastores.targetsDataStore.findAll()
      backends <- env.datastores.backendsDataStore.findAll()
    } yield {
      env.proxyState.updateRoutes(newRoutes)
      env.proxyState.updateTargets(targets)
      env.proxyState.updateBackends(backends)
      env.proxyState.updateApikeys(apikeys)
      env.proxyState.updateCertificates(certs)
      env.proxyState.updateAuthModules(modules)
      env.proxyState.updateJwtVerifiers(verifiers)
      NgProxyStateLoaderJob.firstSync.compareAndSet(false, true)
      // println(s"job done in ${System.currentTimeMillis() - start} ms")
    }
  }.andThen {
    case Failure(e) => e.printStackTrace()
  }.map(_ => ())
}

