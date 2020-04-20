package functional

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import models.ServiceGroup
import play.api.Configuration
import play.api.libs.json.{JsArray, JsValue, Json}
import security.IdGenerator

class Version150Spec(name: String, configurationSpec: => Configuration)
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
    }
  }

  override def singleEntity(): ServiceGroup = ServiceGroup(
    id = IdGenerator.token(64),
    name = "test-group",
    description = "group for test"
  )

  override def entityName: String = "ServiceGroup"
  override def bulkEntities(): Seq[ServiceGroup] = Seq.empty
  override def route(): String = "/api/groups"
  override def readEntityFromJson(json: JsValue): ServiceGroup = ServiceGroup._fmt.reads(json).get
  override def writeEntityToJson(entity: ServiceGroup): JsValue = ServiceGroup._fmt.writes(entity)
  override def updateEntity(entity: ServiceGroup): ServiceGroup = entity.copy(name = entity.name + " - updated")
  override def patchEntity(entity: ServiceGroup): (ServiceGroup, JsArray) = (entity.copy(name = entity.name + " - patched"), Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> (entity.name + " - patched"))))
  override def extractId(entity: ServiceGroup): String = entity.id
}
