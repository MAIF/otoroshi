package otoroshi.next.proxy

import com.github.blemale.scaffeine.Scaffeine
import otoroshi.auth.AuthModuleConfig
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.next.plugins.api.NgPluginHelper.pluginId
import otoroshi.next.plugins.api.{NgPluginCategory, NgPluginHelper}
import otoroshi.next.plugins._
import otoroshi.script._
import otoroshi.ssl.{Cert, DynamicSSLEngineProvider}
import otoroshi.tcp.TcpService
import otoroshi.utils.TypedMap
import otoroshi.utils.cache.types.LegitTrieMap
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.RequestHeader

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

class NgProxyState(env: Env) {

  private val logger = Logger("otoroshi-proxy-state")

  private val routes = TrieMap
    .newBuilder[String, NgRoute]
    .result()

  private val apikeys             = new LegitTrieMap[String, ApiKey]()
  private val backends            = new LegitTrieMap[String, NgBackend]()
  private val ngroutecompositions = new LegitTrieMap[String, NgRouteComposition]()
  private val ngbackends          = new LegitTrieMap[String, StoredNgBackend]()
  private val jwtVerifiers        = new LegitTrieMap[String, GlobalJwtVerifier]()
  private val certificates        = new LegitTrieMap[String, Cert]()
  private val authModules         = new LegitTrieMap[String, AuthModuleConfig]()
  private val errorTemplates      = new LegitTrieMap[String, ErrorTemplate]()
  private val services            = new LegitTrieMap[String, ServiceDescriptor]()
  private val teams               = new LegitTrieMap[String, Team]()
  private val tenants             = new LegitTrieMap[String, Tenant]()
  private val serviceGroups       = new LegitTrieMap[String, ServiceGroup]()
  private val dataExporters       = new LegitTrieMap[String, DataExporterConfig]()
  private val otoroshiAdmins      = new LegitTrieMap[String, OtoroshiAdmin]()
  private val backofficeSessions  = new LegitTrieMap[String, BackOfficeUser]()
  private val privateAppsSessions = new LegitTrieMap[String, PrivateAppsUser]()
  private val tcpServices         = new LegitTrieMap[String, TcpService]()
  private val scripts             = new LegitTrieMap[String, Script]()
  private val tryItEnabledReports = Scaffeine()
    .expireAfterWrite(5.minutes)
    .maximumSize(100)
    .build[String, Unit]()
  private val tryItReports        = Scaffeine()
    .expireAfterWrite(5.minutes)
    .maximumSize(100)
    .build[String, NgExecutionReport]()

  private val routesByDomain    = new LegitTrieMap[String, Seq[NgRoute]]()
  private val domainPathTreeRef = new AtomicReference[NgTreeRouter](NgTreeRouter.empty)

  def enableReportFor(id: String): Unit = {
    tryItEnabledReports.put(id, ())
  }

  def isReportEnabledFor(id: String): Boolean = {
    tryItEnabledReports.getIfPresent(id) match {
      case Some(_) => {
        tryItEnabledReports.invalidate(id)
        true
      }
      case None    => false
    }
  }

  def addReport(id: String, report: NgExecutionReport): Unit = {
    tryItReports.put(id, report)
  }

  def report(id: String): Option[NgExecutionReport] = {
    tryItReports.getIfPresent(id) match {
      case Some(report) => {
        tryItReports.invalidate(id)
        report.some
      }
      case None         => None
    }
  }

  def findRoutes(domain: String, path: String): Option[Seq[NgRoute]] =
    domainPathTreeRef.get().find(domain, path).map(_.routes)

  def findRoute(request: RequestHeader, attrs: TypedMap): Option[NgMatchedRoute] =
    domainPathTreeRef.get().findRoute(request, attrs)(env)

  def getDomainRoutes(domain: String): Option[Seq[NgRoute]] = routesByDomain.get(domain) match {
    case s @ Some(_) => s
    case None        => domainPathTreeRef.get().findWildcard(domain).map(_.routes)
  }

