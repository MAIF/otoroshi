package otoroshi.next.proxy

import otoroshi.auth.AuthModuleConfig
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.{AdditionalHeadersOut, OverrideHost}
import otoroshi.script._
import otoroshi.ssl.Cert
import otoroshi.utils.RegexPool
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.Json

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

class ProxyState(env: Env) {

  private val routes = TrieMap.newBuilder[String, Route]
    .+=(Route.fake.id -> Route.fake)
    .result()
  private val apikeys = new TrieMap[String, ApiKey]()
  private val targets = new TrieMap[String, NgTarget]()
  private val backends = new TrieMap[String, Backend]()
  private val jwtVerifiers = new TrieMap[String, GlobalJwtVerifier]()
  private val certificates = new TrieMap[String, Cert]()
  private val authModules = new TrieMap[String, AuthModuleConfig]()
  val routesByDomain = new TrieMap[String, Seq[Route]]()
  private val cowRoutesByWildcardDomain = new CopyOnWriteArrayList[Route]()

  def backend(id: String): Option[Backend] = backends.get(id)
  def target(id: String): Option[NgTarget] = targets.get(id)
  def route(id: String): Option[Route] = routes.get(id)
  def apikey(id: String): Option[ApiKey] = apikeys.get(id)
  def jwtVerifier(id: String): Option[GlobalJwtVerifier] = jwtVerifiers.get(id)
  def certificate(id: String): Option[Cert] = certificates.get(id)
  def authModule(id: String): Option[AuthModuleConfig] = authModules.get(id)
  def getDomainRoutes(domain: String): Option[Seq[Route]] = {
    routesByDomain.get(domain) match {
      case s @ Some(_) => s
      case None => {
        cowRoutesByWildcardDomain.asScala.filter { route =>
          RegexPool(route.frontend.domains.head.domain).matches(domain)
        }.applyOn {
          case seq if seq.isEmpty => None
          case seq => seq.some
        }
      }
    }
  }

  def allRoutes(): Seq[Route] = routes.values.toSeq
  def allApikeys(): Seq[ApiKey] = apikeys.values.toSeq
  def allJwtVerifiers(): Seq[GlobalJwtVerifier] = jwtVerifiers.values.toSeq
  def allCertificates(): Seq[Cert] = certificates.values.toSeq
  def allAuthModules(): Seq[AuthModuleConfig] = authModules.values.toSeq

