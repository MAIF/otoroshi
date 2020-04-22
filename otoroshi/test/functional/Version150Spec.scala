package functional

import akka.actor.ActorSystem
import auth.{AuthModuleConfig, BasicAuthModuleConfig}
import com.typesafe.config.ConfigFactory
import models.{ApiKey, GlobalJwtVerifier, ServiceDescriptor, ServiceGroup}
import otoroshi.script.Script
import otoroshi.tcp.TcpService
import play.api.Configuration
import play.api.libs.json.{JsArray, JsValue, Json}
import security.IdGenerator
import otoroshi.utils.syntax.implicits._
import ssl.{Cert, ClientCertificateValidator}

import scala.concurrent.duration._
import scala.concurrent.Await

class ServiceGroupApiSpec(name: String, configurationSpec: => Configuration)
  extends OtoroshiSpec with ApiTester[ServiceGroup] {

  implicit val system  = ActorSystem("otoroshi-test")
  implicit val env     = otoroshiComponents.env

  import system.dispatcher

  override def getTestConfiguration(configuration: Configuration) = Configuration(
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

  override def singleEntity(): ServiceGroup = ServiceGroup(
    id = IdGenerator.token(64),
    name = "test-group",
    description = "group for test"
  )
  override def entityName: String = "ServiceGroup"
  override def route(): String = "/api/groups"
  override def readEntityFromJson(json: JsValue): ServiceGroup = ServiceGroup._fmt.reads(json).get
  override def writeEntityToJson(entity: ServiceGroup): JsValue = ServiceGroup._fmt.writes(entity)
  override def updateEntity(entity: ServiceGroup): ServiceGroup = entity.copy(name = entity.name + " - updated")
  override def patchEntity(entity: ServiceGroup): (ServiceGroup, JsArray) = (entity.copy(name = entity.name + " - patched"), Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched"))))
  override def extractId(entity: ServiceGroup): String = entity.id
  override def testingBulk: Boolean = true
}

class TcpServiceApiSpec(name: String, configurationSpec: => Configuration)
  extends OtoroshiSpec with ApiTester[TcpService] {

  implicit val system  = ActorSystem("otoroshi-test")
  implicit val env     = otoroshiComponents.env

  import system.dispatcher

  override def getTestConfiguration(configuration: Configuration) = Configuration(
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

  override def singleEntity(): TcpService = env.datastores.tcpServiceDataStore.template
  override def entityName: String = "TcpService"
  override def route(): String = "/api/tcp/services"
  override def readEntityFromJson(json: JsValue): TcpService = TcpService.fmt.reads(json).get
  override def writeEntityToJson(entity: TcpService): JsValue = TcpService.fmt.writes(entity)
  override def updateEntity(entity: TcpService): TcpService = entity.copy(name = entity.name + " - updated")
  override def patchEntity(entity: TcpService): (TcpService, JsArray) = (entity.copy(name = entity.name + " - patched"), Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched"))))
  override def extractId(entity: TcpService): String = entity.id
  override def testingBulk: Boolean = true
}

class ScriptApiSpec(name: String, configurationSpec: => Configuration)
  extends OtoroshiSpec with ApiTester[Script] {

  implicit val system  = ActorSystem("otoroshi-test")
  implicit val env     = otoroshiComponents.env

  import system.dispatcher

  override def getTestConfiguration(configuration: Configuration) = Configuration(
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

  override def singleEntity(): Script = env.datastores.scriptDataStore.template
  override def entityName: String = "Script"
  override def route(): String = "/api/scripts"
  override def readEntityFromJson(json: JsValue): Script = Script._fmt.reads(json).get
  override def writeEntityToJson(entity: Script): JsValue = Script._fmt.writes(entity)
  override def updateEntity(entity: Script): Script = entity.copy(name = entity.name + " - updated")
  override def patchEntity(entity: Script): (Script, JsArray) = (entity.copy(name = entity.name + " - patched"), Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched"))))
  override def extractId(entity: Script): String = entity.id
  override def testingBulk: Boolean = true
}

class AuthModuleConfigApiSpec(name: String, configurationSpec: => Configuration)
  extends OtoroshiSpec with ApiTester[AuthModuleConfig] {

  implicit val system  = ActorSystem("otoroshi-test")
  implicit val env     = otoroshiComponents.env

  import system.dispatcher

  override def getTestConfiguration(configuration: Configuration) = Configuration(
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

  override def singleEntity(): AuthModuleConfig = env.datastores.authConfigsDataStore.template("basic".some)
  override def entityName: String = "AuthModuleConfig"
  override def route(): String = "/api/auths"
  override def readEntityFromJson(json: JsValue): AuthModuleConfig = AuthModuleConfig._fmt.reads(json).get
  override def writeEntityToJson(entity: AuthModuleConfig): JsValue = AuthModuleConfig._fmt.writes(entity)
  override def updateEntity(entity: AuthModuleConfig): AuthModuleConfig = entity.asInstanceOf[BasicAuthModuleConfig].copy(name = entity.name + " - updated")
  override def patchEntity(entity: AuthModuleConfig): (AuthModuleConfig, JsArray) = (entity.asInstanceOf[BasicAuthModuleConfig].copy(name = entity.name + " - patched"), Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched"))))
  override def extractId(entity: AuthModuleConfig): String = entity.id
  override def testingBulk: Boolean = true
}

class ClientValidatorApiSpec(name: String, configurationSpec: => Configuration)
  extends OtoroshiSpec with ApiTester[ClientCertificateValidator] {

  implicit val system  = ActorSystem("otoroshi-test")
  implicit val env     = otoroshiComponents.env

  import system.dispatcher

  override def getTestConfiguration(configuration: Configuration) = Configuration(
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

  override def singleEntity(): ClientCertificateValidator = env.datastores.clientCertificateValidationDataStore.template
  override def entityName: String = "ClientCertificateValidator"
  override def route(): String = "/api/client-validators"
  override def readEntityFromJson(json: JsValue): ClientCertificateValidator = ClientCertificateValidator.fmt.reads(json).get
  override def writeEntityToJson(entity: ClientCertificateValidator): JsValue = ClientCertificateValidator.fmt.writes(entity)
  override def updateEntity(entity: ClientCertificateValidator): ClientCertificateValidator = entity.copy(name = entity.name + " - updated")
  override def patchEntity(entity: ClientCertificateValidator): (ClientCertificateValidator, JsArray) = (entity.copy(name = entity.name + " - patched"), Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched"))))
  override def extractId(entity: ClientCertificateValidator): String = entity.id
  override def testingBulk: Boolean = true
}

class JWTVerifierApiSpec(name: String, configurationSpec: => Configuration)
  extends OtoroshiSpec with ApiTester[GlobalJwtVerifier] {

  implicit val system  = ActorSystem("otoroshi-test")
  implicit val env     = otoroshiComponents.env

  import system.dispatcher

  override def getTestConfiguration(configuration: Configuration) = Configuration(
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

  override def singleEntity(): GlobalJwtVerifier = env.datastores.globalJwtVerifierDataStore.template
  override def entityName: String = "GlobalJwtVerifier"
  override def route(): String = "/api/verifiers"
  override def readEntityFromJson(json: JsValue): GlobalJwtVerifier = GlobalJwtVerifier._fmt.reads(json).get
  override def writeEntityToJson(entity: GlobalJwtVerifier): JsValue = GlobalJwtVerifier._fmt.writes(entity)
  override def updateEntity(entity: GlobalJwtVerifier): GlobalJwtVerifier = entity.copy(name = entity.name + " - updated")
  override def patchEntity(entity: GlobalJwtVerifier): (GlobalJwtVerifier, JsArray) = (entity.copy(name = entity.name + " - patched"), Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched"))))
  override def extractId(entity: GlobalJwtVerifier): String = entity.id
  override def testingBulk: Boolean = true
}

class CertificateApiSpec(name: String, configurationSpec: => Configuration)
  extends OtoroshiSpec with ApiTester[Cert] {

  implicit val system  = ActorSystem("otoroshi-test")
  implicit val env     = otoroshiComponents.env

  import system.dispatcher

  override def getTestConfiguration(configuration: Configuration) = Configuration(
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

  override def singleEntity(): Cert = Await.result(env.datastores.certificatesDataStore.template(ec, env), 10.seconds)
  override def entityName: String = "Cert"
  override def route(): String = "/api/certificates"
  override def readEntityFromJson(json: JsValue): Cert = Cert._fmt.reads(json).get
  override def writeEntityToJson(entity: Cert): JsValue = Cert._fmt.writes(entity)
  override def updateEntity(entity: Cert): Cert = entity.copy(name = entity.name + " - updated")
  override def patchEntity(entity: Cert): (Cert, JsArray) = (entity.copy(name = entity.name + " - patched"), Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched"))))
  override def extractId(entity: Cert): String = entity.id
  override def testingBulk: Boolean = true
}

class ServicesApiSpec(name: String, configurationSpec: => Configuration)
  extends OtoroshiSpec with ApiTester[ServiceDescriptor] {

  implicit val system  = ActorSystem("otoroshi-test")
  implicit val env     = otoroshiComponents.env

  import system.dispatcher

  override def getTestConfiguration(configuration: Configuration) = Configuration(
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

  override def singleEntity(): ServiceDescriptor = env.datastores.serviceDescriptorDataStore.template()(env)
  override def entityName: String = "ServiceDescriptor"
  override def route(): String = "/api/services"
  override def readEntityFromJson(json: JsValue): ServiceDescriptor = ServiceDescriptor._fmt.reads(json).get
  override def writeEntityToJson(entity: ServiceDescriptor): JsValue = ServiceDescriptor._fmt.writes(entity)
  override def updateEntity(entity: ServiceDescriptor): ServiceDescriptor = entity.copy(name = entity.name + " - updated")
  override def patchEntity(entity: ServiceDescriptor): (ServiceDescriptor, JsArray) = (entity.copy(name = entity.name + " - patched"), Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched"))))
  override def extractId(entity: ServiceDescriptor): String = entity.id
  override def testingBulk: Boolean = true
}

class ApikeyGroupApiSpec(name: String, configurationSpec: => Configuration)
  extends OtoroshiSpec with ApiTester[ApiKey] {

  implicit val system  = ActorSystem("otoroshi-test")
  implicit val env     = otoroshiComponents.env

  import system.dispatcher

  override def getTestConfiguration(configuration: Configuration) = Configuration(
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

  override def singleEntity(): ApiKey = env.datastores.apiKeyDataStore.initiateNewApiKey("default")
  override def entityName: String = "ApiKey"
  override def route(): String = "/api/groups/default/apikeys"
  override def readEntityFromJson(json: JsValue): ApiKey = ApiKey._fmt.reads(json).get
  override def writeEntityToJson(entity: ApiKey): JsValue = ApiKey._fmt.writes(entity)
  override def updateEntity(entity: ApiKey): ApiKey = entity.copy(clientName = entity.clientName + " - updated")
  override def patchEntity(entity: ApiKey): (ApiKey, JsArray) = (entity.copy(clientName = entity.clientName + " - patched"), Json.arr(Json.obj("op" -> "replace", "path" -> "/clientName", "value" -> (entity.clientName + " - patched"))))
  override def extractId(entity: ApiKey): String = entity.clientId
  override def testingBulk: Boolean = false
}

class ApikeyServiceApiSpec(name: String, configurationSpec: => Configuration)
  extends OtoroshiSpec with ApiTester[ApiKey] {

  implicit val system  = ActorSystem("otoroshi-test")
  implicit val env     = otoroshiComponents.env

  import system.dispatcher

  override def getTestConfiguration(configuration: Configuration) = Configuration(
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

  override def singleEntity(): ApiKey = env.datastores.apiKeyDataStore.initiateNewApiKey("admin-api-group")
  override def entityName: String = "ApiKey"
  override def route(): String = "/api/services/admin-api-service/apikeys"
  override def readEntityFromJson(json: JsValue): ApiKey = ApiKey._fmt.reads(json).get
  override def writeEntityToJson(entity: ApiKey): JsValue = ApiKey._fmt.writes(entity)
  override def updateEntity(entity: ApiKey): ApiKey = entity.copy(clientName = entity.clientName + " - updated")
  override def patchEntity(entity: ApiKey): (ApiKey, JsArray) = (entity.copy(clientName = entity.clientName + " - patched"), Json.arr(Json.obj("op" -> "replace", "path" -> "/clientName", "value" -> (entity.clientName + " - patched"))))
  override def extractId(entity: ApiKey): String = entity.clientId
  override def testingBulk: Boolean = false
}

class ApikeyApiSpec(name: String, configurationSpec: => Configuration)
  extends OtoroshiSpec with ApiTester[ApiKey] {

  implicit val system  = ActorSystem("otoroshi-test")
  implicit val env     = otoroshiComponents.env

  import system.dispatcher

  override def getTestConfiguration(configuration: Configuration) = Configuration(
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

  override def singleEntity(): ApiKey = env.datastores.apiKeyDataStore.initiateNewApiKey("default")
  override def entityName: String = "ApiKey"
  override def route(): String = "/api/apikeys"
  override def readEntityFromJson(json: JsValue): ApiKey = ApiKey._fmt.reads(json).get
  override def writeEntityToJson(entity: ApiKey): JsValue = ApiKey._fmt.writes(entity)
  override def updateEntity(entity: ApiKey): ApiKey = entity.copy(clientName = entity.clientName + " - updated")
  override def patchEntity(entity: ApiKey): (ApiKey, JsArray) = (entity.copy(clientName = entity.clientName + " - patched"), Json.arr(Json.obj("op" -> "replace", "path" -> "/clientName", "value" -> (entity.clientName + " - patched"))))
  override def extractId(entity: ApiKey): String = entity.clientId
  override def testingBulk: Boolean = true
}