  def script(id: String): Option[Script]                            = scripts.get(id)
  def backend(id: String): Option[NgBackend]                        = backends.get(id)
  def errorTemplate(id: String): Option[ErrorTemplate]              = errorTemplates.get(id)
  def route(id: String): Option[NgRoute]                            = routes.get(id)
  def routeComposition(id: String): Option[NgRouteComposition]      = ngroutecompositions.get(id)
  def apikey(id: String): Option[ApiKey]                            = apikeys.get(id)
  def jwtVerifier(id: String): Option[GlobalJwtVerifier]            = jwtVerifiers.get(id)
  def certificate(id: String): Option[Cert]                         = certificates.get(id)
  def authModule(id: String): Option[AuthModuleConfig]              = authModules.get(id)
  def authModuleAsync(id: String): Future[Option[AuthModuleConfig]] = authModules.get(id).vfuture
  def service(id: String): Option[ServiceDescriptor]                = services.get(id)
  def team(id: String): Option[Team]                                = teams.get(id)
  def tenant(id: String): Option[Tenant]                            = tenants.get(id)
  def serviceGroup(id: String): Option[ServiceGroup]                = serviceGroups.get(id)
  def dataExporter(id: String): Option[DataExporterConfig]          = dataExporters.get(id)
  def otoroshiAdmin(id: String): Option[OtoroshiAdmin]              = otoroshiAdmins.get(id)
  def backofficeSession(id: String): Option[BackOfficeUser]         = backofficeSessions.get(id)
  def privateAppsSession(id: String): Option[PrivateAppsUser]       = privateAppsSessions.get(id)
  def tcpService(id: String): Option[TcpService]                    = tcpServices.get(id)

  def allScripts(): Seq[Script]                      = scripts.values.toSeq
  def allRoutes(): Seq[NgRoute]                      = routes.values.toSeq
  def allApikeys(): Seq[ApiKey]                      = apikeys.values.toSeq
  def allJwtVerifiers(): Seq[GlobalJwtVerifier]      = jwtVerifiers.values.toSeq
  def allCertificates(): Seq[Cert]                   = certificates.values.toSeq
  def allCertificatesMap(): TrieMap[String, Cert]    = certificates
  def allAuthModules(): Seq[AuthModuleConfig]        = authModules.values.toSeq
  def allServices(): Seq[ServiceDescriptor]          = services.values.toSeq
  def allTeams(): Seq[Team]                          = teams.values.toSeq
  def allTenants(): Seq[Tenant]                      = tenants.values.toSeq
  def allServiceGroups(): Seq[ServiceGroup]          = serviceGroups.values.toSeq
  def allDataExporters(): Seq[DataExporterConfig]    = dataExporters.values.toSeq
  def allOtoroshiAdmins(): Seq[OtoroshiAdmin]        = otoroshiAdmins.values.toSeq
  def allBackofficeSessions(): Seq[BackOfficeUser]   = backofficeSessions.values.toSeq
  def allPrivateAppsSessions(): Seq[PrivateAppsUser] = privateAppsSessions.values.toSeq
  def allTcpServices(): Seq[TcpService]              = tcpServices.values.toSeq

  def allNgServices(): Seq[NgRouteComposition] = ngroutecompositions.values.toSeq
  def allBackends(): Seq[StoredNgBackend]      = ngbackends.values.toSeq

