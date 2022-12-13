package otoroshi.storage.stores

import akka.http.scaladsl.util.FastFuture
import otoroshi.auth.{AuthModuleConfig, GenericOauth2ModuleConfig, SessionCookieValues}
import otoroshi.env.Env
import otoroshi.models._
import otoroshi.next.models.{NgRoute, NgRouteComposition, StoredNgBackend}
import otoroshi.script.Script
import otoroshi.security.Auth0Config
import otoroshi.ssl.{Cert, ClientCertificateValidator}
import otoroshi.storage.{RedisLike, RedisLikeStore}
import otoroshi.tcp.TcpService
import otoroshi.utils.cache.types.LegitConcurrentHashMap
import play.api.Logger
import play.api.libs.json._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Success

class KvGlobalConfigDataStore(redisCli: RedisLike, _env: Env)
    extends GlobalConfigDataStore
    with RedisLikeStore[GlobalConfig] {

  lazy val logger = Logger("otoroshi-globalconfig-datastore")

  override def fmt: Format[GlobalConfig] = GlobalConfig._fmt

  override def key(id: String): String =
    s"${_env.storageRoot}:config:global" // WARN : its a singleton, id is always global

  override def extractId(value: GlobalConfig): String = "global" // WARN : its a singleton, id is always global

  override def redisLike(implicit env: Env): RedisLike = redisCli

  def throttlingKey(): String = s"${_env.storageRoot}:throttling:global"

  private val callsForIpAddressCache =
    new LegitConcurrentHashMap[String, java.util.concurrent.atomic.AtomicLong]() // TODO: check growth over time
  private val quotasForIpAddressCache =
    new LegitConcurrentHashMap[String, java.util.concurrent.atomic.AtomicLong]() // TODO: check growth over time

  def incrementCallsForIpAddressWithTTL(ipAddress: String, ttl: Int = 10)(implicit
      ec: ExecutionContext
  ): Future[Long] = {

    @inline
    def actualCall() =
      redisCli.incrby(s"${_env.storageRoot}:throttling:perip:$ipAddress", 1L).flatMap { secCalls =>
        if (!callsForIpAddressCache.containsKey(ipAddress)) {
          callsForIpAddressCache.putIfAbsent(ipAddress, new java.util.concurrent.atomic.AtomicLong(secCalls))
        } else {
          callsForIpAddressCache.get(ipAddress).set(secCalls)
        }
        redisCli.pttl(s"${_env.storageRoot}:throttling:perip:$ipAddress").filter(_ > -1).recoverWith { case _ =>
          redisCli.expire(s"${_env.storageRoot}:throttling:perip:$ipAddress", ttl)
        } map (_ => secCalls)
      }

    if (callsForIpAddressCache.containsKey(ipAddress)) {
      actualCall()
      FastFuture.successful(callsForIpAddressCache.get(ipAddress).get)
    } else {
      actualCall()
    }
  }

  def quotaForIpAddress(ipAddress: String)(implicit ec: ExecutionContext): Future[Option[Long]] = {
    @inline
    def actualCall() =
      redisCli.get(s"${_env.storageRoot}:throttling:peripquota:$ipAddress").map(_.map(_.utf8String.toLong)).andThen {
        case Success(Some(quota)) if !quotasForIpAddressCache.containsKey(ipAddress) =>
          quotasForIpAddressCache.putIfAbsent(ipAddress, new java.util.concurrent.atomic.AtomicLong(quota))
        case Success(Some(quota)) if quotasForIpAddressCache.containsKey(ipAddress)  =>
          quotasForIpAddressCache.get(ipAddress).set(quota)
      }
    if (quotasForIpAddressCache.containsKey(ipAddress)) {
      actualCall()
      FastFuture.successful(Some(quotasForIpAddressCache.get(ipAddress).get))
    } else {
      actualCall()
    }
  }

  override def isOtoroshiEmpty()(implicit ec: ExecutionContext): Future[Boolean] = {
    redisCli.keys(key("global")).map(_.isEmpty)
  }

  private val throttlingQuotasCache = new java.util.concurrent.atomic.AtomicLong(0L)

  override def withinThrottlingQuota()(implicit ec: ExecutionContext, env: Env): Future[Boolean] = {
    val config = latest()
    //singleton().map { config =>
    redisCli.get(throttlingKey()).map { bs =>
      throttlingQuotasCache.set(bs.map(_.utf8String.toLong).getOrElse(0L))
      throttlingQuotasCache.get() <= (config.throttlingQuota * 10L)
    }
    //}
  }
  // singleton().flatMap { config =>
  //   redisCli.get(throttlingKey()).map { bs =>
  //     val count = bs.map(_.utf8String.toLong).getOrElse(0L)
  //     count <= (config.throttlingQuota * 10L)
  //   }
  // }

  def quotasValidationFor(
      from: String
  )(implicit ec: ExecutionContext, env: Env): Future[(Boolean, Long, Option[Long])] = {
    val a = withinThrottlingQuota()
    val b = incrementCallsForIpAddressWithTTL(from)
    val c = quotaForIpAddress(from)
    for {
      within     <- a
      secCalls   <- b
      maybeQuota <- c
    } yield (within, secCalls, maybeQuota)
  }

  override def updateQuotas(
      config: otoroshi.models.GlobalConfig
  )(implicit ec: ExecutionContext, env: Env): Future[Unit] =
    for {
      secCalls <- redisCli.incrby(throttlingKey(), 1L)
      _        <- redisCli.pttl(throttlingKey()).filter(_ > -1).recoverWith { case _ => redisCli.expire(throttlingKey(), 10) }
      fu        = env.metrics.markLong(s"global.throttling-quotas", secCalls)
    } yield ()

  override def allEnv()(implicit ec: ExecutionContext, env: Env): Future[Set[String]] = singleton().map(_.lines.toSet)

  private val configCache     = new java.util.concurrent.atomic.AtomicReference[GlobalConfig](null)
  private val lastConfigCache = new java.util.concurrent.atomic.AtomicLong(0L)

  override def findById(id: String)(implicit ec: ExecutionContext, env: Env): Future[Option[GlobalConfig]] = {
    val staticGlobalScripts: GlobalScripts = env.staticGlobalScripts
    if (/*env.staticExposedDomainEnabled && */ staticGlobalScripts.enabled) {
      super
        .findById(id)(ec, env)
        .map(_.map { c =>
          c.copy(
            scripts = GlobalScripts(
              enabled = true,
              transformersRefs = staticGlobalScripts.transformersRefs ++ c.scripts.transformersRefs,
              transformersConfig = staticGlobalScripts.transformersConfig.as[JsObject] ++ c.scripts.transformersConfig
                .as[JsObject],
              validatorRefs = staticGlobalScripts.validatorRefs ++ c.scripts.validatorRefs,
              validatorConfig = staticGlobalScripts.validatorConfig.as[JsObject] ++ c.scripts.validatorConfig
                .as[JsObject],
              preRouteRefs = staticGlobalScripts.preRouteRefs ++ c.scripts.preRouteRefs,
              preRouteConfig = staticGlobalScripts.preRouteConfig.as[JsObject] ++ c.scripts.preRouteConfig.as[JsObject],
              sinkRefs = staticGlobalScripts.sinkRefs ++ c.scripts.sinkRefs,
              sinkConfig = staticGlobalScripts.sinkConfig.as[JsObject] ++ c.scripts.sinkConfig.as[JsObject],
              jobRefs = staticGlobalScripts.jobRefs ++ c.scripts.jobRefs,
              jobConfig = staticGlobalScripts.jobConfig.as[JsObject] ++ c.scripts.jobConfig.as[JsObject]
            )
          )
        })
    } else {
      super.findById(id)(ec, env)
    }
  }
  override def findByIdAndFillSecrets(
      id: String
  )(implicit ec: ExecutionContext, env: Env): Future[Option[GlobalConfig]] = {
    val staticGlobalScripts: GlobalScripts = env.staticGlobalScripts
    if (/*env.staticExposedDomainEnabled && */ staticGlobalScripts.enabled) {
      super
        .findByIdAndFillSecrets(id)(ec, env)
        .map(_.map { c =>
          c.copy(
            scripts = GlobalScripts(
              enabled = true,
              transformersRefs = staticGlobalScripts.transformersRefs ++ c.scripts.transformersRefs,
              transformersConfig = staticGlobalScripts.transformersConfig.as[JsObject] ++ c.scripts.transformersConfig
                .as[JsObject],
              validatorRefs = staticGlobalScripts.validatorRefs ++ c.scripts.validatorRefs,
              validatorConfig = staticGlobalScripts.validatorConfig.as[JsObject] ++ c.scripts.validatorConfig
                .as[JsObject],
              preRouteRefs = staticGlobalScripts.preRouteRefs ++ c.scripts.preRouteRefs,
              preRouteConfig = staticGlobalScripts.preRouteConfig.as[JsObject] ++ c.scripts.preRouteConfig.as[JsObject],
              sinkRefs = staticGlobalScripts.sinkRefs ++ c.scripts.sinkRefs,
              sinkConfig = staticGlobalScripts.sinkConfig.as[JsObject] ++ c.scripts.sinkConfig.as[JsObject],
              jobRefs = staticGlobalScripts.jobRefs ++ c.scripts.jobRefs,
              jobConfig = staticGlobalScripts.jobConfig.as[JsObject] ++ c.scripts.jobConfig.as[JsObject]
            )
          )
        })
    } else {
      super.findByIdAndFillSecrets(id)(ec, env)
    }
  }

  override def latest()(implicit ec: ExecutionContext, env: Env): GlobalConfig = {
    val ref = configCache.get()
    if (ref == null) {
      // AWAIT: valid
      logger.error("this await should never be called!")
      Await.result(singleton(), 1.second) // WARN: await here should never be executed
    } else {
      ref
    }
  }

  override def latestSafe: Option[GlobalConfig] = Option(configCache.get())

  override def singleton()(implicit ec: ExecutionContext, env: Env): Future[GlobalConfig] = {
    val time = System.currentTimeMillis
    val ref  = configCache.get()

    @inline
    def actualCall() =
      findByIdAndFillSecrets("global").map(_.get).andThen { case Success(conf) =>
        lastConfigCache.set(time)
        configCache.set(conf)
      }

    if (ref == null) {
      lastConfigCache.set(time)
      if (logger.isDebugEnabled) logger.debug("Fetching GlobalConfig for the first time")
      actualCall()
    } else {
      if ((lastConfigCache.get() + 6000) < time) {
        lastConfigCache.set(time)
        actualCall()
      } else if ((lastConfigCache.get() + 5000) < time) {
        lastConfigCache.set(time)
        actualCall()
        FastFuture.successful(ref)
      } else {
        FastFuture.successful(ref)
      }
    }
  }

  override def set(value: GlobalConfig, pxMilliseconds: Option[Duration] = None)(implicit
      ec: ExecutionContext,
      env: Env
  ): Future[Boolean] = {
    super.set(value, pxMilliseconds)(ec, env).andThen { case Success(_) =>
      value.fillSecrets(GlobalConfig._fmt).map(gc => configCache.set(gc))
    }
  }

  override def fullImport(exportSource: JsObject)(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    val config             = GlobalConfig.fromJsons((exportSource \ "config").asOpt[JsObject].getOrElse(GlobalConfig().toJson))
    val admins             = (exportSource \ "admins").asOpt[JsArray].getOrElse(Json.arr())
    val simpleAdmins       = (exportSource \ "simpleAdmins").asOpt[JsArray].getOrElse(Json.arr())
    val serviceGroups      = (exportSource \ "serviceGroups").asOpt[JsArray].getOrElse(Json.arr())
    val apiKeys            = (exportSource \ "apiKeys").asOpt[JsArray].getOrElse(Json.arr())
    val serviceDescriptors = (exportSource \ "serviceDescriptors").asOpt[JsArray].getOrElse(Json.arr())
    val errorTemplates     = (exportSource \ "errorTemplates").asOpt[JsArray].getOrElse(Json.arr())
    val jwtVerifiers       = (exportSource \ "jwtVerifiers").asOpt[JsArray].getOrElse(Json.arr())
    val authConfigs        = (exportSource \ "authConfigs").asOpt[JsArray].getOrElse(Json.arr())
    val certificates       = (exportSource \ "certificates").asOpt[JsArray].getOrElse(Json.arr())
    val clientValidators   = (exportSource \ "clientValidators").asOpt[JsArray].getOrElse(Json.arr())
    val scripts            = (exportSource \ "scripts").asOpt[JsArray].getOrElse(Json.arr())
    val tcpServices        = (exportSource \ "tcpServices").asOpt[JsArray].getOrElse(Json.arr())
    val dataExporters      = (exportSource \ "dataExporters").asOpt[JsArray].getOrElse(Json.arr())
    val tenants            = (exportSource \ "tenants").asOpt[JsArray].getOrElse(Json.arr())
    val teams              = (exportSource \ "teams").asOpt[JsArray].getOrElse(Json.arr())
    val routes             = (exportSource \ "routes").asOpt[JsArray].getOrElse(Json.arr())
    val routeCompositions  = (exportSource \ "routeCompositions").asOpt[JsArray].getOrElse(Json.arr())
    val backends           = (exportSource \ "backends").asOpt[JsArray].getOrElse(Json.arr())

    for {
      _ <- redisCli
             .keys(s"${env.storageRoot}:*")
             .flatMap(keys => if (keys.nonEmpty) redisCli.del(keys: _*) else FastFuture.successful(0L))
      _ <- config.save()
      _ <-
        Future.sequence(
          admins.value.map(v => env.datastores.webAuthnAdminDataStore.registerUser(WebAuthnOtoroshiAdmin.reads(v).get))
        )
      _ <- Future.sequence(
             simpleAdmins.value.map(v =>
               env.datastores.simpleAdminDataStore.registerUser(SimpleOtoroshiAdmin.reads(v).get)
             )
           )
      _ <- Future.sequence(serviceGroups.value.map(ServiceGroup.fromJsons).map(_.save()))
      _ <- Future.sequence(apiKeys.value.map(ApiKey.fromJsons).map(_.save()))
      _ <- Future.sequence(serviceDescriptors.value.map(ServiceDescriptor.fromJsons).map(_.save()))
      _ <- Future.sequence(errorTemplates.value.map(ErrorTemplate.fromJsons).map(_.save()))
      _ <- Future.sequence(jwtVerifiers.value.map(GlobalJwtVerifier.fromJsons).map(_.save()))
      _ <- Future.sequence(authConfigs.value.map(AuthModuleConfig.fromJsons).map(_.save()))
      _ <- Future.sequence(certificates.value.map(Cert.fromJsons).map(_.save()))
      _ <- Future.sequence(clientValidators.value.map(ClientCertificateValidator.fromJsons).map(_.save()))
      _ <- Future.sequence(scripts.value.map(Script.fromJsons).map(_.save()))
      _ <- Future.sequence(tcpServices.value.map(TcpService.fromJsons).map(_.save()))
      _ <- Future.sequence(dataExporters.value.map(DataExporterConfig.fromJsons).map(_.save()))
      _ <- Future.sequence(tenants.value.map(Tenant.fromJsons).map(_.save()))
      _ <- Future.sequence(teams.value.map(Team.fromJsons).map(_.save()))
      _ <- Future.sequence(routes.value.map(NgRoute.fromJsons).map(_.save()))
      _ <- Future.sequence(routeCompositions.value.map(NgRouteComposition.fromJsons).map(_.save()))
      _ <- Future.sequence(backends.value.map(StoredNgBackend.fromJsons).map(_.save()))
    } yield ()
  }

  override def fullExport()(implicit ec: ExecutionContext, env: Env): Future[JsValue] = {
    // val appConfig =
    //   Json.parse(
    //     env.configuration
    //       .getOptional[play.api.Configuration]("app")
    //       .get
    //       .underlying
    //       .root()
    //       .render(ConfigRenderOptions.concise())
    //   )
    for {
      config            <- env.datastores.globalConfigDataStore.singleton()
      descs             <- env.datastores.serviceDescriptorDataStore.findAll()
      apikeys           <- env.datastores.apiKeyDataStore.findAll()
      groups            <- env.datastores.serviceGroupDataStore.findAll()
      tmplts            <- env.datastores.errorTemplateDataStore.findAll()
      calls             <- env.datastores.serviceDescriptorDataStore.globalCalls()
      dataIn            <- env.datastores.serviceDescriptorDataStore.globalDataIn()
      dataOut           <- env.datastores.serviceDescriptorDataStore.globalDataOut()
      admins            <- env.datastores.webAuthnAdminDataStore.findAll()
      simpleAdmins      <- env.datastores.simpleAdminDataStore.findAll()
      jwtVerifiers      <- env.datastores.globalJwtVerifierDataStore.findAll()
      authConfigs       <- env.datastores.authConfigsDataStore.findAll()
      certificates      <- env.datastores.certificatesDataStore.findAll()
      clientValidators  <- env.datastores.clientCertificateValidationDataStore.findAll()
      scripts           <- env.datastores.scriptDataStore.findAll()
      tcpServices       <- env.datastores.tcpServiceDataStore.findAll()
      dataExporters     <- env.datastores.dataExporterConfigDataStore.findAll()
      tenants           <- env.datastores.tenantDataStore.findAll()
      teams             <- env.datastores.teamDataStore.findAll()
      routes            <- env.datastores.routeDataStore.findAll()
      routeCompositions <- env.datastores.routeCompositionDataStore.findAll()
      backends          <- env.datastores.backendsDataStore.findAll()
    } yield OtoroshiExport(
      config,
      descs,
      apikeys,
      groups,
      tmplts,
      calls,
      dataIn,
      dataOut,
      admins,
      simpleAdmins,
      jwtVerifiers,
      authConfigs,
      certificates,
      clientValidators,
      scripts,
      tcpServices,
      dataExporters,
      tenants,
      teams,
      routes,
      routeCompositions,
      backends
    ).json
  }

  override def migrate()(implicit ec: ExecutionContext, env: Env): Future[Unit] = {
    val migrationKey = s"${_env.storageRoot}:migrations:globalconfig:before130"
    redisCli.get(key("global")).map(_.get).flatMap { configBS =>
      val json = Json.parse(configBS.utf8String)
      ((json \ "backofficeAuth0Config").asOpt[JsValue], (json \ "privateAppsAuth0Config").asOpt[JsValue]) match {
        case (Some(_), Some(_)) => {
          redisCli.get(migrationKey).flatMap {
            case Some(_) => FastFuture.successful(())
            case None    => {
              logger.info("OAuth config migration - Saving global configuration before migration")
              for {
                _                     <- redisCli.setBS(s"${_env.storageRoot}:migrations:globalconfig:before130", configBS)
                backofficeAuth0Config  = (json \ "backofficeAuth0Config").asOpt[JsValue].flatMap { config =>
                                           (
                                             (config \ "clientId").asOpt[String].filter(_.nonEmpty),
                                             (config \ "clientSecret").asOpt[String].filter(_.nonEmpty),
                                             (config \ "domain").asOpt[String].filter(_.nonEmpty),
                                             (config \ "callbackUrl").asOpt[String].filter(_.nonEmpty)
                                           ) match {
                                             case (
                                                   Some(clientId),
                                                   Some(clientSecret),
                                                   Some(domain),
                                                   Some(callbackUrl)
                                                 ) =>
                                               Some(Auth0Config(clientSecret, clientId, callbackUrl, domain))
                                             case _ => None
                                           }
                                         }
                privateAppsAuth0Config = (json \ "privateAppsAuth0Config").asOpt[JsValue].flatMap { config =>
                                           (
                                             (config \ "clientId").asOpt[String].filter(_.nonEmpty),
                                             (config \ "clientSecret").asOpt[String].filter(_.nonEmpty),
                                             (config \ "domain").asOpt[String].filter(_.nonEmpty),
                                             (config \ "callbackUrl").asOpt[String].filter(_.nonEmpty)
                                           ) match {
                                             case (
                                                   Some(clientId),
                                                   Some(clientSecret),
                                                   Some(domain),
                                                   Some(callbackUrl)
                                                 ) =>
                                               Some(Auth0Config(clientSecret, clientId, callbackUrl, domain))
                                             case _ => None
                                           }
                                         }
                _                      = logger.info("OAuth config migration - creating global oauth configuration for private apps")
                _                     <- privateAppsAuth0Config
                                           .map(c =>
                                             env.datastores.authConfigsDataStore.set(
                                               GenericOauth2ModuleConfig(
                                                 id = "confidential-apps",
                                                 name = "Confidential apps Auth0 provider",
                                                 desc = "Use to be the Auth0 global config. for private apps",
                                                 clientId = c.clientId,
                                                 clientSecret = c.secret,
                                                 tokenUrl = s"https://${c.domain}/oauth/token",
                                                 authorizeUrl = s"https://${c.domain}/authorize",
                                                 userInfoUrl = s"https://${c.domain}/userinfo",
                                                 loginUrl = s"https://${c.domain}/authorize",
                                                 logoutUrl = s"https://${c.domain}/logout",
                                                 callbackUrl = c.callbackURL,
                                                 tags = Seq.empty,
                                                 metadata = Map.empty,
                                                 sessionCookieValues = SessionCookieValues(),
                                                 clientSideSessionEnabled = true
                                               )
                                             )
                                           )
                                           .getOrElse(FastFuture.successful(()))
                _                      = logger.info("OAuth config migration - creating global oauth configuration for otoroshi backoffice")
                _                     <- backofficeAuth0Config
                                           .map(c =>
                                             env.datastores.authConfigsDataStore.set(
                                               GenericOauth2ModuleConfig(
                                                 id = "otoroshi-backoffice",
                                                 name = "Otoroshi backoffic Auth0 provider",
                                                 desc = "Use to be the Auth0 global config. for Otoroshi backoffice",
                                                 clientId = c.clientId,
                                                 clientSecret = c.secret,
                                                 tokenUrl = s"https://${c.domain}/oauth/token",
                                                 authorizeUrl = s"https://${c.domain}/authorize",
                                                 userInfoUrl = s"https://${c.domain}/userinfo",
                                                 loginUrl = s"https://${c.domain}/authorize",
                                                 logoutUrl = s"https://${c.domain}/logout",
                                                 callbackUrl = c.callbackURL,
                                                 tags = Seq.empty,
                                                 metadata = Map.empty,
                                                 sessionCookieValues = SessionCookieValues(),
                                                 clientSideSessionEnabled = true
                                               )
                                             )
                                           )
                                           .getOrElse(FastFuture.successful(()))
                _                      = logger.info("OAuth config migration - creating global oauth configuration for otoroshi backoffice")
                config                <- env.datastores.globalConfigDataStore.findById("global").map(_.get)
                configWithBackOffice   = backofficeAuth0Config
                                           .map(_ => config.copy(backOfficeAuthRef = Some("otoroshi-backoffice")))
                                           .getOrElse(config)
                _                     <- configWithBackOffice.save()
                _                      = logger.info("OAuth config migration - migration done !")
              } yield ()
            }
          }
        }
        case _                  => FastFuture.successful(())
      }
    }
  }
}
