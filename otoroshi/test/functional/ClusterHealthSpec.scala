package functional

import com.typesafe.config.ConfigFactory
import otoroshi.api.Otoroshi
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.core.server.ServerConfig

import java.nio.file.Files
import scala.concurrent.duration.*

// the health of a cluster member: a worker is not ready until it got the state of the cluster once, and stays ready
// when its leader goes away as it keeps serving the state it has. a leader does not depend on its workers
class ClusterHealthSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration =
    Configuration(
      ConfigFactory
        .parseString(s"""
          |otoroshi.cluster.mode = "leader"
          |""".stripMargin)
        .resolve()
    ).withFallback(configuration)

  private var workers = Seq.empty[Otoroshi]

  private def startWorker(leaderPort: Int): Int = {
    val workerPort = TargetService.freePort
    val worker     = Otoroshi(
      ServerConfig(
        address = "0.0.0.0",
        port = Some(workerPort),
        sslPort = None,
        rootDir = Files.createTempDirectory("otoroshi-cluster-health-worker").toFile
      ),
      ConfigFactory
        .parseString(s"""
          |otoroshi.cluster.mode = "worker"
          |otoroshi.cluster.leader.url = "http://127.0.0.1:$leaderPort"
          |otoroshi.cluster.worker.state.pollEvery = 1000
          |otoroshi.cluster.worker.state.retries = 1
          |otoroshi.cluster.worker.state.timeout = 1000
          |app.boot.waitForFirstClusterFetch = false
          |""".stripMargin)
        .resolve()
    ).start()
    workers = workers :+ worker
    workerPort
  }

  private def health(instancePort: Int, path: String): (Int, JsValue) = {
    val resp = ws.url(s"http://127.0.0.1:$instancePort$path").withRequestTimeout(5.seconds).get().futureValue
    (resp.status, resp.json)
  }

  private def awaitReady(instancePort: Int): Int = {
    val deadline = System.currentTimeMillis() + 60000L
    var status   = health(instancePort, "/ready")._1
    while (status != 200 && System.currentTimeMillis() < deadline) {
      Thread.sleep(1000)
      status = health(instancePort, "/ready")._1
    }
    status
  }

  private var syncedWorkerPort = 0

  "The health of a cluster member" should {
    "warm up" in {
      startOtoroshi()
    }
    "not be ready for a worker that never got the state of the cluster" in {
      // nothing listens on the port of its leader
      val workerPort = startWorker(TargetService.freePort)
      await(5.seconds) // a few polls of the state, all failing
      // the request handler of a worker answers every request, the health ones included, until the first sync
      Seq("/health", "/ready").foreach { path =>
        val (status, json) = health(workerPort, path)
        withClue(s"$path ${json.stringify}") {
          status must not be 200
          json.select("Otoroshi-Error").asOpt[String] mustBe Some("Waiting for first Otoroshi leader sync.")
        }
      }
    }
    "be ready for a worker once it got the state of the cluster" in {
      syncedWorkerPort = startWorker(port)
      awaitReady(syncedWorkerPort) mustBe 200
      val (_, json) = health(syncedWorkerPort, "/health")
      json.select("cluster").select("worker").select("initialized").asOpt[Boolean] mustBe Some(true)
      // the leader does not depend on its workers, one of them was never synced
      health(port, "/ready")._1 mustBe 200
    }
    "stay ready for a worker when its leader goes away" in {
      stopAll()
      await(5.seconds) // a few polls of the state, all failing
      val (status, json) = health(syncedWorkerPort, "/ready")
      withClue(json.stringify)(status mustBe 200)
    }
    "shutdown" in {
      workers.foreach(_.stop())
    }
  }
}
