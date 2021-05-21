package functional

import akka.actor.ActorSystem
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.google.common.base.Charsets
import com.typesafe.config.ConfigFactory
import otoroshi.auth.{AuthModuleConfig, BasicAuthModuleConfig}
import otoroshi.models._
import otoroshi.script.Script
import otoroshi.security.IdGenerator
import otoroshi.ssl.{Cert, ClientCertificateValidator}
import otoroshi.tcp.TcpService
import otoroshi.utils.http.MtlsConfig
import otoroshi.utils.syntax.implicits._
import play.api.Configuration
import play.api.libs.json.{JsArray, JsValue, Json}
import play.api.libs.ws.WSAuthScheme

import java.util.Base64
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class ServiceGroupApiSpec(name: String, configurationSpec: => Configuration)
    extends OtoroshiSpec
    with ApiTester[ServiceGroup] {

  implicit val system = ActorSystem("otoroshi-test")
  implicit val env    = otoroshiComponents.env

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                      |{
                      |  otoroshi.cache.enabled = false
                      |  otoroshi.cache.ttl = 1
                      |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  startOtoroshi()

  s"[$name] Otoroshi service group API" should {
    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }
    "works" in {
      val result = testApi.futureValue
      result.works mustBe true
    }
    "shutdown" in {
      stopAll()
      system.terminate()
    }
  }

  override def singleEntity(): ServiceGroup                               =
    ServiceGroup(
      id = IdGenerator.token(64),
      name = "test-group",
      description = "group for test"
    )
  override def entityName: String                                         = "ServiceGroup"
  override def route(): String                                            = "/api/groups"
  override def readEntityFromJson(json: JsValue): ServiceGroup            = ServiceGroup._fmt.reads(json).get
  override def writeEntityToJson(entity: ServiceGroup): JsValue           = ServiceGroup._fmt.writes(entity)
  override def updateEntity(entity: ServiceGroup): ServiceGroup           = entity.copy(name = entity.name + " - updated")
  override def patchEntity(entity: ServiceGroup): (ServiceGroup, JsArray) =
    (
      entity.copy(name = entity.name + " - patched"),
      Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched")))
    )
  override def extractId(entity: ServiceGroup): String                    = entity.id
  override def testingBulk: Boolean                                       = true
}

class TcpServiceApiSpec(name: String, configurationSpec: => Configuration)
    extends OtoroshiSpec
    with ApiTester[TcpService] {

  implicit val system = ActorSystem("otoroshi-test")
  implicit val env    = otoroshiComponents.env

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                      |{
                      |  otoroshi.cache.enabled = false
                      |  otoroshi.cache.ttl = 1
                      |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  startOtoroshi()

  s"[$name] Otoroshi tcp service API" should {
    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }
    "works" in {
      val result = testApi.futureValue
      result.works mustBe true
    }
    "shutdown" in {
      stopAll()
      system.terminate()
    }
  }

  override def singleEntity(): TcpService                             = env.datastores.tcpServiceDataStore.template(env)
  override def entityName: String                                     = "TcpService"
  override def route(): String                                        = "/api/tcp/services"
  override def readEntityFromJson(json: JsValue): TcpService          = TcpService.fmt.reads(json).get
  override def writeEntityToJson(entity: TcpService): JsValue         = TcpService.fmt.writes(entity)
  override def updateEntity(entity: TcpService): TcpService           = entity.copy(name = entity.name + " - updated")
  override def patchEntity(entity: TcpService): (TcpService, JsArray) =
    (
      entity.copy(name = entity.name + " - patched"),
      Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched")))
    )
  override def extractId(entity: TcpService): String                  = entity.id
  override def testingBulk: Boolean                                   = true
}

class ScriptApiSpec(name: String, configurationSpec: => Configuration) extends OtoroshiSpec with ApiTester[Script] {

  implicit val system = ActorSystem("otoroshi-test")
  implicit val env    = otoroshiComponents.env

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                      |{
                      |  otoroshi.cache.enabled = false
                      |  otoroshi.cache.ttl = 1
                      |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  startOtoroshi()

  s"[$name] Otoroshi script API" should {
    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }
    "works" in {
      val result = testApi.futureValue
      result.works mustBe true
    }
    "shutdown" in {
      stopAll()
      system.terminate()
    }
  }

