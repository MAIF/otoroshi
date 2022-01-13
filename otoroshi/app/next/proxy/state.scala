package otoroshi.next.proxy

import otoroshi.auth.AuthModuleConfig
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.script._
import otoroshi.ssl.Cert
import otoroshi.utils.RegexPool
import otoroshi.utils.syntax.implicits._
import play.api.libs.json.Json

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}
import scala.collection.JavaConverters._
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

class ProxyState(env: Env) {

  // private val routes = TrieMap.newBuilder[String, Route]
  //   .+=(Route.fake.id -> Route.fake)
  //   .result()
  // private val apikeys = new TrieMap[String, ApiKey]()
  // private val jwtVerifiers = new TrieMap[String, GlobalJwtVerifier]()
  // private val certificates = new TrieMap[String, Cert]()
  // private val authModules = new TrieMap[String, AuthModuleConfig]()

  private val chmRoutes = {
    val map = new ConcurrentHashMap[String, Route]()
    map.put(Route.fake.id, Route.fake)
    map
  }
  private val chmApikeys = new ConcurrentHashMap[String, ApiKey]()
  private val chmJwtVerifiers = new ConcurrentHashMap[String, GlobalJwtVerifier]()
  private val chmCertificates = new ConcurrentHashMap[String, Cert]()
  private val chmAuthModules = new ConcurrentHashMap[String, AuthModuleConfig]()
  private val chmRoutesByDomain = new ConcurrentHashMap[String, Seq[Route]]()
  private val chmRoutesByWildcardDomain = new CopyOnWriteArrayList[Route]()

  def route(id: String): Option[Route] = Option(chmRoutes.get(id))
  def apikey(id: String): Option[ApiKey] = Option(chmApikeys.get(id))
  def jwtVerifier(id: String): Option[GlobalJwtVerifier] = Option(chmJwtVerifiers.get(id))
  def certificate(id: String): Option[Cert] = Option(chmCertificates.get(id))
  def authModule(id: String): Option[AuthModuleConfig] = Option(chmAuthModules.get(id))
  def getDomainRoutes(domain: String): Option[Seq[Route]] = {
    Option(chmRoutesByDomain.get(domain)) match {
      case s @ Some(_) => s
      case None => {
        chmRoutesByWildcardDomain.asScala.filter { route =>
          RegexPool(route.frontend.domains.head.domain).matches(domain)
        }.applyOn {
          case seq if seq.isEmpty => None
          case seq => seq.some
        }
      }
    }
  }

  // def allRoutes(): Iterable[Route] = chmRoutes.values().asScala
  // def allApikeys(): Seq[ApiKey] = chmApikeys.values().asScala.toSeq
  // def allJwtVerifiers(): Seq[GlobalJwtVerifier] = chmJwtVerifiers.values().asScala.toSeq
  // def allCertificates(): Seq[Cert] = chmCertificates.values().asScala.toSeq
  // def allAuthModules(): Seq[AuthModuleConfig] = chmAuthModules.values().asScala.toSeq

  // def old_allRoutes(): Seq[Route] = routes.values.toSeq
  // def old_allApikeys(): Seq[ApiKey] = apikeys.values.toSeq
  // def old_allJwtVerifiers(): Seq[GlobalJwtVerifier] = jwtVerifiers.values.toSeq
  // def old_allCertificates(): Seq[Cert] = certificates.values.toSeq
  // def old_allAuthModules(): Seq[AuthModuleConfig] = authModules.values.toSeq
  // def old_route(id: String): Option[Route] = routes.get(id)
  // def old_apikey(id: String): Option[ApiKey] = apikeys.get(id)
  // def old_jwtVerifier(id: String): Option[GlobalJwtVerifier] = jwtVerifiers.get(id)
  // def old_certificate(id: String): Option[Cert] = certificates.get(id)
  // def old_authModule(id: String): Option[AuthModuleConfig] = authModules.get(id)