  def updateRoutes(values: Seq[Route]): Unit = {
    routes.++=(values.map(v => (v.id, v))).--=(routes.keySet.toSeq.diff(values.map(_.id)))
    val routesByDomainRaw: Map[String, Seq[Route]] = values
      .filter(_.enabled)
      .flatMap(r => r.frontend.domains.map(d => (d.domain, r.copy(frontend = r.frontend.copy(domains = Seq(d))))))
      .groupBy(_._1)
      .mapValues(_.map(_._2).sortWith((r1, r2) => r1.frontend.domains.head.path.length.compareTo(r2.frontend.domains.head.path.length) > 0))
    val (routesByWildcardDomainRaw, all_routesByDomain) = routesByDomainRaw.partition(_._1.contains("*"))
    val routesWithWildcardDomains = routesByWildcardDomainRaw
      .values
      .flatten
      .toSeq
      .sortWith((r1, r2) => r1.frontend.domains.head.domain.length.compareTo(r2.frontend.domains.head.domain.length) > 0)
    routesByDomain.++=(all_routesByDomain).--=(routesByDomain.keySet.toSeq.diff(all_routesByDomain.keySet.toSeq))
    cowRoutesByWildcardDomain.clear()
    cowRoutesByWildcardDomain.addAll(routesWithWildcardDomains.asJava)
    //println(routesByDomain.mapValues(_.size))
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

object ProxyStateLoaderJob {
  val firstSync = new AtomicBoolean(false)
}

class ProxyStateLoaderJob extends Job {

  override def uniqueId: JobId = JobId("io.otoroshi.next.core.jobs.ProxyStateLoaderJob")

  override def name: String = "proxy state loader job"

  override def visibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.millisecond.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiInstance

  def generateRoutesByDomain(env: Env): Future[Seq[Route]] = {
    import NgPluginHelper.pluginId
    if (env.env == "dev") {
      (0 until 10000).map { idx =>
        Route(
          location = EntityLocation.default,
          id = s"route_generated-domain-${idx}",
          name = s"generated_fake_route_domain_${idx}",
          description = s"generated_fake_route_domain_${idx}",
          tags = Seq.empty,
          metadata = Map.empty,
          enabled = true,
          debugFlow = true,
          frontend = Frontend(
            domains = Seq(DomainAndPath(s"${idx}-generated-next-gen.oto.tools")),
            headers = Map.empty,
            methods = Seq.empty,
            stripPath = true,
            apikey = ApiKeyRouteMatcher(),
          ),
          backend = Backend(
            targets = Seq(NgTarget(
              id = "mirror-1",
              hostname = "mirror.otoroshi.io",
              port = 443,
              tls = true
            )),
            targetRefs = Seq.empty,
            root = s"/gen-${idx}",
            loadBalancing = RoundRobin
          ),
          client = ClientConfig(),
          healthCheck = HealthCheck(false, "/"),
          plugins = NgPlugins(Seq(
            PluginInstance(
              plugin = pluginId[OverrideHost],
              enabled = true,
              include = Seq.empty,
              exclude = Seq.empty,
              config = PluginInstanceConfig(Json.obj())
            ),
            PluginInstance(
              plugin = pluginId[AdditionalHeadersOut],
              enabled = true,
              include = Seq.empty,
              exclude = Seq.empty,
              config = PluginInstanceConfig(Json.obj(
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

  def generateRoutesByName(env: Env): Future[Seq[Route]] = {
    import NgPluginHelper.pluginId
    if (env.env == "dev") {
      (0 until 10000).map { idx =>
        Route(
          location = EntityLocation.default,
          id = s"route_generated-path-${idx}",
          name = s"generated_fake_route_path_${idx}",
          description = s"generated_fake_route_path_${idx}",
          tags = Seq.empty,
          metadata = Map.empty,
          enabled = true,
          debugFlow = true,
          frontend = Frontend(
            domains = Seq(DomainAndPath(s"path-generated-next-gen.oto.tools/api/${idx}")),
            headers = Map.empty,
            methods = Seq.empty,
            stripPath = true,
            apikey = ApiKeyRouteMatcher(),
          ),
          backend = Backend(
            targets = Seq(NgTarget(
              id = "mirror-1",
              hostname = "mirror.otoroshi.io",
              port = 443,
              tls = true
            )),
            targetRefs = Seq.empty,
            root = s"/path-${idx}",
            loadBalancing = RoundRobin
          ),
          client = ClientConfig(),
          healthCheck = HealthCheck(false, "/"),
          plugins = NgPlugins(Seq(
            PluginInstance(
              plugin = pluginId[OverrideHost],
              config = PluginInstanceConfig(Json.obj())
            ),
            PluginInstance(
              plugin = pluginId[AdditionalHeadersOut],
              config = PluginInstanceConfig(Json.obj(
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

  def generateRandomRoutes(env: Env): Future[Seq[Route]] = {
    import NgPluginHelper.pluginId
    if (env.env == "dev") {
      (0 until ((Math.random() * 50) + 10).toInt).map { idx =>
        Route(
          location = EntityLocation.default,
          id = s"route_generated-random-${idx}",
          name = s"generated_fake_route_random_${idx}",
          description = s"generated_fake_route_radom_${idx}",
          tags = Seq.empty,
          metadata = Map.empty,
          enabled = true,
          debugFlow = true,
          frontend = Frontend(
            domains = Seq(DomainAndPath(s"random-generated-next-gen.oto.tools/api/${idx}")),
            headers = Map.empty,
            methods = Seq.empty,
            stripPath = true,
            apikey = ApiKeyRouteMatcher(),
          ),
          backend = Backend(
            targets = Seq(NgTarget(
              id = "mirror-1",
              hostname = "mirror.otoroshi.io",
              port = 443,
              tls = true
            )),
            targetRefs = Seq.empty,
            root = s"/path-${idx}",
            loadBalancing = RoundRobin
          ),
          client = ClientConfig(),
          healthCheck = HealthCheck(false, "/"),
          plugins = NgPlugins(Seq(
            PluginInstance(
              plugin = pluginId[OverrideHost],
              config = PluginInstanceConfig(Json.obj())
            ),
            PluginInstance(
              plugin = pluginId[AdditionalHeadersOut],
              config = PluginInstanceConfig(Json.obj(
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
      routes <- env.datastores.routeDataStore.findAll()
      genRoutesDomain <- generateRoutesByDomain(env)
      genRoutesPath <- generateRoutesByName(env)
      genRandom <- generateRandomRoutes(env)
      descriptors <- env.datastores.serviceDescriptorDataStore.findAll()
      fakeRoutes = if (env.env == "dev") Seq(Route.fake) else Seq.empty
      newRoutes = genRoutesDomain ++ genRoutesPath ++ genRandom ++ descriptors.map(d => Route.fromServiceDescriptor(d, debug || debugHeaders).seffectOn(_.serviceDescriptor)) ++ routes ++ fakeRoutes
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
      ProxyStateLoaderJob.firstSync.compareAndSet(false, true)

      /*
      println(s"job done in ${System.currentTimeMillis() - start} ms")
      val expectedDomains = newRoutes.flatMap(_.frontend.domains.map(_.domain)).distinct
      val expectedSize = expectedDomains.size
      val actualDomains = env.proxyState.routesByDomain.keySet.toSeq
      val actualSize = actualDomains.size
      val diff1 = expectedDomains.diff(actualDomains)
      val diff2 = actualDomains.diff(expectedDomains)
      val diff3 = newRoutes.diff(env.proxyState.allRoutes())
      val diff4 = env.proxyState.allRoutes().diff(newRoutes)
      println(s"got ${newRoutes.size} routes now !")
      println(s"expectedSize is: ${expectedSize}, actualSize is: ${actualSize}, diff1: ${diff1.size}, diff2: ${diff2.size}, diff3: ${diff3.size}, diff4: ${diff4.size}")
      if (diff1.nonEmpty || diff2.nonEmpty || diff3.nonEmpty || diff4.nonEmpty) {
        println(s"diff1: ${diff1}")
        println(s"diff2: ${diff2}")
        println(s"diff3: ${diff3}")
        println(s"diff4: ${diff4}")
      }
      */

    }
  }.andThen {
    case Failure(e) => e.printStackTrace()
  }.map(_ => ())
}