  override def singleEntity(): Script                         = env.datastores.scriptDataStore.template(env)
  override def entityName: String                             = "Script"
  override def route(): String                                = "/api/scripts"
  override def readEntityFromJson(json: JsValue): Script      = Script._fmt.reads(json).get
  override def writeEntityToJson(entity: Script): JsValue     = Script._fmt.writes(entity)
  override def updateEntity(entity: Script): Script           = entity.copy(name = entity.name + " - updated")
  override def patchEntity(entity: Script): (Script, JsArray) =
    (
      entity.copy(name = entity.name + " - patched"),
      Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched")))
    )
  override def extractId(entity: Script): String              = entity.id
  override def testingBulk: Boolean                           = true
}

class AuthModuleConfigApiSpec(name: String, configurationSpec: => Configuration)
    extends OtoroshiSpec
    with ApiTester[AuthModuleConfig] {

  implicit val system = ActorSystem("otoroshi-test")
  implicit val env    = otoroshiComponents.env

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                      |{
                      |  otoroshi.cache.enabled = false
                      |  otoroshi.cache.ttl = 1
                      |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  startOtoroshi()

  s"[$name] Otoroshi auth. module config. API" should {
    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }
    "works" in {
      val result = testApi.futureValue
      result.works mustBe true
    }
    "shutdown" in {
      stopAll()
      system.terminate()
    }
  }

  override def singleEntity(): AuthModuleConfig                                   = env.datastores.authConfigsDataStore.template("basic".some, env)
  override def entityName: String                                                 = "AuthModuleConfig"
  override def route(): String                                                    = "/api/auths"
  override def readEntityFromJson(json: JsValue): AuthModuleConfig                = AuthModuleConfig._fmt.reads(json).get
  override def writeEntityToJson(entity: AuthModuleConfig): JsValue               = AuthModuleConfig._fmt.writes(entity)
  override def updateEntity(entity: AuthModuleConfig): AuthModuleConfig           =
    entity.asInstanceOf[BasicAuthModuleConfig].copy(name = entity.name + " - updated")
  override def patchEntity(entity: AuthModuleConfig): (AuthModuleConfig, JsArray) =
    (
      entity.asInstanceOf[BasicAuthModuleConfig].copy(name = entity.name + " - patched"),
      Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched")))
    )
  override def extractId(entity: AuthModuleConfig): String                        = entity.id
  override def testingBulk: Boolean                                               = true
}

class ClientValidatorApiSpec(name: String, configurationSpec: => Configuration)
    extends OtoroshiSpec
    with ApiTester[ClientCertificateValidator] {

  implicit val system = ActorSystem("otoroshi-test")
  implicit val env    = otoroshiComponents.env

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                      |{
                      |  otoroshi.cache.enabled = false
                      |  otoroshi.cache.ttl = 1
                      |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  startOtoroshi()

  s"[$name] Otoroshi cient validator API" should {
    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }
    "works" in {
      val result = testApi.futureValue
      result.works mustBe true
    }
    "shutdown" in {
      stopAll()
      system.terminate()
    }
  }

  override def singleEntity(): ClientCertificateValidator                                             = env.datastores.clientCertificateValidationDataStore.template
  override def entityName: String                                                                     = "ClientCertificateValidator"
  override def route(): String                                                                        = "/api/client-validators"
  override def readEntityFromJson(json: JsValue): ClientCertificateValidator                          =
    ClientCertificateValidator.fmt.reads(json).get
  override def writeEntityToJson(entity: ClientCertificateValidator): JsValue                         =
    ClientCertificateValidator.fmt.writes(entity)
  override def updateEntity(entity: ClientCertificateValidator): ClientCertificateValidator           =
    entity.copy(name = entity.name + " - updated")
  override def patchEntity(entity: ClientCertificateValidator): (ClientCertificateValidator, JsArray) =
    (
      entity.copy(name = entity.name + " - patched"),
      Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched")))
    )
  override def extractId(entity: ClientCertificateValidator): String                                  = entity.id
  override def testingBulk: Boolean                                                                   = true
}

class JWTVerifierApiSpec(name: String, configurationSpec: => Configuration)
    extends OtoroshiSpec
    with ApiTester[GlobalJwtVerifier] {

  implicit val system = ActorSystem("otoroshi-test")
  implicit val env    = otoroshiComponents.env

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                      |{
                      |  otoroshi.cache.enabled = false
                      |  otoroshi.cache.ttl = 1
                      |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  startOtoroshi()

  s"[$name] Otoroshi jwt verifier API" should {
    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }
    "works" in {
      val result = testApi.futureValue
      result.works mustBe true
    }
    "shutdown" in {
      stopAll()
      system.terminate()
    }
  }

  override def singleEntity(): GlobalJwtVerifier                                    = env.datastores.globalJwtVerifierDataStore.template(env)
  override def entityName: String                                                   = "GlobalJwtVerifier"
  override def route(): String                                                      = "/api/verifiers"
  override def readEntityFromJson(json: JsValue): GlobalJwtVerifier                 = GlobalJwtVerifier._fmt.reads(json).get
  override def writeEntityToJson(entity: GlobalJwtVerifier): JsValue                = GlobalJwtVerifier._fmt.writes(entity)
  override def updateEntity(entity: GlobalJwtVerifier): GlobalJwtVerifier           =
    entity.copy(name = entity.name + " - updated")
  override def patchEntity(entity: GlobalJwtVerifier): (GlobalJwtVerifier, JsArray) =
    (
      entity.copy(name = entity.name + " - patched"),
      Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched")))
    )
  override def extractId(entity: GlobalJwtVerifier): String                         = entity.id
  override def testingBulk: Boolean                                                 = true
}