  def updateRoutes(new_routes: Seq[Route]): Unit = {
    // val routeIds = new_routes.map(_.id)
    // new_routes.foreach(route => routes.put(route.id, route))
    // routes.keySet.filterNot(key => routeIds.contains(key)).foreach(key => routes.remove(key))
    val routeIds = new_routes.map(_.id)
    chmRoutes.putAll(new_routes.map(r => (r.id, r)).toMap.asJava)
    chmRoutes.keySet.asScala.foreach { key =>
      if (!routeIds.contains(key)) {
        chmRoutes.remove(key)
      }
    }
    val routesByDomainRaw: Map[String, Seq[Route]] = new_routes
      .filter(_.enabled)
      .flatMap(r => r.frontend.domains.map(d => (d.domain, r.copy(frontend = r.frontend.copy(domains = Seq(d))))))
      .groupBy(_._1)
      .mapValues(_.map(_._2).sortWith((r1, r2) => r1.frontend.domains.head.path.length.compareTo(r2.frontend.domains.head.path.length) > 0))
    val (routesByWildcardDomainRaw, routesByDomain) = routesByDomainRaw.partition(_._1.contains("*"))
    val routesWithWildcardDomains = routesByWildcardDomainRaw
      .values
      .flatten
      .toSeq
      .sortWith((r1, r2) => r1.frontend.domains.head.domain.length.compareTo(r2.frontend.domains.head.domain.length) > 0)
    val domains = routesByDomain.keySet
    chmRoutesByDomain.putAll(routesByDomain.asJava)
    chmRoutesByDomain.keySet.asScala.foreach { key =>
      if (!domains.contains(key)) {
        chmRoutesByDomain.remove(key)
      }
    }
    chmRoutesByWildcardDomain.clear()
    chmRoutesByWildcardDomain.addAll(routesWithWildcardDomains.asJava)
  }

  def updateApikeys(values: Seq[ApiKey]): Unit = {
    // val apikeyIds = new_apikeys.map(_.clientId)
    // new_apikeys.foreach(apikey => apikeys.put(apikey.clientId, apikey))
    // apikeys.keySet.filterNot(key => apikeyIds.contains(key)).foreach(key => apikeys.remove(key))
    val valueIds = values.map(_.clientId)
    chmApikeys.putAll(values.map(r => (r.clientId, r)).toMap.asJava)
    chmApikeys.keySet.asScala.foreach { key =>
      if (!valueIds.contains(key)) {
        chmApikeys.remove(key)
      }
    }
  }

  def updateJwtVerifiers(values: Seq[GlobalJwtVerifier]): Unit = {
    // val verifierIds = new_verifiers.map(_.id)
    // new_verifiers.foreach(verifier => jwtVerifiers.put(verifier.id, verifier))
    // jwtVerifiers.keySet.filterNot(key => verifierIds.contains(key)).foreach(key => jwtVerifiers.remove(key))
    val valueIds = values.map(_.id)
    chmJwtVerifiers.putAll(values.map(r => (r.id, r)).toMap.asJava)
    chmJwtVerifiers.keySet.asScala.foreach { key =>
      if (!valueIds.contains(key)) {
        chmJwtVerifiers.remove(key)
      }
    }
  }

  def updateCertificates(values: Seq[Cert]): Unit = {
    // val certificatesIds = new_certificates.map(_.id)
    // new_certificates.foreach(certificate => certificates.put(certificate.id, certificate))
    // certificates.keySet.filterNot(key => certificatesIds.contains(key)).foreach(key => certificates.remove(key))
    val valueIds = values.map(_.id)
    chmCertificates.putAll(values.map(r => (r.id, r)).toMap.asJava)
    chmCertificates.keySet.asScala.foreach { key =>
      if (!valueIds.contains(key)) {
        chmCertificates.remove(key)
      }
    }
  }

