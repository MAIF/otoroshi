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

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

class ProxyState(env: Env) {

  private val routes = TrieMap.newBuilder[String, Route]
    .+=(Route.fake.id -> Route.fake)
    .result()
  private val apikeys = new TrieMap[String, ApiKey]()
  private val jwtVerifiers = new TrieMap[String, GlobalJwtVerifier]()
  private val certificates = new TrieMap[String, Cert]()
  private val authModules = new TrieMap[String, AuthModuleConfig]()
  val routesByDomain = new TrieMap[String, Seq[Route]]()

  // private val chmRoutes = {
  //   val map = new ConcurrentHashMap[String, Route]()
  //   map.put(Route.fake.id, Route.fake)
  //   map
  // }
  // private val chmApikeys = new ConcurrentHashMap[String, ApiKey]()
  // private val chmJwtVerifiers = new ConcurrentHashMap[String, GlobalJwtVerifier]()
  // private val chmCertificates = new ConcurrentHashMap[String, Cert]()
  // private val chmAuthModules = new ConcurrentHashMap[String, AuthModuleConfig]()
  // private val chmRoutesByDomain = new ConcurrentHashMap[String, Seq[Route]]()
  private val cowRoutesByWildcardDomain = new CopyOnWriteArrayList[Route]()

  def route(id: String): Option[Route] = routes.get(id) // Option(chmRoutes.get(id))
  def apikey(id: String): Option[ApiKey] = apikeys.get(id) // Option(chmApikeys.get(id))
  def jwtVerifier(id: String): Option[GlobalJwtVerifier] = jwtVerifiers.get(id) // Option(chmJwtVerifiers.get(id))
  def certificate(id: String): Option[Cert] = certificates.get(id) // Option(chmCertificates.get(id))
  def authModule(id: String): Option[AuthModuleConfig] = authModules.get(id) // Option(chmAuthModules.get(id))
  def getDomainRoutes(domain: String): Option[Seq[Route]] = {
    //Option(chmRoutesByDomain.get(domain)) match {
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
    // val routeIds = new_routes.map(_.id)
    // chmRoutes.putAll(new_routes.map(r => (r.id, r)).toMap.asJava)
    // chmRoutes.keySet.asScala.foreach { key =>
    //   if (!routeIds.contains(key)) {
    //     chmRoutes.remove(key)
    //   }
    // }
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
    // chmRoutesByDomain.putAll(routesByDomain.asJava)
    // chmRoutesByDomain.keySet.asScala.foreach { key =>
    //   if (!domains.contains(key)) {
    //     chmRoutesByDomain.remove(key)
    //   }
    // }
    cowRoutesByWildcardDomain.clear()
    cowRoutesByWildcardDomain.addAll(routesWithWildcardDomains.asJava)
  }

  def updateApikeys(values: Seq[ApiKey]): Unit = {
    apikeys.++=(values.map(v => (v.clientId, v))).--=(apikeys.keySet.toSeq.diff(values.map(_.clientId)))
    // val apikeyIds = new_apikeys.map(_.clientId)
    // new_apikeys.foreach(apikey => apikeys.put(apikey.clientId, apikey))
    // apikeys.keySet.filterNot(key => apikeyIds.contains(key)).foreach(key => apikeys.remove(key))
    // val valueIds = values.map(_.clientId)
    // chmApikeys.putAll(values.map(r => (r.clientId, r)).toMap.asJava)
    // chmApikeys.keySet.asScala.foreach { key =>
    //   if (!valueIds.contains(key)) {
    //     chmApikeys.remove(key)
    //   }
    // }
  }

  def updateJwtVerifiers(values: Seq[GlobalJwtVerifier]): Unit = {
    jwtVerifiers.++=(values.map(v => (v.id, v))).--=(jwtVerifiers.keySet.toSeq.diff(values.map(_.id)))
    // val verifierIds = new_verifiers.map(_.id)
    // new_verifiers.foreach(verifier => jwtVerifiers.put(verifier.id, verifier))
    // jwtVerifiers.keySet.filterNot(key => verifierIds.contains(key)).foreach(key => jwtVerifiers.remove(key))
    // val valueIds = values.map(_.id)
    // chmJwtVerifiers.putAll(values.map(r => (r.id, r)).toMap.asJava)
    // chmJwtVerifiers.keySet.asScala.foreach { key =>
    //   if (!valueIds.contains(key)) {
    //     chmJwtVerifiers.remove(key)
    //   }
    // }
  }

  def updateCertificates(values: Seq[Cert]): Unit = {
    certificates.++=(values.map(v => (v.id, v))).--=(certificates.keySet.toSeq.diff(values.map(_.id)))
    // val certificatesIds = new_certificates.map(_.id)
    // new_certificates.foreach(certificate => certificates.put(certificate.id, certificate))
    // certificates.keySet.filterNot(key => certificatesIds.contains(key)).foreach(key => certificates.remove(key))
    // val valueIds = values.map(_.id)
    // chmCertificates.putAll(values.map(r => (r.id, r)).toMap.asJava)
    // chmCertificates.keySet.asScala.foreach { key =>
    //   if (!valueIds.contains(key)) {
    //     chmCertificates.remove(key)
    //   }
    // }
  }

  def updateAuthModules(values: Seq[AuthModuleConfig]): Unit = {
    authModules.++=(values.map(v => (v.id, v))).--=(authModules.keySet.toSeq.diff(values.map(_.id)))
    // val authModuleIds = new_authModule.map(_.id)
    // new_authModule.foreach(authModule => authModules.put(authModule.id, authModule))
    // authModules.keySet.filterNot(key => authModuleIds.contains(key)).foreach(key => authModules.remove(key))
    // val valueIds = values.map(_.id)
    // chmAuthModules.putAll(values.map(r => (r.id, r)).toMap.asJava)
    // chmAuthModules.keySet.asScala.foreach { key =>
    //   if (!valueIds.contains(key)) {
    //     chmAuthModules.remove(key)
    //   }
    // }
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
    }.vfuture
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
    }.vfuture
  }

  def generateRandomRoutes(): Future[Seq[Route]] = {
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
    }.vfuture
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val start = System.currentTimeMillis()
    for {
      routes <- env.datastores.routeDataStore.findAll().map(routes => routes ++ Seq(Route.fake))
      genRoutesDomain <- generateRoutesByDomain()
      genRoutesPath <- generateRoutesByName()
      genRandom <- generateRandomRoutes()
      descriptors <- env.datastores.serviceDescriptorDataStore.findAll()
      newRoutes = genRoutesDomain ++ genRoutesPath ++ genRandom ++ descriptors.map(Route.fromServiceDescriptor) ++ routes
      apikeys <- env.datastores.apiKeyDataStore.findAll()
      certs <- env.datastores.certificatesDataStore.findAll()
      verifiers <- env.datastores.globalJwtVerifierDataStore.findAll()
      modules <- env.datastores.authConfigsDataStore.findAll()
    } yield {
      env.proxyState.updateRoutes(newRoutes)
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
  }
}