class CertificateApiSpec(name: String, configurationSpec: => Configuration) extends OtoroshiSpec with ApiTester[Cert] {

  implicit val system = ActorSystem("otoroshi-test")
  implicit val env    = otoroshiComponents.env

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                      |{
                      |  otoroshi.cache.enabled = false
                      |  otoroshi.cache.ttl = 1
                      |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  startOtoroshi()

  s"[$name] Otoroshi certificates API" should {
    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }
    "works" in {
      val result = testApi.futureValue
      result.works mustBe true
    }
    "shutdown" in {
      stopAll()
      system.terminate()
    }
  }

  override def singleEntity(): Cert                       = Await.result(env.datastores.certificatesDataStore.template(ec, env), 10.seconds)
  override def entityName: String                         = "Cert"
  override def route(): String                            = "/api/certificates"
  override def readEntityFromJson(json: JsValue): Cert    = Cert._fmt.reads(json).get
  override def writeEntityToJson(entity: Cert): JsValue   = Cert._fmt.writes(entity)
  override def updateEntity(entity: Cert): Cert           = entity.copy(name = entity.name + " - updated")
  override def patchEntity(entity: Cert): (Cert, JsArray) =
    (
      entity.copy(name = entity.name + " - patched"),
      Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched")))
    )
  override def extractId(entity: Cert): String            = entity.id
  override def testingBulk: Boolean                       = true
}

class ServicesApiSpec(name: String, configurationSpec: => Configuration)
    extends OtoroshiSpec
    with ApiTester[ServiceDescriptor] {

  implicit val system = ActorSystem("otoroshi-test")
  implicit val env    = otoroshiComponents.env

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                      |{
                      |  otoroshi.cache.enabled = false
                      |  otoroshi.cache.ttl = 1
                      |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  startOtoroshi()

  s"[$name] Otoroshi service descriptor API" should {
    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }
    "works" in {
      val result = testApi.futureValue
      result.works mustBe true
    }
    "shutdown" in {
      stopAll()
      system.terminate()
    }
  }

  override def singleEntity(): ServiceDescriptor                                    = env.datastores.serviceDescriptorDataStore.template(env)
  override def entityName: String                                                   = "ServiceDescriptor"
  override def route(): String                                                      = "/api/services"
  override def readEntityFromJson(json: JsValue): ServiceDescriptor                 = ServiceDescriptor._fmt.reads(json).get
  override def writeEntityToJson(entity: ServiceDescriptor): JsValue                = ServiceDescriptor._fmt.writes(entity)
  override def updateEntity(entity: ServiceDescriptor): ServiceDescriptor           =
    entity.copy(name = entity.name + " - updated")
  override def patchEntity(entity: ServiceDescriptor): (ServiceDescriptor, JsArray) =
    (
      entity.copy(name = entity.name + " - patched"),
      Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched")))
    )
  override def extractId(entity: ServiceDescriptor): String                         = entity.id
  override def testingBulk: Boolean                                                 = true
}

class ApikeyGroupApiSpec(name: String, configurationSpec: => Configuration)
    extends OtoroshiSpec
    with ApiTester[ApiKey] {

  implicit val system = ActorSystem("otoroshi-test")
  implicit val env    = otoroshiComponents.env

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                      |{
                      |  otoroshi.cache.enabled = false
                      |  otoroshi.cache.ttl = 1
                      |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  startOtoroshi()

  s"[$name] Otoroshi Apikey from group API" should {
    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }
    "works" in {
      val result = testApi.futureValue
      result.works mustBe true
    }
    "shutdown" in {
      stopAll()
      system.terminate()
    }
  }

  override def singleEntity(): ApiKey                         = env.datastores.apiKeyDataStore.initiateNewApiKey("default", env)
  override def entityName: String                             = "ApiKey"
  override def route(): String                                = "/api/groups/default/apikeys"
  override def readEntityFromJson(json: JsValue): ApiKey      = ApiKey._fmt.reads(json).get
  override def writeEntityToJson(entity: ApiKey): JsValue     = ApiKey._fmt.writes(entity)
  override def updateEntity(entity: ApiKey): ApiKey           = entity.copy(clientName = entity.clientName + " - updated")
  override def patchEntity(entity: ApiKey): (ApiKey, JsArray) =
    (
      entity.copy(clientName = entity.clientName + " - patched"),
      Json.arr(Json.obj("op" -> "replace", "path" -> "/clientName", "value" -> (entity.clientName + " - patched")))
    )
  override def extractId(entity: ApiKey): String              = entity.clientId
  override def testingBulk: Boolean                           = false
}