  def updateRoutes(values: Seq[NgRoute]): Unit = {
    routes.addAll(values.map(v => (v.cacheableId, v))).remAll(routes.keySet.toSeq.diff(values.map(_.cacheableId)))
    val routesByDomainRaw: Map[String, Seq[NgRoute]] = values
      .flatMap(r => r.frontend.domains.map(d => NgRouteDomainAndPathWrapper(r, d.domain, d.path)))
      .filterNot(_.domain.contains("*"))
      .groupBy(_.domain)
      .mapValues(_.sortWith((r1, r2) => r1.path.length.compareTo(r2.path.length) > 0).map(_.route))
    routesByDomain.addAll(routesByDomainRaw).remAll(routesByDomain.keySet.toSeq.diff(routesByDomainRaw.keySet.toSeq))
    val s                                            = System.currentTimeMillis()
    domainPathTreeRef.set(NgTreeRouter.build(values))
    val d                                            = System.currentTimeMillis() - s
    if (logger.isDebugEnabled) logger.debug(s"built TreeRouter(${values.size} routes) in ${d} ms.")
    // java.nio.file.Files.writeString(new java.io.File("./tree-router-config.json").toPath, domainPathTreeRef.get().json.prettify)
  }

  def updateServices(values: Seq[ServiceDescriptor]): Unit = {
    services.addAll(values.map(v => (v.id, v))).remAll(services.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateTeams(values: Seq[Team]): Unit = {
    teams.addAll(values.map(v => (v.id.value, v))).remAll(teams.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateTenants(values: Seq[Tenant]): Unit = {
    tenants.addAll(values.map(v => (v.id.value, v))).remAll(tenants.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateServiceGroups(values: Seq[ServiceGroup]): Unit = {
    serviceGroups.addAll(values.map(v => (v.id, v))).remAll(serviceGroups.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateDataExporters(values: Seq[DataExporterConfig]): Unit = {
    dataExporters.addAll(values.map(v => (v.id, v))).remAll(dataExporters.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateOtoroshiAdmins(values: Seq[OtoroshiAdmin]): Unit = {
    otoroshiAdmins
      .addAll(values.map(v => (v.username, v)))
      .remAll(otoroshiAdmins.keySet.toSeq.diff(values.map(_.username)))
  }

  def updateBackofficeSessions(values: Seq[BackOfficeUser]): Unit = {
    backofficeSessions
      .addAll(values.map(v => (v.randomId, v)))
      .remAll(backofficeSessions.keySet.toSeq.diff(values.map(_.randomId)))
  }

  def updatePrivateAppsSessions(values: Seq[PrivateAppsUser]): Unit = {
    privateAppsSessions
      .addAll(values.map(v => (v.randomId, v)))
      .remAll(privateAppsSessions.keySet.toSeq.diff(values.map(_.randomId)))
  }

  def updateTcpServices(values: Seq[TcpService]): Unit = {
    tcpServices.addAll(values.map(v => (v.id, v))).remAll(tcpServices.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateBackends(values: Seq[StoredNgBackend]): Unit = {
    backends.addAll(values.map(v => (v.id, v.backend))).remAll(backends.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateApikeys(values: Seq[ApiKey]): Unit = {
    apikeys.addAll(values.map(v => (v.clientId, v))).remAll(apikeys.keySet.toSeq.diff(values.map(_.clientId)))
  }

  def updateJwtVerifiers(values: Seq[GlobalJwtVerifier]): Unit = {
    jwtVerifiers.addAll(values.map(v => (v.id, v))).remAll(jwtVerifiers.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateCertificates(values: Seq[Cert]): Unit = {
    certificates.addAll(values.map(v => (v.id, v))).remAll(certificates.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateAuthModules(values: Seq[AuthModuleConfig]): Unit = {
    authModules.addAll(values.map(v => (v.id, v))).remAll(authModules.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateErrorTemplates(values: Seq[ErrorTemplate]): Unit = {
    errorTemplates
      .addAll(values.map(v => (v.serviceId, v)))
      .remAll(errorTemplates.keySet.toSeq.diff(values.map(_.serviceId)))
  }

  def updateScripts(values: Seq[Script]): Unit = {
    scripts.addAll(values.map(v => (v.id, v))).remAll(scripts.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateNgBackends(values: Seq[StoredNgBackend]): Unit = {
    ngbackends.addAll(values.map(v => (v.id, v))).remAll(ngbackends.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateNgSRouteCompositions(values: Seq[NgRouteComposition]): Unit = {
    ngroutecompositions
      .addAll(values.map(v => (v.id, v)))
      .remAll(ngroutecompositions.keySet.toSeq.diff(values.map(_.id)))
  }

  private val fakeRoutesCount = 10 //10000 // 300000

  private val dev = false && env.isDev

  private def generateRoutesByDomain(env: Env): Future[Seq[NgRoute]] = {
    import NgPluginHelper.pluginId
    if (dev) {
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
          capture = false,
          exportReporting = false,
          frontend = NgFrontend(
            domains = Seq(NgDomainAndPath(s"${idx}-generated-next-gen.oto.tools")),
            headers = Map.empty,
            query = Map.empty,
            methods = Seq.empty,
            stripPath = true,
            exact = false
          ),
          backend = NgBackend(
            targets = Seq(
              NgTarget(
                id = "mirror-1",
                hostname = "mirror.otoroshi.io",
                port = 443,
                tls = true
              )
            ),
            root = s"/gen-${idx}",
            rewrite = false,
            loadBalancing = RoundRobin,
            client = NgClientConfig.default
          ),
          plugins = NgPlugins(
            Seq(
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
                config = NgPluginInstanceConfig(
                  Json.obj(
                    "headers" -> Json.obj(
                      "bar" -> "foo"
                    )
                  )
                )
              )
            )
          )
        )
      }.vfuture
    } else {
      Seq.empty.vfuture
    }
  }

  private def generateRoutesByName(env: Env): Future[Seq[NgRoute]] = {
    import NgPluginHelper.pluginId
    if (dev) {
      (0 until fakeRoutesCount).map { idx =>
        NgRoute(
          location = EntityLocation.default,
          id = s"route_generated-path-${idx}",
          name = s"generated_fake_route_path_${idx}",
          description = s"generated_fake_route_path_${idx}",
          tags = Seq.empty,
          metadata = Map.empty,
          enabled = true,
          capture = false,
          debugFlow = true,
          exportReporting = false,
          frontend = NgFrontend(
            domains = Seq(NgDomainAndPath(s"path-generated-next-gen.oto.tools/api/${idx}")),
            headers = Map.empty,
            query = Map.empty,
            methods = Seq.empty,
            stripPath = true,
            exact = false
          ),
          backend = NgBackend(
            targets = Seq(
              NgTarget(
                id = "mirror-1",
                hostname = "mirror.otoroshi.io",
                port = 443,
                tls = true
              )
            ),
            root = s"/path-${idx}",
            rewrite = false,
            loadBalancing = RoundRobin,
            client = NgClientConfig.default
          ),
          plugins = NgPlugins(
            Seq(
              NgPluginInstance(
                plugin = pluginId[OverrideHost],
                config = NgPluginInstanceConfig(Json.obj())
              ),
              NgPluginInstance(
                plugin = pluginId[AdditionalHeadersOut],
                config = NgPluginInstanceConfig(
                  Json.obj(
                    "headers" -> Json.obj(
                      "bar" -> "foo"
                    )
                  )
                )
              )
            )
          )
        )
      }.vfuture
    } else {
      Seq.empty.vfuture
    }
  }

  private def generateRandomRoutes(env: Env): Future[Seq[NgRoute]] = {
    import NgPluginHelper.pluginId
    if (dev) {
      (0 until ((Math.random() * 50) + 10).toInt).map { idx =>
        NgRoute(
          location = EntityLocation.default,
          id = s"route_generated-random-${idx}",
          name = s"generated_fake_route_random_${idx}",
          description = s"generated_fake_route_random_${idx}",
          tags = Seq.empty,
          metadata = Map.empty,
          enabled = true,
          capture = false,
          debugFlow = true,
          exportReporting = false,
          frontend = NgFrontend(
            domains = Seq(NgDomainAndPath(s"random-generated-next-gen.oto.tools/api/${idx}")),
            headers = Map.empty,
            query = Map.empty,
            methods = Seq.empty,
            stripPath = true,
            exact = false
          ),
          backend = NgBackend(
            targets = Seq(
              NgTarget(
                id = "mirror-1",
                hostname = "mirror.otoroshi.io",
                port = 443,
                tls = true
              )
            ),
            root = s"/path-${idx}",
            rewrite = false,
            loadBalancing = RoundRobin,
            client = NgClientConfig.default
          ),
          plugins = NgPlugins(
            Seq(
              NgPluginInstance(
                plugin = pluginId[OverrideHost],
                config = NgPluginInstanceConfig(Json.obj())
              ),
              NgPluginInstance(
                plugin = pluginId[AdditionalHeadersOut],
                config = NgPluginInstanceConfig(
                  Json.obj(
                    "headers" -> Json.obj(
                      "bar" -> "foo"
                    )
                  )
                )
              )
            )
          )
        )
      }.vfuture
    } else {
      Seq.empty.vfuture
    }
  }

  private def soapRoute(env: Env): Seq[NgRoute] = {
    if (dev) {
      Seq(
        NgRoute(
          location = EntityLocation.default,
          id = s"route_soap",
          name = s"route_soap",
          description = s"route_soap",
          tags = Seq.empty,
          metadata = Map.empty,
          enabled = true,
          capture = false,
          debugFlow = true,
          exportReporting = false,
          frontend = NgFrontend(
            domains = Seq(NgDomainAndPath(s"soap-next-gen.oto.tools/text/from/number/:number")),
            headers = Map.empty,
            query = Map.empty,
            methods = Seq("GET"),
            stripPath = true,
            exact = false
          ),
          backend = NgBackend(
            targets = Seq(
              NgTarget(
                id = "www.dataaccess.com",
                hostname = "www.dataaccess.com",
                port = 443,
                tls = true
              )
            ),
            root = s"/webservicesserver/numberconversion.wso",
            rewrite = true,
            loadBalancing = RoundRobin,
            client = NgClientConfig.default
          ),
          plugins = NgPlugins(
            Seq(
              NgPluginInstance(
                plugin = pluginId[SOAPAction],
                config = NgPluginInstanceConfig(
                  SOAPActionConfig(
                    // url = "https://www.dataaccess.com/webservicesserver/numberconversion.wso",
                    envelope = s"""<?xml version="1.0" encoding="utf-8"?>
                                  |<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                                  |  <soap:Body>
                                  |    <NumberToWords xmlns="http://www.dataaccess.com/webservicesserver/">
                                  |      <ubiNum>$${req.pathparams.number}</ubiNum>
                                  |    </NumberToWords>
                                  |  </soap:Body>
                                  |</soap:Envelope>""".stripMargin,
                    jqResponseFilter =
                      """{number_str: .["soap:Envelope"] | .["soap:Body"] | .["m:NumberToWordsResponse"] | .["m:NumberToWordsResult"] }""".some
                  ).json.asObject
                )
              )
            )
          )
        )
      )
    } else {
      Seq.empty
    }
  }

  def sync()(implicit ec: ExecutionContext): Future[Unit] = {
    implicit val ev  = env
    val start        = System.currentTimeMillis()
    val config       = env.datastores.globalConfigDataStore
      .latest()
      .plugins
      .config
      .select(ProxyEngine.configRoot)
      .asOpt[JsObject]
      .map(v => ProxyEngineConfig.parse(v, env))
      .getOrElse(ProxyEngineConfig.default)
    val debug        = config.debug
    val debugHeaders = config.debugHeaders
    for {
      _                   <- env.vaults.renewSecretsInCache()
      routes              <- env.datastores.routeDataStore.findAllAndFillSecrets() // secrets OK
      routescomp          <- env.datastores.routeCompositionDataStore.findAllAndFillSecrets() // secrets OK
      genRoutesDomain     <- generateRoutesByDomain(env)
      genRoutesPath       <- generateRoutesByName(env)
      genRandom           <- generateRandomRoutes(env)
      descriptors         <- env.datastores.serviceDescriptorDataStore.findAllAndFillSecrets() // secrets OK
      fakeRoutes           = if (dev) Seq(NgRoute.fake) else Seq.empty
      newRoutes            = (genRoutesDomain ++ genRoutesPath ++ genRandom ++ descriptors.map(d =>
                               NgRoute.fromServiceDescriptor(d, debug || debugHeaders).seffectOn(_.serviceDescriptor)
                             ) ++ routes ++ routescomp.flatMap(_.toRoutes) ++ fakeRoutes ++ soapRoute(env)).filter(_.enabled)
      apikeys             <- env.datastores.apiKeyDataStore.findAllAndFillSecrets() // secrets OK
      certs               <- env.datastores.certificatesDataStore.findAllAndFillSecrets() // secrets OK
      verifiers           <- env.datastores.globalJwtVerifierDataStore.findAllAndFillSecrets() // secrets OK
      modules             <- env.datastores.authConfigsDataStore.findAllAndFillSecrets() // secrets OK
      backends            <- env.datastores.backendsDataStore.findAllAndFillSecrets() // secrets OK
      errorTemplates      <- env.datastores.errorTemplateDataStore.findAll() // no need for secrets
      teams               <- env.datastores.teamDataStore.findAll() // no need for secrets
      tenants             <- env.datastores.tenantDataStore.findAll() // no need for secrets
      serviceGroups       <- env.datastores.serviceGroupDataStore.findAll() // no need for secrets
      dataExporters       <- env.datastores.dataExporterConfigDataStore.findAllAndFillSecrets() // secrets OK
      simpleAdmins        <- env.datastores.simpleAdminDataStore.findAll() // no need for secrets
      webauthnAdmins      <- env.datastores.webAuthnAdminDataStore.findAll() // no need for secrets
      backofficeSessions  <- env.datastores.backOfficeUserDataStore.findAll() // no need for secrets
      privateAppsSessions <- env.datastores.privateAppsUserDataStore.findAll() // no need for secrets
      tcpServices         <- env.datastores.tcpServiceDataStore.findAllAndFillSecrets() // secrets OK
      scripts             <- env.datastores.scriptDataStore.findAll() // no need for secrets
      croutes             <- if (dev) {
                               NgRouteComposition
                                 .fromOpenApi(
                                   "oto-api-next-gen.oto.tools",
                                   "https://raw.githubusercontent.com/MAIF/otoroshi/master/otoroshi/public/openapi.json"
                                 )
                                 .map(route => {
                                   // java.nio.file.Files.writeString(new java.io.File("./service.json").toPath(), route.json.prettify)
                                   route.toRoutes.map(r =>
                                     r.copy(
                                       backend =
                                         r.backend.copy(targets = r.backend.targets.map(t => t.copy(port = 9999, tls = false))),
                                       plugins = NgPlugins(
                                         Seq(
                                           NgPluginInstance(
                                             plugin = NgPluginHelper.pluginId[OverrideHost]
                                           ),
                                           NgPluginInstance(
                                             plugin = NgPluginHelper.pluginId[AdditionalHeadersIn],
                                             config = NgPluginInstanceConfig(
                                               Json.obj(
                                                 "headers" -> Json.obj(
                                                   "Otoroshi-Client-Id"     -> "admin-api-apikey-id",
                                                   "Otoroshi-Client-Secret" -> "admin-api-apikey-secret"
                                                 )
                                               )
                                             )
                                           )
                                         )
                                       )
                                     )
                                   )
                                 })
                             } else Seq.empty[NgRoute].vfuture
    } yield {
      env.proxyState.updateRoutes(newRoutes ++ croutes)
      env.proxyState.updateBackends(backends)
      env.proxyState.updateApikeys(apikeys)
      env.proxyState.updateCertificates(certs)
      env.proxyState.updateAuthModules(modules)
      env.proxyState.updateJwtVerifiers(verifiers)
      env.proxyState.updateErrorTemplates(errorTemplates)
      env.proxyState.updateServices(descriptors)
      env.proxyState.updateTeams(teams)
      env.proxyState.updateTenants(tenants)
      env.proxyState.updateServiceGroups(serviceGroups)
      env.proxyState.updateDataExporters(dataExporters)
      env.proxyState.updateOtoroshiAdmins(simpleAdmins ++ webauthnAdmins)
      env.proxyState.updateBackofficeSessions(backofficeSessions)
      env.proxyState.updatePrivateAppsSessions(privateAppsSessions)
      env.proxyState.updateTcpServices(tcpServices)
      env.proxyState.updateScripts(scripts)
      env.proxyState.updateNgBackends(backends)
      env.proxyState.updateNgSRouteCompositions(routescomp)
      DynamicSSLEngineProvider.setCertificates(env)
      NgProxyStateLoaderJob.firstSync.compareAndSet(false, true)
      env.metrics.timerUpdate("ng-proxy-state-refresh", System.currentTimeMillis() - start, TimeUnit.MILLISECONDS)
    }
  }.andThen { case Failure(e) =>
    e.printStackTrace()
  }.map(_ => ())
}

object NgProxyStateLoaderJob {
  val firstSync = new AtomicBoolean(false)
}

class NgProxyStateLoaderJob extends Job {

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.next.core.jobs.NgProxyStateLoaderJob")

  override def name: String = "proxy state loader job"

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.millisecond.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = {
    env.configuration.getOptional[Long]("otoroshi.next.state-sync-interval").getOrElse(10000L).milliseconds.some
  }

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiInstance

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    env.proxyState.sync()
  }
}

class NgInternalStateMonitor extends Job {

  import squants.information._
  import squants.time._

  private val logger = Logger("otoroshi-internal-state-monitor")

  override def categories: Seq[NgPluginCategory] = Seq.empty

  override def uniqueId: JobId = JobId("io.otoroshi.next.core.jobs.NgInternalStateMonitor")

  override def name: String = "internal state size monitor"

  override def jobVisibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.second.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =
    JobInstantiation.OneInstancePerOtoroshiInstance

  def monitorProxyState(env: Env): Unit = {
    val start    = System.currentTimeMillis()
    val total    = Bytes(org.openjdk.jol.info.GraphLayout.parseInstance(env.proxyState).totalSize())
    val duration = Milliseconds(System.currentTimeMillis() - start)
    env.metrics.markDouble("ng-proxy-state-size-monitoring", total.value)
    if (logger.isDebugEnabled) logger.debug(s"proxy-state: ${total.toMegabytes} mb, in ${duration}")
  }

  def monitorDataStoreState(env: Env): Unit = {
    val start    = System.currentTimeMillis()
    val total    = Bytes(org.openjdk.jol.info.GraphLayout.parseInstance(env.datastores).totalSize())
    val duration = Milliseconds(System.currentTimeMillis() - start)
    env.metrics.markDouble("ng-datastore-size-monitoring", total.value)
    if (logger.isDebugEnabled) logger.debug(s"datastore: ${total.toMegabytes} mb, in ${duration}")
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val monitorState     = env.configuration.getOptional[Boolean]("otoroshi.next.monitor-proxy-state-size").getOrElse(false)
    val monitorDatastore =
      env.configuration.getOptional[Boolean]("otoroshi.next.monitor-datastore-size").getOrElse(false)
    if (monitorState) monitorProxyState(env)
    if (monitorDatastore) monitorDataStoreState(env)
    ().vfuture
  }
}
