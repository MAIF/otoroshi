package functional

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory
import env.Env
import events._
import events.impl.ElasticWritesAnalytics
import models._
import org.joda.time.DateTime
import org.scalatest.concurrent.IntegrationPatience
import org.scalatestplus.play.PlaySpec
import play.api.Configuration
import play.api.libs.json.{JsArray, JsValue, Json, Reads}
import play.api.libs.ws.WSClient
import security.IdGenerator

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class AnalyticsSpec(name: String, configurationSpec: => Configuration)
    extends PlaySpec
    with OneServerPerSuiteWithMyComponents
    with OtoroshiSpecHelper
    with IntegrationPatience {

  implicit val system = ActorSystem("otoroshi-test")

  lazy val serviceHost  = "api.foo.bar"
  lazy val ws: WSClient = otoroshiComponents.wsClient
  lazy val elasticUrl   = "http://127.0.0.1:9200"
  lazy val analytics = new ElasticWritesAnalytics(
    ElasticAnalyticsConfig(elasticUrl, Some("otoroshi-events"), Some("event"), None, None),
    otoroshiComponents.environment,
    otoroshiComponents.env,
    otoroshiComponents.executionContext,
    otoroshiComponents.actorSystem
  )

  override def getConfiguration(configuration: Configuration) = configuration ++ configurationSpec ++ Configuration(
    ConfigFactory
      .parseString(s"""
                      |{
                      |  http.port=$port
                      |  play.server.http.port=$port
                      |  app.analyticsWindow = 2
                      |}
       """.stripMargin)
      .resolve()
  )

  s"Analytics API" should {

    def runTest(serviceId: Option[String] = None, count: Int) = {
      setUp {
        // Inject events
        val now = DateTime.now()
        setUpEvent(
          (1 to 100).map { i =>
            event(now.minusMinutes(i), getStatus(i), 500L, 50L, 500, 1000)
          }: _*
        )

        awaitF(10.seconds).futureValue

        ws.url(s"$elasticUrl/_refresh").post("").futureValue

        val from = now.minusMinutes(100)
        val url = serviceId
          .map(id => s"/api/services/$id/stats?from=${from.getMillis}&to=${now.getMillis}")
          .getOrElse(s"/api/stats/global?from=${from.getMillis}&to=${now.getMillis}")
        val (resp, status) =
          otoroshiApiCall("GET", url).futureValue

        status mustBe 200

        val statusesPiechart = (resp \ "statusesPiechart" \ "series" \ 0 \ "data").as[Seq[JsValue]]
        (statusesPiechart.find(j => (j \ "name").as[String] == "200").get \ "y").as[Int] mustBe count
        (statusesPiechart.find(j => (j \ "name").as[String] == "400").get \ "y").as[Int] mustBe 20
        (statusesPiechart.find(j => (j \ "name").as[String] == "500").get \ "y").as[Int] mustBe 30

        val statusesHistogram = (resp \ "statusesHistogram" \ "series").as[Seq[JsValue]]
        val `4**`             = statusesHistogram.find(j => (j \ "name").as[String] == "4**").get
        (`4**` \ "count").as[Int] mustBe 20
        (`4**` \ "data").as[Seq[JsValue]].nonEmpty mustBe true
        val `2**` = statusesHistogram.find(j => (j \ "name").as[String] == "2**").get
        (`2**` \ "count").as[Int] mustBe count
        (`2**` \ "data").as[Seq[JsValue]].nonEmpty mustBe true
        val `5**` = statusesHistogram.find(j => (j \ "name").as[String] == "5**").get
        (`5**` \ "count").as[Int] mustBe 30
        (`5**` \ "data").as[Seq[JsValue]].nonEmpty mustBe true

        val `1**` = statusesHistogram.find(j => (j \ "name").as[String] == "1**").get
        (`1**` \ "count").as[Int] mustBe 0
        (`1**` \ "data").as[Seq[JsValue]].isEmpty mustBe true
        val `3**` = statusesHistogram.find(j => (j \ "name").as[String] == "3**").get
        (`3**` \ "count").as[Int] mustBe 0
        (`3**` \ "data").as[Seq[JsValue]].isEmpty mustBe true

        val overheadPercentiles      = (resp \ "overheadPercentiles" \ "series").as[Seq[JsValue]]
        val overheadPercentilesNames = overheadPercentiles.map(j => (j \ "name").as[String])
        overheadPercentilesNames must contain theSameElementsAs Vector("1.0",
                                                                       "5.0",
                                                                       "25.0",
                                                                       "50.0",
                                                                       "75.0",
                                                                       "95.0",
                                                                       "99.0")
        overheadPercentilesNames foreach { n =>
          testHistoValues(overheadPercentiles, n, 50)
        }

        val overheadStats      = (resp \ "overheadStats" \ "series").as[Seq[JsValue]]
        val overheadStatsNames = overheadStats.map(j => (j \ "name").as[String])
        overheadStatsNames must contain theSameElementsAs Vector("count", "min", "max", "avg", "std deviation")
        testHistoValues(overheadStats, "count", 1)
        testHistoValues(overheadStats, "min", 50)
        testHistoValues(overheadStats, "max", 50)
        testHistoValues(overheadStats, "avg", 50)
        testHistoValues(overheadStats, "std deviation", 0)

        val durationStats      = (resp \ "durationStats" \ "series").as[Seq[JsValue]]
        val durationStatsNames = overheadStats.map(j => (j \ "name").as[String])
        durationStatsNames must contain theSameElementsAs Vector("count", "min", "max", "avg", "std deviation")
        testHistoValues(durationStats, "count", 1)
        testHistoValues(durationStats, "min", 500)
        testHistoValues(durationStats, "max", 500)
        testHistoValues(durationStats, "avg", 500)
        testHistoValues(durationStats, "std deviation", 0)

        val durationPercentiles      = (resp \ "durationPercentiles" \ "series").as[Seq[JsValue]]
        val durationPercentilesNames = overheadPercentiles.map(j => (j \ "name").as[String])
        durationPercentilesNames must contain theSameElementsAs Vector("1.0",
                                                                       "5.0",
                                                                       "25.0",
                                                                       "50.0",
                                                                       "75.0",
                                                                       "95.0",
                                                                       "99.0")
        durationPercentilesNames foreach { n =>
          testHistoValues(durationPercentiles, n, 500)
        }

        val dataInStats      = (resp \ "dataInStats" \ "series").as[Seq[JsValue]]
        val dataInStatsNames = overheadStats.map(j => (j \ "name").as[String])
        dataInStatsNames must contain theSameElementsAs Vector("count", "min", "max", "avg", "std deviation")
        testHistoValues(dataInStats, "count", 1)
        testHistoValues(dataInStats, "min", 500)
        testHistoValues(dataInStats, "max", 500)
        testHistoValues(dataInStats, "avg", 500)
        testHistoValues(dataInStats, "std deviation", 0)

        val dataOutStats      = (resp \ "dataOutStats" \ "series").as[Seq[JsValue]]
        val dataOutStatsNames = overheadStats.map(j => (j \ "name").as[String])
        dataOutStatsNames must contain theSameElementsAs Vector("count", "min", "max", "avg", "std deviation")
        testHistoValues(dataOutStats, "count", 1)
        testHistoValues(dataOutStats, "min", 1000)
        testHistoValues(dataOutStats, "max", 1000)
        testHistoValues(dataOutStats, "avg", 1000)
        testHistoValues(dataOutStats, "std deviation", 0)

        (resp \ "hits" \ "count").as[Int] mustBe 100
        (resp \ "dataIn" \ "data.dataIn").as[Int] mustBe 50000
        (resp \ "dataOut" \ "data.dataOut").as[Int] mustBe 100000
        (resp \ "avgDuration" \ "duration").as[Int] mustBe 500
        (resp \ "avgOverhead" \ "overhead").as[Int] mustBe 50

        if (serviceId.isEmpty) {
          (resp \ "productPiechart" \ "series" \ 0 \ "data" \ 0 \ "name").as[String] mustBe "\"mon-product\""
          (resp \ "productPiechart" \ "series" \ 0 \ "data" \ 0 \ "y").as[Int] mustBe 100

          (resp \ "servicePiechart" \ "series" \ 0 \ "data" \ 0 \ "name").as[String] mustBe "\"mon-service\""
          (resp \ "servicePiechart" \ "series" \ 0 \ "data" \ 0 \ "y").as[Int] mustBe 100
        }
      }
    }

    "Global stats API" in {
      // runTest(None, 54)
    }

    "Service stats API" in {
      runTest(Some("mon-service-id"), 50)
    }

    "Events api" in {
      setUp {
        // Inject events
        val now = DateTime.now()
        setUpEvent(
          (1 to 100).map { i =>
            event(now.minusMinutes(i), getStatus(i), 500L, 50L, 500, 1000)
          }: _*
        )
        awaitF(10.seconds).futureValue
        ws.url(s"$elasticUrl/_refresh").post("").futureValue

        val from           = now.minusMinutes(1000)
        val url            = s"/api/services/mon-service-id/events?from=${from.getMillis}&to=${now.getMillis}"
        val (resp, status) = otoroshiApiCall("GET", url).futureValue

        status mustBe 200
        val events = resp.as[Seq[JsValue]]

        events.length mustBe 100

        val (resp2, status2) = otoroshiApiCall(
          "GET",
          s"/api/services/mon-service-id2/events?from=${from.getMillis}&to=${now.getMillis}"
        ).futureValue
        status2 mustBe 200
        resp2.as[Seq[JsValue]].length mustBe 0
      }
    }
  }

  private def setUp(test: => Unit): Unit = {
    //Delete all indices
    ws.url(s"$elasticUrl/otoroshi-events-*").delete().futureValue

    // Init datas
    otoroshiApiCall(
      "PATCH",
      "/api/globalconfig",
      Some(Json.parse("""
      |[
      |  { "op": "replace", "path": "/elasticWritesConfigs", "value": [ {
      |    "clusterUri": "http://127.0.0.1:9200",
      |    "index": "otoroshi-events",
      |    "type": "event"
      |  } ] },
      |  { "op": "replace", "path": "/elasticReadsConfig", "value": {
      |    "clusterUri": "http://127.0.0.1:9200",
      |    "index": "otoroshi-events",
      |    "type": "event"
      |  } }
      |]
    """.stripMargin))
    ).futureValue
    otoroshiApiCall("POST", "/api/groups", Some(testGroup.toJson)).futureValue
    otoroshiApiCall("POST", "/api/services", Some(serviceDescriptor("mon-service-id").toJson)).futureValue
    otoroshiApiCall("POST", "/api/services", Some(serviceDescriptor("mon-service-id2").toJson)).futureValue

    test

  }

  private def testHistoValues[T](vals: Seq[JsValue], name: String, value: T)(implicit reads: Reads[T]): Unit = {
    val v = vals
      .find(j => (j \ "name").as[String] == name)
      .getOrElse(throw new IllegalArgumentException(s"$name not found in $vals"))
    (v \ "data")
      .as[Seq[JsArray]]
      .map { arr =>
        (arr \ 1).as[T]
      }
      .foreach {
        _ mustBe value
      }

  }

  private def event(ts: DateTime,
                    status: Int,
                    duration: Long,
                    overhead: Long,
                    dataIn: Long,
                    dataOut: Long): AnalyticEvent = {
    GatewayEvent(
      `@id` = IdGenerator.nextId(1024).toString,
      `@timestamp` = ts,
      `@calledAt` = ts,
      reqId = IdGenerator.nextId(1024).toString,
      parentReqId = None,
      protocol = "http",
      to = Location("localhost", "http", "/"),
      target = Location("localhost", "http", "/"),
      url = "/toto",
      method = "GET",
      from = "from",
      env = "prod",
      duration = duration,
      overhead = overhead,
      cbDuration = 0L,
      overheadWoCb = 0L,
      callAttempts = 0,
      data = DataInOut(dataIn, dataOut),
      status = status,
      headers = Seq.empty,
      headersOut = Seq.empty,
      identity = None,
      gwError = None,
      `@serviceId` = "mon-service-id",
      `@service` = "mon-service",
      `@product` = "mon-product",
      responseChunked = false,
      descriptor = Some(
        ServiceDescriptor(id = "123456",
                          groupId = "test",
                          name = "name",
                          env = "prod",
                          domain = "toto.com",
                          subdomain = "")
      ),
      remainingQuotas = RemainingQuotas(),
      viz = None
    )
  }

  val testGroup = new ServiceGroup(
    id = "test-group",
    name = "Test group",
    description = "A test group"
  )

  def serviceDescriptor(serviceId: String) = {
    new ServiceDescriptor(
      id = serviceId,
      groupId = testGroup.id,
      name = "mon-service",
      env = "prod",
      domain = "foo.bar",
      subdomain = "api",
      targets = Seq(
        Target(host = "127.0.0.1:9999", scheme = "http")
      ),
      enabled = true,
      metadata = Map.empty,
      chaosConfig = ChaosConfig._fmt.reads(Json.parse("""{
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
                                                        |}""".stripMargin)).get
    )
  }

  def setUpEvent(seq: AnalyticEvent*): Unit = {
    implicit val ec: ExecutionContext = otoroshiComponents.executionContext
    implicit val env: Env             = otoroshiComponents.env
    analytics.publish(seq).futureValue
  }

  private def getStatus(i: Int): Int = {
    i % 10 match {
      case v if v < 5          => 200
      case v if v > 5 && v > 7 => 400
      case _                   => 500
    }
  }

}