class ApikeyServiceApiSpec(name: String, configurationSpec: => Configuration)
    extends OtoroshiSpec
    with ApiTester[ApiKey] {

  implicit val system = ActorSystem("otoroshi-test")
  implicit val env    = otoroshiComponents.env

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                      |{
                      |  otoroshi.cache.enabled = false
                      |  otoroshi.cache.ttl = 1
                      |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  startOtoroshi()

  s"[$name] Otoroshi Apikey from service API" should {
    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }
    "works" in {
      val result = testApi.futureValue
      result.works mustBe true
    }
    "shutdown" in {
      stopAll()
      system.terminate()
    }
  }

  override def singleEntity(): ApiKey                         =
    env.datastores.apiKeyDataStore
      .initiateNewApiKey("admin-api-group", env)
      .copy(authorizedEntities = Seq(ServiceDescriptorIdentifier("admin-api-service")))
  override def entityName: String                             = "ApiKey"
  override def route(): String                                = "/api/services/admin-api-service/apikeys"
  override def readEntityFromJson(json: JsValue): ApiKey      = ApiKey._fmt.reads(json).get
  override def writeEntityToJson(entity: ApiKey): JsValue     = ApiKey._fmt.writes(entity)
  override def updateEntity(entity: ApiKey): ApiKey           = entity.copy(clientName = entity.clientName + " - updated")
  override def patchEntity(entity: ApiKey): (ApiKey, JsArray) =
    (
      entity.copy(clientName = entity.clientName + " - patched"),
      Json.arr(Json.obj("op" -> "replace", "path" -> "/clientName", "value" -> (entity.clientName + " - patched")))
    )
  override def extractId(entity: ApiKey): String              = entity.clientId
  override def testingBulk: Boolean                           = false
}

class ApikeyApiSpec(name: String, configurationSpec: => Configuration) extends OtoroshiSpec with ApiTester[ApiKey] {

  implicit val system = ActorSystem("otoroshi-test")
  implicit val env    = otoroshiComponents.env

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                      |{
                      |  otoroshi.cache.enabled = false
                      |  otoroshi.cache.ttl = 1
                      |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  startOtoroshi()

  s"[$name] Otoroshi Apikey API" should {
    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }
    "works" in {
      val result = testApi.futureValue
      result.works mustBe true
    }
    "shutdown" in {
      stopAll()
      system.terminate()
    }
  }

  override def singleEntity(): ApiKey                         = env.datastores.apiKeyDataStore.initiateNewApiKey("default", env)
  override def entityName: String                             = "ApiKey"
  override def route(): String                                = "/api/apikeys"
  override def readEntityFromJson(json: JsValue): ApiKey      = ApiKey._fmt.reads(json).get
  override def writeEntityToJson(entity: ApiKey): JsValue     = ApiKey._fmt.writes(entity)
  override def updateEntity(entity: ApiKey): ApiKey           = entity.copy(clientName = entity.clientName + " - updated")
  override def patchEntity(entity: ApiKey): (ApiKey, JsArray) =
    (
      entity.copy(clientName = entity.clientName + " - patched"),
      Json.arr(Json.obj("op" -> "replace", "path" -> "/clientName", "value" -> (entity.clientName + " - patched")))
    )
  override def extractId(entity: ApiKey): String              = entity.clientId
  override def testingBulk: Boolean                           = true
}

class TeamsSpec(name: String, configurationSpec: => Configuration) extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                      |{
                      |  otoroshi.cache.enabled = false
                      |  otoroshi.cache.ttl = 1
                      |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