  def updateAuthModules(values: Seq[AuthModuleConfig]): Unit = {
    // val authModuleIds = new_authModule.map(_.id)
    // new_authModule.foreach(authModule => authModules.put(authModule.id, authModule))
    // authModules.keySet.filterNot(key => authModuleIds.contains(key)).foreach(key => authModules.remove(key))
    val valueIds = values.map(_.id)
    chmAuthModules.putAll(values.map(r => (r.id, r)).toMap.asJava)
    chmAuthModules.keySet.asScala.foreach { key =>
      if (!valueIds.contains(key)) {
        chmAuthModules.remove(key)
      }
    }
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

  def generateRoutesByDomain(): Future[Seq[Route]] = {
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
          stripPath = true,
          apikey = ApiKeyRouteMatcher(),
        ),
        backends = Backends(
          targets = Seq(Backend(
            id = "mirror-1",
            hostname = "mirror.otoroshi.io",
            port = 443,
            tls = true
          )),
          root = s"/gen-${idx}",
          loadBalancing = RoundRobin
        ),
        client = ClientConfig(),
        healthCheck = HealthCheck(false, "/"),
        plugins = Plugins(Seq(
          PluginInstance(
            plugin = "cp:otoroshi.next.plugins.OverrideHost",
            enabled = true,
            include = Seq.empty,
            exclude = Seq.empty,
            config = PluginInstanceConfig(Json.obj())
          ),
          PluginInstance(
            plugin = "cp:otoroshi.next.plugins.AdditionalHeadersOut",
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
    }.future
  }

  def generateRoutesByName(): Future[Seq[Route]] = {
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
          stripPath = true,
          apikey = ApiKeyRouteMatcher(),
        ),
        backends = Backends(
          targets = Seq(Backend(
            id = "mirror-1",
            hostname = "mirror.otoroshi.io",
            port = 443,
            tls = true
          )),
          root = s"/path-${idx}",
          loadBalancing = RoundRobin
        ),
        client = ClientConfig(),
        healthCheck = HealthCheck(false, "/"),
        plugins = Plugins(Seq(
          PluginInstance(
            plugin = "cp:otoroshi.next.plugins.OverrideHost",
            config = PluginInstanceConfig(Json.obj())
          ),
          PluginInstance(
            plugin = "cp:otoroshi.next.plugins.AdditionalHeadersOut",
            config = PluginInstanceConfig(Json.obj(
              "headers" -> Json.obj(
                "bar" -> "foo"
              )
            ))
          )
        ))
      )
    }.future
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val start = System.currentTimeMillis()
    (for {
      routes <- env.datastores.routeDataStore.findAll().map(routes => routes ++ Seq(Route.fake))
      genRoutesDomain <- generateRoutesByDomain()
      genRoutesPath <- generateRoutesByName()
      descriptors <- env.datastores.serviceDescriptorDataStore.findAll()
      newRoutes = genRoutesDomain ++ genRoutesPath ++ descriptors.map(Route.fromServiceDescriptor) ++ routes
      // _ = println(s"route stuff in ${System.currentTimeMillis() - start} ms")
      apikeys <- env.datastores.apiKeyDataStore.findAll()
      certs <- env.datastores.certificatesDataStore.findAll()
      verifiers <- env.datastores.globalJwtVerifierDataStore.findAll()
      modules <- env.datastores.authConfigsDataStore.findAll()
    } yield {
      // val secondStart = System.currentTimeMillis()
      env.proxyState.updateRoutes(newRoutes)
      // println(s"update 1 in ${System.currentTimeMillis() - secondStart} ms")
      env.proxyState.updateApikeys(apikeys)
      env.proxyState.updateCertificates(certs)
      env.proxyState.updateAuthModules(modules)
      env.proxyState.updateJwtVerifiers(verifiers)
      // println(s"update in ${System.currentTimeMillis() - secondStart} ms")
      // println(s"job done in ${System.currentTimeMillis() - start} ms")
      ProxyStateLoaderJob.firstSync.compareAndSet(false, true)
    })
  }
}

