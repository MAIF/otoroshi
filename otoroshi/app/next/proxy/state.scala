package otoroshi.next.proxy

import otoroshi.auth.AuthModuleConfig
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models._
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.next.plugins.api.NgPluginHelper.pluginId
import otoroshi.next.plugins.{AdditionalHeadersIn, AdditionalHeadersOut, OverrideHost, SOAPAction, SOAPActionConfig}
import otoroshi.script._
import otoroshi.ssl.Cert
import otoroshi.tcp.TcpService
import otoroshi.utils.TypedMap
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

  private val routes            = TrieMap
    .newBuilder[String, NgRoute]
    .+=(NgRoute.fake.id -> NgRoute.fake)
    .result()

  private val apikeys             = new TrieMap[String, ApiKey]()
  private val targets             = new TrieMap[String, NgTarget]()
  private val backends            = new TrieMap[String, NgBackend]()
  private val jwtVerifiers        = new TrieMap[String, GlobalJwtVerifier]()
  private val certificates        = new TrieMap[String, Cert]()
  private val authModules         = new TrieMap[String, AuthModuleConfig]()
  private val errorTemplates      = new TrieMap[String, ErrorTemplate]()
  private val services            = new TrieMap[String, ServiceDescriptor]()
  private val teams               = new TrieMap[String, Team]()
  private val tenants             = new TrieMap[String, Tenant]()
  private val serviceGroups       = new TrieMap[String, ServiceGroup]()
  private val dataExporters       = new TrieMap[String, DataExporterConfig]()
  private val otoroshiAdmins      = new TrieMap[String, OtoroshiAdmin]()
  private val backofficeSessions  = new TrieMap[String, BackOfficeUser]()
  private val privateAppsSessions = new TrieMap[String, PrivateAppsUser]()
  private val tcpServices         = new TrieMap[String, TcpService]()
  private val scripts             = new TrieMap[String, Script]()

  private val routesByDomain    = new TrieMap[String, Seq[NgRoute]]()
  private val domainPathTreeRef = new AtomicReference[NgTreeRouter](NgTreeRouter.empty)

  def findRoutes(domain: String, path: String): Option[Seq[NgRoute]]             =
    domainPathTreeRef.get().find(domain, path).map(_.routes)

  def findRoute(request: RequestHeader, attrs: TypedMap): Option[NgMatchedRoute] =
    domainPathTreeRef.get().findRoute(request, attrs)(env)

  def getDomainRoutes(domain: String): Option[Seq[NgRoute]] = routesByDomain.get(domain) match {
    case s @ Some(_) => s
    case None        => domainPathTreeRef.get().findWildcard(domain).map(_.routes)
  }

  def script(id: String): Option[Script]                 = scripts.get(id)
  def backend(id: String): Option[NgBackend]             = backends.get(id)
  def errorTemplate(id: String): Option[ErrorTemplate]   = errorTemplates.get(id)
  def target(id: String): Option[NgTarget]               = targets.get(id)
  def route(id: String): Option[NgRoute]                 = routes.get(id)
  def apikey(id: String): Option[ApiKey]                 = apikeys.get(id)
  def jwtVerifier(id: String): Option[GlobalJwtVerifier] = jwtVerifiers.get(id)
  def certificate(id: String): Option[Cert]              = certificates.get(id)
  def authModule(id: String): Option[AuthModuleConfig]   = authModules.get(id)
  def service(id: String): Option[ServiceDescriptor] = services.get(id)
  def team(id: String): Option[Team] = teams.get(id)
  def tenant(id: String): Option[Tenant] = tenants.get(id)
  def serviceGroup(id: String): Option[ServiceGroup] = serviceGroups.get(id)
  def dataExporter(id: String): Option[DataExporterConfig] = dataExporters.get(id)
  def otoroshiAdmin(id: String): Option[OtoroshiAdmin] = otoroshiAdmins.get(id)
  def backofficeSession(id: String): Option[BackOfficeUser] = backofficeSessions.get(id)
  def privateAppsSession(id: String): Option[PrivateAppsUser] = privateAppsSessions.get(id)
  def tcpService(id: String): Option[TcpService] = tcpServices.get(id)

  def allScripts(): Seq[Script]                 = scripts.values.toSeq
  def allRoutes(): Seq[NgRoute]                 = routes.values.toSeq
  def allApikeys(): Seq[ApiKey]                 = apikeys.values.toSeq
  def allJwtVerifiers(): Seq[GlobalJwtVerifier] = jwtVerifiers.values.toSeq
  def allCertificates(): Seq[Cert]              = certificates.values.toSeq
  def allAuthModules(): Seq[AuthModuleConfig]   = authModules.values.toSeq
  def allServices(): Seq[ServiceDescriptor] = services.values.toSeq
  def allTeams(): Seq[Team] = teams.values.toSeq
  def allTenants(): Seq[Tenant] = tenants.values.toSeq
  def allServiceGroups(): Seq[ServiceGroup] = serviceGroups.values.toSeq
  def allDataExporters(): Seq[DataExporterConfig] = dataExporters.values.toSeq
  def allOtoroshiAdmins(): Seq[OtoroshiAdmin] = otoroshiAdmins.values.toSeq
  def allBackofficeSessions(): Seq[BackOfficeUser] = backofficeSessions.values.toSeq
  def allPrivateAppsSessions(): Seq[PrivateAppsUser] = privateAppsSessions.values.toSeq
  def allTcpServices(): Seq[TcpService] = tcpServices.values.toSeq

  def updateRoutes(values: Seq[NgRoute]): Unit = {
    routes.addAll(values.map(v => (v.id, v))).remAll(routes.keySet.toSeq.diff(values.map(_.id)))
    val routesByDomainRaw: Map[String, Seq[NgRoute]] = values
      .flatMap(r => r.frontend.domains.map(d => NgRouteDomainAndPathWrapper(r, d.domain, d.path)))
      .filterNot(_.domain.contains("*"))
      .groupBy(_.domain)
      .mapValues(_.sortWith((r1, r2) => r1.path.length.compareTo(r2.path.length) > 0).map(_.route))
    routesByDomain.addAll(routesByDomainRaw).remAll(routesByDomain.keySet.toSeq.diff(routesByDomainRaw.keySet.toSeq))
    val s                                            = System.currentTimeMillis()
    domainPathTreeRef.set(NgTreeRouter.build(values))
    val d                                            = System.currentTimeMillis() - s
    logger.debug(s"built TreeRouter(${values.size} routes) in ${d} ms.")
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
    otoroshiAdmins.addAll(values.map(v => (v.username, v))).remAll(otoroshiAdmins.keySet.toSeq.diff(values.map(_.username)))
  }

  def updateBackofficeSessions(values: Seq[BackOfficeUser]): Unit = {
    backofficeSessions.addAll(values.map(v => (v.randomId, v))).remAll(backofficeSessions.keySet.toSeq.diff(values.map(_.randomId)))
  }

  def updatePrivateAppsSessions(values: Seq[PrivateAppsUser]): Unit = {
    privateAppsSessions.addAll(values.map(v => (v.randomId, v))).remAll(privateAppsSessions.keySet.toSeq.diff(values.map(_.randomId)))
  }

  def updateTcpServices(values: Seq[TcpService]): Unit = {
    tcpServices.addAll(values.map(v => (v.id, v))).remAll(tcpServices.keySet.toSeq.diff(values.map(_.id)))
  }

  def updateTargets(values: Seq[StoredNgTarget]): Unit = {
    targets.addAll(values.map(v => (v.id, v.target))).remAll(targets.keySet.toSeq.diff(values.map(_.id)))
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
    errorTemplates.addAll(values.map(v => (v.serviceId, v))).remAll(errorTemplates.keySet.toSeq.diff(values.map(_.serviceId)))
  }

  def updateScripts(values: Seq[Script]): Unit = {
    scripts.addAll(values.map(v => (v.id, v))).remAll(scripts.keySet.toSeq.diff(values.map(_.id)))
  }
}