  val adminUser       = BackOfficeUser(
    randomId = "admin@otoroshi.io",
    name = "admin@otoroshi.io",
    email = "admin@otoroshi.io",
    profile = Json.obj(),
    authConfigId = "basic",
    simpleLogin = true,
    tags = Seq.empty,
    metadata = Map.empty,
    rights = UserRights(
      Seq(
        UserRight(
          TenantAccess("*"),
          Seq(TeamAccess("*"))
        )
      )
    )
  )
  val tenantAdminUser = BackOfficeUser(
    randomId = "tenantadmin@otoroshi.io",
    name = "tenantadmin@otoroshi.io",
    email = "tenantadmin@otoroshi.io",
    profile = Json.obj(),
    authConfigId = "basic",
    simpleLogin = true,
    tags = Seq.empty,
    metadata = Map.empty,
    rights = UserRights(
      Seq(
        UserRight(
          TenantAccess("test-teams"),
          Seq(TeamAccess("*"))
        )
      )
    )
  )

  val team1User     = BackOfficeUser(
    randomId = "team1@otoroshi.io",
    name = "team1@otoroshi.io",
    email = "team1@otoroshi.io",
    profile = Json.obj(),
    authConfigId = "basic",
    simpleLogin = true,
    tags = Seq.empty,
    metadata = Map.empty,
    rights = UserRights(
      Seq(
        UserRight(
          TenantAccess("test-teams"),
          Seq(TeamAccess("team1"))
        )
      )
    )
  )
  val team2User     = BackOfficeUser(
    randomId = "team2@otoroshi.io",
    name = "team2@otoroshi.io",
    email = "team2@otoroshi.io",
    profile = Json.obj(),
    authConfigId = "basic",
    simpleLogin = true,
    tags = Seq.empty,
    metadata = Map.empty,
    rights = UserRights(
      Seq(
        UserRight(
          TenantAccess("test-teams"),
          Seq(TeamAccess("team2"))
        )
      )
    )
  )
  val team1and2User = BackOfficeUser(
    randomId = "team1and2@otoroshi.io",
    name = "team1and2@otoroshi.io",
    email = "team1and2@otoroshi.io",
    profile = Json.obj(),
    authConfigId = "basic",
    simpleLogin = true,
    tags = Seq.empty,
    metadata = Map.empty,
    rights = UserRights(
      Seq(
        UserRight(
          TenantAccess("test-teams"),
          Seq(TeamAccess("team1"), TeamAccess("team2"))
        )
      )
    )
  )

  def service(team: String): ServiceDescriptor = {
    ServiceDescriptor(
      id = IdGenerator.token(64),
      name = IdGenerator.token(64),
      groups = Seq("default"),
      env = "prod",
      domain = "oto.tools",
      subdomain = IdGenerator.token(64),
      targets = Seq(
        Target(
          host = "changeme.cleverapps.io",
          mtlsConfig = MtlsConfig()
        )
      ),
      location = otoroshi.models.EntityLocation(
        tenant = TenantId("test-teams"),
        teams = Seq(TeamId(team))
      )
    )
  }

  startOtoroshi()

  def call(method: String, path: String, tenant: TenantId, user: BackOfficeUser): Future[JsValue] = {
    ws.url(s"http://localhost:${port}${path}")
      .withHttpHeaders(
        "Host"                     -> "otoroshi-api.oto.tools",
        "Accept"                   -> "application/json",
        "Otoroshi-Admin-Profile"   -> Base64.getUrlEncoder.encodeToString(
          Json.stringify(user.profile).getBytes(Charsets.UTF_8)
        ),
        "Otoroshi-Tenant"          -> tenant.value,
        "Otoroshi-BackOffice-User" -> JWT
          .create()
          .withClaim("user", Json.stringify(user.toJson))
          .sign(Algorithm.HMAC512("admin-api-apikey-secret"))
      )
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .withMethod(method)
      .execute()
      .map(_.json)
  }

  s"[$name] Otoroshi Teams filtering" should {
    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }
    "register services in different teams" in {
      val service1 = service("team0")
      val service2 = service("team1")
      val service3 = service("team2")
      createOtoroshiService(service1).futureValue
      createOtoroshiService(service2).futureValue
      createOtoroshiService(service3).futureValue
    }
    "check services number with different users" in {
      call("GET", "/api/services", TenantId("test-teams"), adminUser).futureValue.as[JsArray].value.size mustBe 4
      call("GET", "/api/services", TenantId("test-teams"), tenantAdminUser).futureValue.as[JsArray].value.size mustBe 3
      call("GET", "/api/services", TenantId("test-teams"), team1User).futureValue.as[JsArray].value.size mustBe 1
      call("GET", "/api/services", TenantId("test-teams"), team2User).futureValue.as[JsArray].value.size mustBe 1
      call("GET", "/api/services", TenantId("test-teams"), team1and2User).futureValue.as[JsArray].value.size mustBe 2
    }
    "shutdown" in {
      stopAll()
    }
  }
}
