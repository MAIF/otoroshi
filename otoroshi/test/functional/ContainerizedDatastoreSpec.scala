package functional

import com.dimafeng.testcontainers.GenericContainer
import org.apache.pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import otoroshi.env.Env
import otoroshi.next.models.NgPluginInstance
import otoroshi.next.plugins.OverrideHost
import otoroshi.next.plugins.api.NgPluginHelper
import otoroshi.security.IdGenerator
import play.api.http.Status
import play.api.libs.json.Json
import play.api.libs.ws.WSBodyReadables.given

import scala.concurrent.duration.DurationInt

/**
 * Shared scenario for the datastore specs that boot a real database in a container:
 *
 *   - start the container
 *   - boot a full otoroshi instance on top of it
 *   - exercise the datastore driver directly (raw get/set/incr/del)
 *   - create a route through the admin api (write), read it back (read) and actually call it
 *   - delete the route and check it's gone
 *
 * Concrete specs only provide the container and the otoroshi configuration pointing at it,
 * so each one can be run on its own (see PgDatastoreSpec / LettuceDatastoreSpec).
 */
trait ContainerizedDatastoreSpec extends OtoroshiSpec with BeforeAndAfterAll {

  def storeName: String
  def container: GenericContainer

  override def beforeAll(): Unit = {
    container.start()
    startOtoroshi()
    getOtoroshiRoutes().futureValue // warm up
    await(2.seconds)                // wait for router sync
  }

  override def afterAll(): Unit = {
    stopAll()
    container.stop()
  }

  s"Otoroshi with the '$storeName' datastore" should {

    "boot and answer on the admin api" in {
      val (json, status) = otoroshiApiCall("GET", "/api/routes").futureValue
      status mustBe Status.OK
      json.asOpt[Seq[play.api.libs.json.JsValue]].isDefined mustBe true
    }

    "read and write raw values through the datastore driver" in {
      given env: Env = otoroshiComponents.env
      val store      = env.datastores.rawDataStore
      val key        = s"${env.storageRoot}:tests:${IdGenerator.uuid}"
      val counterKey = s"$key-counter"

      store.set(key, ByteString("hello world"), None).futureValue mustBe true
      store.exists(key).futureValue mustBe true
      store.get(key).futureValue.map(_.utf8String) mustBe Some("hello world")

      store.incrby(counterKey, 3L).futureValue mustBe 3L
      store.incrby(counterKey, 2L).futureValue mustBe 5L

      store.set(s"$key-ttl", ByteString("expires"), Some(60000L)).futureValue mustBe true
      store.pttl(s"$key-ttl").futureValue must be > 0L

      store.del(Seq(key, counterKey, s"$key-ttl")).futureValue
      store.get(key).futureValue mustBe None
      store.exists(key).futureValue mustBe false
    }

    "persist a route and serve http calls through it" in {
      val domain = s"datastore-${IdGenerator.uuid}.oto.tools"
      val route  = createLocalRoute(
        Seq(NgPluginInstance(plugin = NgPluginHelper.pluginId[OverrideHost])),
        rawDomain = Some(domain),
        result = _ => Json.obj("message" -> "hello world")
      ).futureValue

      // the route has been written in the datastore and can be read back from it
      getOtoroshiRoutes().futureValue.exists(_.id == route.id) mustBe true

      // and the router actually serves it
      val resp = ws
        .url(s"http://127.0.0.1:$port/api")
        .withHttpHeaders("Host" -> domain)
        .get()
        .futureValue

      resp.status mustBe Status.OK
      (resp.json \ "message").as[String] mustBe "hello world"

      deleteOtoroshiRoute(route).futureValue
      await(2.seconds)
      getOtoroshiRoutes().futureValue.exists(_.id == route.id) mustBe false
    }
  }
}
