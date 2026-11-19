package functional

import com.typesafe.config.ConfigFactory
import otoroshi.models.*
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.{JsArray, JsObject, JsSuccess, JsValue, Json, Reads}
import play.api.libs.ws.{readableAsString, writeableOf_String, WSAuthScheme, WSResponse}
import otoroshi.utils.syntax.implicits.*

import java.io.File
import java.nio.file.Files
import scala.concurrent.duration.*

class AdminApiSpec(name: String, configurationSpec: => Configuration) extends OtoroshiSpec {

  lazy val serviceHost = "api.oto.tools"

  override def getTestConfiguration(configuration: Configuration) =
    Configuration(
      ConfigFactory
        .parseString(s"""
                      |{
                      |}
       """.stripMargin)
        .resolve()
    ).withFallback(configurationSpec).withFallback(configuration)

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
      authorizedEntities = Seq(ServiceGroupIdentifier(testGroup.id)),
      enabled = true,
      throttlingQuota = 10,
      dailyQuota = 10,
      monthlyQuota = 100,
      metadata = Map.empty
    )

    val testServiceDescriptor = new ServiceDescriptor(
      id = "test-service",
      groups = Seq(testGroup.id),
      name = "test-service",
      env = "prod",
      domain = "oto.tools",
      subdomain = "api",
      targets = Seq(
        Target(host = "127.0.0.1:9999", scheme = "http")
      ),
      enabled = true,
      metadata = Map.empty,
      chaosConfig = ChaosConfig._fmt
        .reads(Json.parse("""{
          |  "enabled" : false,
          |  "largeRequestFaultConfig" : {
          |    "ratio" : 0.2,
          |    "additionalRequestSize" : 0
          |  },
          |  "largeResponseFaultConfig" : {
          |    "ratio" : 0.2,
          |    "additionalResponseSize" : 0
          |  },
          |  "latencyInjectionFaultConfig" : {
          |    "ratio" : 0.2,
          |    "from" : 0,
          |    "to" : 0
          |  },
          |  "badResponsesFaultConfig" : {
          |    "ratio" : 0.2,
          |    "responses" : [ ]
          |  }
          |}""".stripMargin))
        .get
    )

    val testApiKey2 = new ApiKey(
      clientId = "4321",
      clientSecret = "0987654321",
      clientName = "test apikey 2",
      authorizedEntities =
        Seq(ServiceGroupIdentifier(testGroup.id), ServiceDescriptorIdentifier(testServiceDescriptor.id)),
      enabled = true,
      throttlingQuota = 10,
      dailyQuota = 10,
      monthlyQuota = 100,
      metadata = Map.empty
    )

    // a call of the admin api, answered as it is
    def generic(
        method: String,
        path: String,
        body: Option[String] = None,
        secret: String = "admin-api-apikey-secret",
        contentType: String = "application/json"
    ): WSResponse = {
      val req = ws
        .url(s"http://localhost:$port$path")
        .withHttpHeaders("Host" -> "otoroshi-api.oto.tools", "Accept" -> contentType)
        .withAuth("admin-api-apikey-id", secret, WSAuthScheme.BASIC)
        .withMethod(method)
      body.map(b => req.addHttpHeaders("Content-Type" -> contentType).withBody(b)).getOrElse(req).execute().futureValue
    }
    def errorOf(resp: WSResponse): String = (Json.parse(resp.body[String]) \ "error").as[String]

    "warm up" in {
      startOtoroshi()
      getOtoroshiServices().futureValue // WARM UP
    }

    s"return only one route after startup (for admin API)" in {
      val routes = getOtoroshiRoutes().futureValue
      routes.size mustBe 1
    }

    "provide templates for the main entities" in {
      val (apikeyTemplate, status1)  = otoroshiApiCall("GET", "/api/new/apikey").futureValue
      //[REMOVE SERVICEDESC] val (serviceTemplate, status2) = otoroshiApiCall("GET", "/api/new/service").futureValue
      val (groupTemplate, status3)   = otoroshiApiCall("GET", "/api/new/group").futureValue

      status1 mustBe 200
      //[REMOVE SERVICEDESC] status2 mustBe 200
      status3 mustBe 200

      ApiKey.fromJsonSafe(apikeyTemplate).isSuccess mustBe true
      //[REMOVE SERVICEDESC] ServiceDescriptor.fromJsonSafe(serviceTemplate).isSuccess mustBe true
      ServiceGroup.fromJsonSafe(groupTemplate).isSuccess mustBe true
    }

    "provide a way to crud main entities" in {
      {
        val (_, status1) = otoroshiApiCall("POST", "/api/groups", Some(testGroup.toJson)).futureValue
        //[REMOVE SERVICEDESC] val (_, status2) = otoroshiApiCall("POST", "/api/services", Some(testServiceDescriptor.toJson)).futureValue
        val (_, status3) =
          otoroshiApiCall("POST", s"/api/groups/${testGroup.id}/apikeys", Some(testApiKey.toJson)).futureValue
        //[REMOVE SERVICEDESC] val (_, status4) = otoroshiApiCall(
          //[REMOVE SERVICEDESC] "POST",
          //[REMOVE SERVICEDESC] s"/api/services/${testServiceDescriptor.id}/apikeys",
          //[REMOVE SERVICEDESC] Some(testApiKey2.toJson)
        //[REMOVE SERVICEDESC] ).futureValue

        status1 mustBe 201
        //[REMOVE SERVICEDESC] status2 mustBe 201
        status3 mustBe 201
        //[REMOVE SERVICEDESC] status4 mustBe 201
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", "/api/groups").futureValue
        status1 mustBe 200
        Reads.seq[ServiceGroup](using ServiceGroup._fmt).reads(res1).get.contains(testGroup) mustBe true
      }
      //[REMOVE SERVICEDESC] {
        //[REMOVE SERVICEDESC] val (res1, status1) = otoroshiApiCall("GET", "/api/services").futureValue
        //[REMOVE SERVICEDESC] status1 mustBe 200
        //[REMOVE SERVICEDESC] Reads
          //[REMOVE SERVICEDESC] .seq[ServiceDescriptor](using ServiceDescriptor._fmt)
          //[REMOVE SERVICEDESC] .reads(res1)
          //[REMOVE SERVICEDESC] .get
          //[REMOVE SERVICEDESC] .exists(_.id == testServiceDescriptor.id) mustBe true
      //[REMOVE SERVICEDESC] }
      //[REMOVE SERVICEDESC] {
        //[REMOVE SERVICEDESC] val (res1, status1) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}/apikeys").futureValue
        //[REMOVE SERVICEDESC] status1 mustBe 200
        //[REMOVE SERVICEDESC] //Reads.seq[ApiKey](ApiKey._fmt).reads(res1).get.contains(testApiKey) mustBe true
        //[REMOVE SERVICEDESC] Reads.seq[ApiKey](using ApiKey._fmt).reads(res1).get.contains(testApiKey2) mustBe true
      //[REMOVE SERVICEDESC] }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}/apikeys").futureValue
        status1 mustBe 200
        Reads.seq[ApiKey](using ApiKey._fmt).reads(res1).get.contains(testApiKey) mustBe true
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/apikeys").futureValue
        status1 mustBe 200
        Reads.seq[ApiKey](using ApiKey._fmt).reads(res1).get.contains(testApiKey) mustBe true
        //[REMOVE SERVICEDESC] Reads.seq[ApiKey](using ApiKey._fmt).reads(res1).get.contains(testApiKey2) mustBe true
      }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}").futureValue
        status1 mustBe 200
        ServiceGroup.fromJsons(res1) mustBe testGroup
      }
      //[REMOVE SERVICEDESC] {
        //[REMOVE SERVICEDESC] val (res1, status1) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}").futureValue
        //[REMOVE SERVICEDESC] status1 mustBe 200
        //[REMOVE SERVICEDESC] ServiceDescriptor.fromJsons(res1) mustBe testServiceDescriptor
      //[REMOVE SERVICEDESC] }
      //[REMOVE SERVICEDESC] {
        //[REMOVE SERVICEDESC] val (res1, status1) = otoroshiApiCall(
          //[REMOVE SERVICEDESC] "GET",
          //[REMOVE SERVICEDESC] s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}"
        //[REMOVE SERVICEDESC] ).futureValue
        //[REMOVE SERVICEDESC] status1 mustBe 200
        //[REMOVE SERVICEDESC] ApiKey.fromJsons(res1) mustBe testApiKey
      //[REMOVE SERVICEDESC] }
      //[REMOVE SERVICEDESC] {
        //[REMOVE SERVICEDESC] val (res1, status1) = otoroshiApiCall(
          //[REMOVE SERVICEDESC] "GET",
          //[REMOVE SERVICEDESC] s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey2.clientId}"
        //[REMOVE SERVICEDESC] ).futureValue
        //[REMOVE SERVICEDESC] status1 mustBe 200
        //[REMOVE SERVICEDESC] ApiKey.fromJsons(res1) mustBe testApiKey2
      //[REMOVE SERVICEDESC] }
      {
        val (res1, status1) =
          otoroshiApiCall("GET", s"/api/groups/${testGroup.id}/apikeys/${testApiKey.clientId}").futureValue
        status1 mustBe 200
        ApiKey.fromJsons(res1) mustBe testApiKey
      }
      // {
      //   val (res1, status1) =
      //     otoroshiApiCall("GET", s"/api/groups/${testGroup.id}/apikeys/${testApiKey2.clientId}").futureValue
      //   status1 mustBe 200
      //   ApiKey.fromJsons(res1) mustBe testApiKey2
      // }
      //[REMOVE SERVICEDESC] {
        //[REMOVE SERVICEDESC] val (res1, status1) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}/services").futureValue
        //[REMOVE SERVICEDESC] status1 mustBe 200
        //[REMOVE SERVICEDESC] Reads
          //[REMOVE SERVICEDESC] .seq[ServiceDescriptor](using ServiceDescriptor._fmt)
          //[REMOVE SERVICEDESC] .reads(res1)
          //[REMOVE SERVICEDESC] .get
          //[REMOVE SERVICEDESC] .exists(_.id == testServiceDescriptor.id) mustBe true
      //[REMOVE SERVICEDESC] }
      {
        val (res1, status1) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}").futureValue
        status1 mustBe 200
        ServiceGroup.fromJsons(res1).description mustBe testGroup.description
        otoroshiApiCall(
          "PUT",
          s"/api/groups/${testGroup.id}",
          Some(testGroup.copy(description = "foo").toJson)
        ).futureValue
        val (res2, status2) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}").futureValue
        status2 mustBe 200
        ServiceGroup.fromJsons(res2).description mustBe "foo"
        otoroshiApiCall(
          "PATCH",
          s"/api/groups/${testGroup.id}",
          Some(Json.arr(Json.obj("op" -> "replace", "path" -> "/description", "value" -> "bar")))
        ).futureValue
        val (res3, status3) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}").futureValue
        status3 mustBe 200
        ServiceGroup.fromJsons(res3).description mustBe "bar"
      }
      //[REMOVE SERVICEDESC] {
        //[REMOVE SERVICEDESC] val (res1, status1) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}").futureValue
        //[REMOVE SERVICEDESC] status1 mustBe 200
        //[REMOVE SERVICEDESC] ServiceDescriptor.fromJsons(res1).name mustBe testServiceDescriptor.name
        //[REMOVE SERVICEDESC] otoroshiApiCall(
          //[REMOVE SERVICEDESC] "PUT",
          //[REMOVE SERVICEDESC] s"/api/services/${testServiceDescriptor.id}",
          //[REMOVE SERVICEDESC] Some(testServiceDescriptor.copy(name = "foo").toJson)
        //[REMOVE SERVICEDESC] ).futureValue
        //[REMOVE SERVICEDESC] val (res2, status2) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}").futureValue
        //[REMOVE SERVICEDESC] status2 mustBe 200
        //[REMOVE SERVICEDESC] ServiceDescriptor.fromJsons(res2).name mustBe "foo"
        //[REMOVE SERVICEDESC] otoroshiApiCall(
          //[REMOVE SERVICEDESC] "PATCH",
          //[REMOVE SERVICEDESC] s"/api/services/${testServiceDescriptor.id}",
          //[REMOVE SERVICEDESC] Some(Json.arr(Json.obj("op" -> "replace", "path" -> "/name", "value" -> "bar")))
        //[REMOVE SERVICEDESC] ).futureValue
        //[REMOVE SERVICEDESC] val (res3, status3) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}").futureValue
        //[REMOVE SERVICEDESC] status3 mustBe 200
        //[REMOVE SERVICEDESC] ServiceDescriptor.fromJsons(res3).name mustBe "bar"
      //[REMOVE SERVICEDESC] }

      //[REMOVE SERVICEDESC] {
        //[REMOVE SERVICEDESC] val (res1, status1) = otoroshiApiCall(
          //[REMOVE SERVICEDESC] "GET",
          //[REMOVE SERVICEDESC] s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}"
        //[REMOVE SERVICEDESC] ).futureValue
        //[REMOVE SERVICEDESC] status1 mustBe 200
        //[REMOVE SERVICEDESC] ApiKey.fromJsons(res1).clientName mustBe testApiKey.clientName
        //[REMOVE SERVICEDESC] otoroshiApiCall(
          //[REMOVE SERVICEDESC] "PUT",
          //[REMOVE SERVICEDESC] s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}",
          //[REMOVE SERVICEDESC] Some(testApiKey.copy(clientName = "foo").toJson)
        //[REMOVE SERVICEDESC] ).futureValue
        //[REMOVE SERVICEDESC] val (res2, status2) = otoroshiApiCall(
          //[REMOVE SERVICEDESC] "GET",
          //[REMOVE SERVICEDESC] s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}"
        //[REMOVE SERVICEDESC] ).futureValue
        //[REMOVE SERVICEDESC] status2 mustBe 200
        //[REMOVE SERVICEDESC] ApiKey.fromJsons(res2).clientName mustBe "foo"
        //[REMOVE SERVICEDESC] otoroshiApiCall(
          //[REMOVE SERVICEDESC] "PATCH",
          //[REMOVE SERVICEDESC] s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}",
          //[REMOVE SERVICEDESC] Some(Json.arr(Json.obj("op" -> "replace", "path" -> "/clientName", "value" -> "bar")))
        //[REMOVE SERVICEDESC] ).futureValue
        //[REMOVE SERVICEDESC] val (res3, status3) = otoroshiApiCall(
          //[REMOVE SERVICEDESC] "GET",
          //[REMOVE SERVICEDESC] s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}"
        //[REMOVE SERVICEDESC] ).futureValue
        //[REMOVE SERVICEDESC] status3 mustBe 200
        //[REMOVE SERVICEDESC] ApiKey.fromJsons(res3).clientName mustBe "bar"
      //[REMOVE SERVICEDESC] }

      // {
      //   val (res1, status1) =
      //     otoroshiApiCall("GET", s"/api/groups/${testGroup.id}/apikeys/${testApiKey2.clientId}").futureValue
      //   status1 mustBe 200
      //   ApiKey.fromJsons(res1).clientName mustBe testApiKey2.clientName
      //   otoroshiApiCall("PUT",
      //                   s"/api/groups/${testGroup.id}/apikeys/${testApiKey2.clientId}",
      //                   Some(testApiKey2.copy(clientName = "foo").toJson)).futureValue
      //   val (res2, status2) =
      //     otoroshiApiCall("GET", s"/api/groups/${testGroup.id}/apikeys/${testApiKey2.clientId}").futureValue
      //   status2 mustBe 200
      //   ApiKey.fromJsons(res2).clientName mustBe "foo"
      //   otoroshiApiCall(
      //     "PATCH",
      //     s"/api/groups/${testGroup.id}/apikeys/${testApiKey2.clientId}",
      //     Some(Json.arr(Json.obj("op" -> "replace", "path" -> "/clientName", "value" -> "bar")))
      //   ).futureValue
      //   val (res3, status3) =
      //     otoroshiApiCall("GET", s"/api/groups/${testGroup.id}/apikeys/${testApiKey2.clientId}").futureValue
      //   status3 mustBe 200
      //   ApiKey.fromJsons(res3).clientName mustBe "bar"
      // }

      {
        //[REMOVE SERVICEDESC] otoroshiApiCall(
          //[REMOVE SERVICEDESC] "DELETE",
          //[REMOVE SERVICEDESC] s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}"
        //[REMOVE SERVICEDESC] ).futureValue
        //[REMOVE SERVICEDESC] otoroshiApiCall(
          //[REMOVE SERVICEDESC] "DELETE",
          //[REMOVE SERVICEDESC] s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey2.clientId}"
        //[REMOVE SERVICEDESC] ).futureValue
        //[REMOVE SERVICEDESC] otoroshiApiCall("DELETE", s"/api/services/${testServiceDescriptor.id}").futureValue
        otoroshiApiCall("DELETE", s"/api/groups/${testGroup.id}").futureValue

        //[REMOVE SERVICEDESC] val (_, status1) = otoroshiApiCall(
          //[REMOVE SERVICEDESC] "GET",
          //[REMOVE SERVICEDESC] s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey.clientId}"
        //[REMOVE SERVICEDESC] ).futureValue
        //[REMOVE SERVICEDESC] val (_, status2) = otoroshiApiCall(
          //[REMOVE SERVICEDESC] "GET",
          //[REMOVE SERVICEDESC] s"/api/services/${testServiceDescriptor.id}/apikeys/${testApiKey2.clientId}"
        //[REMOVE SERVICEDESC] ).futureValue
        //[REMOVE SERVICEDESC] val (_, status3) = otoroshiApiCall("GET", s"/api/services/${testServiceDescriptor.id}").futureValue
        val (_, status4) = otoroshiApiCall("GET", s"/api/groups/${testGroup.id}").futureValue

        //[REMOVE SERVICEDESC] status1 mustBe 404
        //[REMOVE SERVICEDESC] status2 mustBe 404
        //[REMOVE SERVICEDESC] status3 mustBe 404
        status4 mustBe 404
      }
    }

    "answer the writes of the generic api with the status and the header of what they did" in {
      val routes   = "/apis/proxy.otoroshi.io/v1/routes"
      val template = Json.parse(generic("GET", s"$routes/_template").body[String]).as[JsObject]
      def route(id: String, name: String = "generic-writes"): JsObject = template ++ Json.obj("id" -> id, "name" -> name)

      // a PUT never creates: a missing id is not found, whatever the kind
      Seq(
        s"$routes/generic-missing-route"                          -> route("generic-missing-route"),
        "/apis/apim.otoroshi.io/v1/apikeys/generic-missing-key"    -> Json.obj("clientId" -> "generic-missing-key", "clientSecret" -> "s", "clientName" -> "k"),
        "/apis/apis.otoroshi.io/v1/apis/generic-missing-api"       -> Json.obj("id" -> "generic-missing-api", "name" -> "a")
      ).foreach { case (path, body) =>
        val resp = generic("PUT", path, Some(Json.stringify(body)))
        withClue(s"$path: ${resp.body[String]}") {
          resp.status mustBe 404
          errorOf(resp) mustBe "not_found"
        }
      }

      // a POST of an id already used is a conflict, in bulk too
      generic("POST", routes, Some(Json.stringify(route("generic-conflict-route")))).status mustBe 201
      try {
        val again = generic("POST", routes, Some(Json.stringify(route("generic-conflict-route"))))
        withClue(again.body[String]) {
          again.status mustBe 409
          errorOf(again) mustBe "conflict"
        }
        val bulk  = generic(
          "POST",
          s"$routes/_bulk",
          Some(Json.stringify(route("generic-conflict-route"))),
          contentType = "application/x-ndjson"
        )
        withClue(bulk.body[String]) {
          (Json.parse(bulk.body[String].linesIterator.next()) \ "status").as[Int] mustBe 409
        }
        // wrong credentials are still wrong credentials
        generic("POST", routes, Some(Json.stringify(route("generic-conflict-route"))), secret = "wrong").status mustBe 401
        generic("PUT", s"$routes/generic-missing-route", Some(Json.stringify(route("generic-missing-route"))), secret = "wrong").status mustBe 401
      } finally {
        generic("DELETE", s"$routes/generic-conflict-route")
      }

      // the upsert tells whether it changed the stored entity
      val upserted = s"$routes/generic-upsert-route"
      def upsert(body: JsValue): (Int, Option[String], JsValue) = {
        val resp = generic("POST", upserted, Some(Json.stringify(body)))
        (resp.status, resp.header("Otoroshi-Entity-Updated"), Json.parse(resp.body[String]))
      }
      try {
        val (createdStatus, createdHeader, created) = upsert(route("generic-upsert-route"))
        createdStatus mustBe 201
        createdHeader mustBe Some("true")
        (created \ "metadata" \ "created_at").asOpt[String] must not be empty
        val (sameStatus, sameHeader, _)             = upsert(route("generic-upsert-route"))
        (sameStatus, sameHeader) mustBe (200, Some("false"))
        val (renamedStatus, renamedHeader, _)       = upsert(route("generic-upsert-route", name = "renamed"))
        (renamedStatus, renamedHeader) mustBe (200, Some("true"))
        val readBack                                = Json.parse(generic("GET", upserted).body[String])
        val (backStatus, backHeader, _)             = upsert(readBack)
        (backStatus, backHeader) mustBe (200, Some("false"))
      } finally {
        generic("DELETE", upserted)
      }
    }

    "store the certificates written through the generic api with the fields of their chain" in {
      val certificates = "/apis/pki.otoroshi.io/v1/certificates"
      // a self-signed certificate for two names, valid 30 days, generated without being stored
      val generated    = Json.parse(
        generic(
          "POST",
          "/api/pki/certs?persist=false",
          Some(
            Json.stringify(
              Json.obj(
                "hosts"    -> Json.arr("probe.example.test", "alt.example.test"),
                "key"      -> Json.obj("algo" -> "rsa", "size" -> 2048),
                "subject"  -> "CN=probe.example.test",
                "duration" -> 30.days.toMillis
              )
            )
          )
        ).body[String]
      )
      val template     = Json.parse(generic("GET", s"$certificates/_template").body[String]).as[JsObject]
      // the chain of the certificate, with fields that say nothing of it
      def body(id: String): JsObject = template ++ Json.obj(
        "id"         -> id,
        "chain"      -> (generated \ "chain").as[String],
        "privateKey" -> (generated \ "privateKey").as[String],
        "subject"    -> "",
        "domain"     -> "",
        "sans"       -> Json.arr(),
        "selfSigned" -> false,
        "ca"         -> false,
        "from"       -> 0L,
        "to"         -> 0L
      )
      val emptied      = Json.arr(
        Json.obj("op" -> "replace", "path" -> "/subject", "value" -> ""),
        Json.obj("op" -> "replace", "path" -> "/sans", "value" -> Json.arr()),
        Json.obj("op" -> "replace", "path" -> "/to", "value" -> 0L)
      )
      def fromChain(id: String): JsObject = {
        val stored = Json.parse(generic("GET", s"$certificates/$id").body[String])
        JsObject(
          Seq("subject", "domain", "sans", "from", "to", "ca", "selfSigned").map(k => k -> (stored \ k).as[JsValue])
        )
      }
      def ndjson(lines: JsValue*): Option[String] = Some(lines.map(Json.stringify).mkString("\n"))
      val ids          = Seq("ref", "post", "upsert", "bulk", "empty").map(n => s"generic-cert-$n")
      try {
        // what /api/certificates stores
        generic("POST", "/api/certificates", Some(Json.stringify(body("generic-cert-ref")))).status mustBe 201
        val reference = fromChain("generic-cert-ref")
        withClue(reference.toString) {
          (reference \ "subject").as[String] must include("CN=probe.example.test")
          (reference \ "sans").as[Seq[String]] must contain allOf ("probe.example.test", "alt.example.test")
          (reference \ "selfSigned").as[Boolean] mustBe true
          ((reference \ "to").as[Long] - (reference \ "from").as[Long]) mustBe 30.days.toMillis +- 1.day.toMillis
        }
        def storedLikeTheReference(what: String, id: String): Unit =
          withClue(what) { fromChain(id) mustBe reference }

        generic("POST", certificates, Some(Json.stringify(body("generic-cert-post")))).status mustBe 201
        storedLikeTheReference("create", "generic-cert-post")
        generic("PUT", s"$certificates/generic-cert-post", Some(Json.stringify(body("generic-cert-post")))).status mustBe 200
        storedLikeTheReference("update", "generic-cert-post")
        generic("PATCH", s"$certificates/generic-cert-post", Some(Json.stringify(emptied))).status mustBe 200
        storedLikeTheReference("patch", "generic-cert-post")

        val upsert = generic("POST", s"$certificates/generic-cert-upsert", Some(Json.stringify(body("generic-cert-upsert"))))
        upsert.status mustBe 201
        storedLikeTheReference("upsert creation", "generic-cert-upsert")
        // the stored certificate is the same as before: the fields of the body are not the ones stored
        val again  = generic("POST", s"$certificates/generic-cert-upsert", Some(Json.stringify(body("generic-cert-upsert"))))
        (again.status, again.header("Otoroshi-Entity-Updated")) mustBe (200, Some("false"))
        storedLikeTheReference("upsert update", "generic-cert-upsert")

        val bulk = "application/x-ndjson"
        generic("POST", s"$certificates/_bulk", ndjson(body("generic-cert-bulk")), contentType = bulk).status mustBe 200
        storedLikeTheReference("bulk create", "generic-cert-bulk")
        generic("PUT", s"$certificates/_bulk", ndjson(body("generic-cert-bulk")), contentType = bulk).status mustBe 200
        storedLikeTheReference("bulk update", "generic-cert-bulk")
        generic(
          "PATCH",
          s"$certificates/_bulk",
          ndjson(Json.obj("id" -> "generic-cert-bulk", "patch" -> emptied)),
          contentType = bulk
        ).status mustBe 200
        storedLikeTheReference("bulk patch", "generic-cert-bulk")

        // a certificate whose chain cannot be read, like the template, is stored as it is written
        val empty = generic("POST", certificates, Some(Json.stringify(template ++ Json.obj("id" -> "generic-cert-empty", "subject" -> "as written"))))
        withClue(empty.body[String]) { empty.status mustBe 201 }
        (Json.parse(generic("GET", s"$certificates/generic-cert-empty").body[String]) \ "subject").as[String] mustBe "as written"
      } finally {
        ids.foreach(id => generic("DELETE", s"$certificates/$id"))
      }
    }

    "shutdown" in {
      stopAll()
    }

  }
}