object NgProxyStateLoaderJob {
  val firstSync = new AtomicBoolean(false)
}

class NgProxyStateLoaderJob extends Job {

  private val fakeRoutesCount = 10 //10000 // 300000

  override def uniqueId: JobId = JobId("io.otoroshi.next.core.jobs.NgProxyStateLoaderJob")

  override def name: String = "proxy state loader job"

  override def visibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.millisecond.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = {
    env.configuration.getOptional[Long]("otoroshi.next.state-sync-interval").getOrElse(10000L).milliseconds.some
  }

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
          exportReporting = false,
          frontend = NgFrontend(
            domains = Seq(NgDomainAndPath(s"${idx}-generated-next-gen.oto.tools")),
            headers = Map.empty,
            query = Map.empty,
            methods = Seq.empty,
            stripPath = true,
            exact = false,
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
            targetRefs = Seq.empty,
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
          exportReporting = false,
          frontend = NgFrontend(
            domains = Seq(NgDomainAndPath(s"path-generated-next-gen.oto.tools/api/${idx}")),
            headers = Map.empty,
            query = Map.empty,
            methods = Seq.empty,
            stripPath = true,
            exact = false,
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
            targetRefs = Seq.empty,
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
          exportReporting = false,
          frontend = NgFrontend(
            domains = Seq(NgDomainAndPath(s"random-generated-next-gen.oto.tools/api/${idx}")),
            headers = Map.empty,
            query = Map.empty,
            methods = Seq.empty,
            stripPath = true,
            exact = false,
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
            targetRefs = Seq.empty,
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

  def soapRoute(env: Env): Seq[NgRoute] = {
    if (env.env == "dev") {
      Seq(NgRoute(
        location = EntityLocation.default,
        id = s"route_soap",
        name = s"route_soap",
        description = s"route_soap",
        tags = Seq.empty,
        metadata = Map.empty,
        enabled = true,
        debugFlow = true,
        exportReporting = false,
        frontend = NgFrontend(
          domains = Seq(NgDomainAndPath(s"soap-next-gen.oto.tools/text/from/number/:number")),
          headers = Map.empty,
          query = Map.empty,
          methods = Seq("GET"),
          stripPath = true,
          exact = false,
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
          targetRefs = Seq.empty,
          root = s"/webservicesserver/numberconversion.wso",
          rewrite = true,
          loadBalancing = RoundRobin,
          client = NgClientConfig.default
        ),
        plugins = NgPlugins(
          Seq(
            NgPluginInstance(
              plugin = pluginId[SOAPAction],
              config = NgPluginInstanceConfig(SOAPActionConfig(
                // url = "https://www.dataaccess.com/webservicesserver/numberconversion.wso",
                envelope =
                  s"""<?xml version="1.0" encoding="utf-8"?>
                    |<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                    |  <soap:Body>
                    |    <NumberToWords xmlns="http://www.dataaccess.com/webservicesserver/">
                    |      <ubiNum>$${req.pathparams.number}</ubiNum>
                    |    </NumberToWords>
                    |  </soap:Body>
                    |</soap:Envelope>""".stripMargin,
                jqResponseFilter = """{number_str: .["soap:Envelope"] | .["soap:Body"] | .["m:NumberToWordsResponse"] | .["m:NumberToWordsResult"] }""".some
              ).json.asObject)
            )
          )
        )
      ))
    } else {
      Seq.empty
    }
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val start        = System.currentTimeMillis()
    val config       = env.datastores.globalConfigDataStore.latest().plugins.config.select(ProxyEngine.configRoot).asOpt[JsObject].map(v => ProxyEngineConfig.parse(v, env)).getOrElse(ProxyEngineConfig.default)
    val debug        = config.debug
    val debugHeaders = config.debugHeaders
    for {
      routes              <- env.datastores.routeDataStore.findAll()
      routescomp          <- env.datastores.servicesDataStore.findAll()
      genRoutesDomain     <- generateRoutesByDomain(env)
      genRoutesPath       <- generateRoutesByName(env)
      genRandom           <- generateRandomRoutes(env)
      descriptors         <- env.datastores.serviceDescriptorDataStore.findAll()
      fakeRoutes          = if (env.env == "dev") Seq(NgRoute.fake) else Seq.empty
      newRoutes           = (genRoutesDomain ++ genRoutesPath ++ genRandom ++ descriptors.map(d =>
                            NgRoute.fromServiceDescriptor(d, debug || debugHeaders).seffectOn(_.serviceDescriptor)
                          ) ++ routes ++ routescomp.flatMap(_.toRoutes) ++ fakeRoutes ++ soapRoute(env)).filter(_.enabled)
      apikeys             <- env.datastores.apiKeyDataStore.findAll()
      certs               <- env.datastores.certificatesDataStore.findAll()
      verifiers           <- env.datastores.globalJwtVerifierDataStore.findAll()
      modules             <- env.datastores.authConfigsDataStore.findAll()
      targets             <- env.datastores.targetsDataStore.findAll()
      backends            <- env.datastores.backendsDataStore.findAll()
      errorTemplates      <- env.datastores.errorTemplateDataStore.findAll()
      teams               <- env.datastores.teamDataStore.findAll()
      tenants             <- env.datastores.tenantDataStore.findAll()
      serviceGroups       <- env.datastores.serviceGroupDataStore.findAll()
      dataExporters       <- env.datastores.dataExporterConfigDataStore.findAll()
      simpleAdmins        <- env.datastores.simpleAdminDataStore.findAll()
      webauthnAdmins      <- env.datastores.webAuthnAdminDataStore.findAll()
      backofficeSessions  <- env.datastores.backOfficeUserDataStore.findAll()
      privateAppsSessions <- env.datastores.privateAppsUserDataStore.findAll()
      tcpServices         <- env.datastores.tcpServiceDataStore.findAll()
      scripts             <- env.datastores.scriptDataStore.findAll()
      croutes             <- if (env.env == "dev") {
                           NgService
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
      env.proxyState.updateTargets(targets)
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
      NgProxyStateLoaderJob.firstSync.compareAndSet(false, true)
      env.metrics.timerUpdate("ng-proxy-state-refresh", System.currentTimeMillis() - start, TimeUnit.MILLISECONDS)
    }
  }.andThen { case Failure(e) =>
    e.printStackTrace()
  }.map(_ => ())
}

class NgInternalStateMonitor extends Job {

  import squants.information._
  import squants.time._

  private val logger = Logger("otoroshi-internal-state-monitor")

  override def uniqueId: JobId = JobId("io.otoroshi.next.core.jobs.NgInternalStateMonitor")

  override def name: String = "internal state size monitor"

  override def visibility: JobVisibility = JobVisibility.Internal

  override def kind: JobKind = JobKind.ScheduledEvery

  override def initialDelay(ctx: JobContext, env: Env): Option[FiniteDuration] = 1.second.some

  override def interval(ctx: JobContext, env: Env): Option[FiniteDuration] = 10.seconds.some

  override def starting: JobStarting = JobStarting.Automatically

  override def instantiation(ctx: JobContext, env: Env): JobInstantiation =  JobInstantiation.OneInstancePerOtoroshiInstance

  def monitorProxyState(env: Env): Unit = {
    val start = System.currentTimeMillis()
    val total = Bytes(org.openjdk.jol.info.GraphLayout.parseInstance(env.proxyState).totalSize())
    val duration = Milliseconds(System.currentTimeMillis() - start)
    env.metrics.markDouble("ng-proxy-state-size-monitoring", total.value)
    logger.debug(s"proxy-state: ${total.toMegabytes} mb, in ${duration}")
  }

  def monitorDataStoreState(env: Env): Unit = {
    val start = System.currentTimeMillis()
    val total = Bytes(org.openjdk.jol.info.GraphLayout.parseInstance(env.datastores).totalSize())
    val duration = Milliseconds(System.currentTimeMillis() - start)
    env.metrics.markDouble("ng-datastore-size-monitoring", total.value)
    logger.debug(s"datastore: ${total.toMegabytes} mb, in ${duration}")
  }

  override def jobRun(ctx: JobContext)(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
    val monitorState = env.configuration.getOptional[Boolean]("otoroshi.next.monitor-proxy-state-size").getOrElse(false)
    val monitorDatastore = env.configuration.getOptional[Boolean]("otoroshi.next.monitor-datastore-size").getOrElse(false)
    if (monitorState) monitorProxyState(env)
    if (monitorDatastore) monitorDataStoreState(env)
    ().vfuture
  }
}
