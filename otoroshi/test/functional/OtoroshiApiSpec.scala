package functional

import com.typesafe.config.ConfigFactory
import models.{ApiKey, ServiceDescriptor, ServiceGroup, Target}
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.{JsSuccess, Json, Reads}

class OtoroshiApiSpec(name: String, configurationSpec: Configuration)
  extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

  lazy val serviceHost = "api.foo.bar"
  lazy val ws = otoroshiComponents.wsClient

  override def getConfiguration(configuration: Configuration) = configuration ++ configurationSpec ++ Configuration(
    ConfigFactory
      .parseString(s"""
                      |{
                      |  http.port=$port
                      |  play.server.http.port=$port
                      |}
       """.stripMargin).resolve()
  )

  s"[$name] Otoroshi admin API" should {

    val testGroup = new ServiceGroup(
      id = "test-group",
      name = "Test group",
      description = "A test group"
    )

    val testApiKey = new ApiKey(
      clientId = "1234",
      clientSecret = "1234567890",
      clientName = "test apikey",
      authorizedGroup = testGroup.id,
      enabled = true,
      throttlingQuota = 10,
      dailyQuota = 10,
      monthlyQuota = 100,
      metadata = Map.empty
    )

    val testApiKey2 = new ApiKey(
      clientId = "4321",
      clientSecret = "0987654321",
      clientName = "test apikey 2",
      authorizedGroup = testGroup.id,
      enabled = true,
      throttlingQuota = 10,
      dailyQuota = 10,
      monthlyQuota = 100,
      metadata = Map.empty
    )

    val testServiceDescriptor = new ServiceDescriptor(
      id = "test-service",
      groupId = testGroup.id,
      name = "test-service",
      env = "prod",
      domain = "foo.bar",
      subdomain = "api",
      targets = Seq(
        Target(host = "127.0.0.1:9999", scheme = "http")
      ),
      enabled = true,
      metadata = Map.empty
    )

    "warm up" in {
      getOtoroshiServices().futureValue // WARM UP
    }

    s"return only one service descriptor after startup (for admin API)" in {
      val services = getOtoroshiServices().futureValue
      services.size mustBe 1
    }

    "provide templates for the main entities" in {
      val (apikeyTemplate, status1) = otoroshiApiCall("GET", "/api/new/apikey").futureValue
      val (serviceTemplate, status2) = otoroshiApiCall("GET", "/api/new/service").futureValue
      val (groupTemplate, status3) = otoroshiApiCall("GET", "/api/new/group").futureValue

      status1 mustBe 200
      status2 mustBe 200
      status3 mustBe 200

      ApiKey.fromJsonSafe(apikeyTemplate).isSuccess mustBe true
      ServiceDescriptor.fromJsonSafe(serviceTemplate).isSuccess mustBe true
      ServiceGroup.fromJsonSafe(groupTemplate).isSuccess mustBe true
    }

    "provide a way to crud main entities" in {
      {
        val (_, status1) = otoroshiApiCall("POST", "/api/groups", Some(testGroup.toJson)).futureValue
        val (_, status2) = otoroshiApiCall("POST", "/api/services", Some(testServiceDescriptor.toJson)).futureValue
        val (_, status3) = otoroshiApiCall("POST", s"/api/groups/${testGroup.id}/apikeys", Some(testApiKey.toJson)).futureValue
        val (_, status4) = otoroshiApiCall("POST", s"/api/services/${testServiceDescriptor.id}/apikeys", Some(testApiKey2.toJson)).futureValue

        status1 mustBe 200
        status2 mustBe 200
        status3 mustBe 200
        status4 mustBe 200
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", "/api/groups").futureValue
        status1 mustBe 200
        Reads.seq[ServiceGroup](ServiceGroup._fmt).reads(res1).get.contains(testGroup) mustBe true
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", "/api/services").futureValue
        status1 mustBe 200
        Reads.seq[ServiceDescriptor](ServiceDescriptor._fmt).reads(res1).get.contains(testServiceDescriptor) mustBe true
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}/apikeys").futureValue
        status1 mustBe 200
        //Reads.seq[ApiKey](ApiKey._fmt).reads(res1).get.contains(testApiKey) mustBe true
        Reads.seq[ApiKey](ApiKey._fmt).reads(res1).get.contains(testApiKey2) mustBe true
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}/apikeys").futureValue
        status1 mustBe 200
        Reads.seq[ApiKey](ApiKey._fmt).reads(res1).get.contains(testApiKey) mustBe true
        //Reads.seq[ApiKey](ApiKey._fmt).reads(res1).get.contains(testApiKey2) mustBe true
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/apikeys").futureValue
        status1 mustBe 200
        Reads.seq[ApiKey](ApiKey._fmt).reads(res1).get.contains(testApiKey) mustBe true
        Reads.seq[ApiKey](ApiKey._fmt).reads(res1).get.contains(testApiKey2) mustBe true
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}").futureValue
        status1 mustBe 200
        ServiceGroup.fromJsons(res1) mustBe testGroup
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}").futureValue
        status1 mustBe 200
        ServiceDescriptor.fromJsons(res1) mustBe testServiceDescriptor
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}").futureValue
        status1 mustBe 200
        ApiKey.fromJsons(res1) mustBe testApiKey
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey2.clientId}").futureValue
        status1 mustBe 200
        ApiKey.fromJsons(res1) mustBe testApiKey2
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}/apikeys/${testApiKey.clientId}").futureValue
        status1 mustBe 200
        ApiKey.fromJsons(res1) mustBe testApiKey
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}/apikeys/${testApiKey2.clientId}").futureValue
        status1 mustBe 200
        ApiKey.fromJsons(res1) mustBe testApiKey2
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}/services").futureValue
        status1 mustBe 200
        Reads.seq[ServiceDescriptor](ServiceDescriptor._fmt).reads(res1).get.contains(testServiceDescriptor) mustBe true
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}").futureValue
        status1 mustBe 200
        ServiceGroup.fromJsons(res1).description mustBe testGroup.description
        otoroshiApiCall("PUT", s"/api/groups/${testGroup.id}", Some(testGroup.copy(description = "foo").toJson)).futureValue
        val (res2, status2) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}").futureValue
        status2 mustBe 200
        ServiceGroup.fromJsons(res2).description mustBe "foo"
        otoroshiApiCall("PATCH", s"/api/groups/${testGroup.id}", Some(Json.arr(Json.obj("op" -> "replace", "path" -> "/description", "value" -> "bar")))).futureValue
        val (res3, status3) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}").futureValue
        status3 mustBe 200
        ServiceGroup.fromJsons(res3).description mustBe "bar"
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}").futureValue
        status1 mustBe 200
        ServiceDescriptor.fromJsons(res1).name mustBe testServiceDescriptor.name
        otoroshiApiCall("PUT", s"/api/services/${testServiceDescriptor.id}", Some(testServiceDescriptor.copy(name = "foo").toJson)).futureValue
        val (res2, status2) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}").futureValue
        status2 mustBe 200
        ServiceDescriptor.fromJsons(res2).name mustBe "foo"
        otoroshiApiCall("PATCH", s"/api/services/${testServiceDescriptor.id}", Some(Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> "bar")))).futureValue
        val (res3, status3) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}").futureValue
        status3 mustBe 200
        ServiceDescriptor.fromJsons(res3).name mustBe "bar"
      }

      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}").futureValue
        status1 mustBe 200
        ApiKey.fromJsons(res1).clientName mustBe testApiKey.clientName
        otoroshiApiCall("PUT", s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}", Some(testApiKey.copy(clientName = "foo").toJson)).futureValue
        val (res2, status2) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}").futureValue
        status2 mustBe 200
        ApiKey.fromJsons(res2).clientName mustBe "foo"
        otoroshiApiCall("PATCH", s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}", Some(Json.arr(Json.obj("op" -> "replace", "path" -> "/clientName", "value" -> "bar")))).futureValue
        val (res3, status3) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}").futureValue
        status3 mustBe 200
        ApiKey.fromJsons(res3).clientName mustBe "bar"
      }

      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}/apikeys/${testApiKey2.clientId}").futureValue
        status1 mustBe 200
        ApiKey.fromJsons(res1).clientName mustBe testApiKey2.clientName
        otoroshiApiCall("PUT", s"/api/groups/${testGroup.id}/apikeys/${testApiKey2.clientId}", Some(testApiKey2.copy(clientName = "foo").toJson)).futureValue
        val (res2, status2) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}/apikeys/${testApiKey2.clientId}").futureValue
        status2 mustBe 200
        ApiKey.fromJsons(res2).clientName mustBe "foo"
        otoroshiApiCall("PATCH", s"/api/groups/${testGroup.id}/apikeys/${testApiKey2.clientId}", Some(Json.arr(Json.obj("op" -> "replace", "path" -> "/clientName", "value" -> "bar")))).futureValue
        val (res3, status3) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}/apikeys/${testApiKey2.clientId}").futureValue
        status3 mustBe 200
        ApiKey.fromJsons(res3).clientName mustBe "bar"
      }

      {
        otoroshiApiCall("DELETE", s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}").futureValue
        otoroshiApiCall("DELETE", s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey2.clientId}").futureValue
        otoroshiApiCall("DELETE", s"/api/services/${testServiceDescriptor.id}").futureValue
        otoroshiApiCall("DELETE", s"/api/groups/${testGroup.id}").futureValue

        val (_, status1) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}").futureValue
        val (_, status2) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey2.clientId}").futureValue
        val (_, status3) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}").futureValue
        val (_, status4) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}").futureValue

        status1 mustBe 404
        status2 mustBe 404
        status3 mustBe 404
        status4 mustBe 404
      }
    }

    /*

    ## ApiKeys
    GET     /api/services/:serviceId/apikeys/:clientId/quotas
    DELETE  /api/services/:serviceId/apikeys/:clientId/quotas
    GET     /api/services/:serviceId/apikeys/:clientId/group
    PUT     /api/services/:serviceId/apikeys/:clientId/group

    GET     /api/groups/:groupId/apikeys/:clientId/quotas
    DELETE  /api/groups/:groupId/apikeys/:clientId/quotas

    ## Services
    GET     /api/services/:serviceId/template
    PUT     /api/services/:serviceId/template
    POST    /api/services/:serviceId/template
    DELETE  /api/services/:serviceId/template
    GET     /api/services/:serviceId/targets
    POST    /api/services/:serviceId/targets
    DELETE  /api/services/:serviceId/targets
    PATCH   /api/services/:serviceId/targets
    GET     /api/services/:serviceId/live
    GET     /api/services/:serviceId/stats
    GET     /api/services/:serviceId/events
    GET     /api/services/:serviceId/health
    GET     /api/services/:serviceId/canary
    DELETE  /api/services/:serviceId/canary

     */
  }
}