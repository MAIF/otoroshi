package functional

import com.sun.net.httpserver.HttpServer
import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.*
import play.api.Configuration
import play.api.libs.json.*
import play.api.libs.ws.DefaultBodyWritables.*
import play.api.libs.ws.{readableAsString, WSAuthScheme, WSResponse}

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.ExecutionContext

// the source config of a remote catalog can hold vault references, resolved when the catalog is fetched
class CatalogSourceSecretsSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  private def admin(method: String, path: String, body: Option[JsValue] = None): WSResponse = {
    val req = ws
      .url(s"http://127.0.0.1:$port$path")
      .withHttpHeaders("Host" -> "otoroshi-api.oto.tools", "Accept" -> "application/json")
      .withAuth("admin-api-apikey-id", "admin-api-apikey-secret", WSAuthScheme.BASIC)
      .withMethod(method)
    body
      .map(b => req.addHttpHeaders("Content-Type" -> "application/json").withBody(b.stringify))
      .getOrElse(req)
      .execute()
      .futureValue
  }

  "The source of a remote catalog" should {
    "warm up" in {
      startOtoroshi()
    }
    "resolve the vault references of its config" in {
      given env: Env             = otoroshiComponents.env
      given ec: ExecutionContext = env.otoroshiExecutionContext
      // the local vault reads the env of the global config
      val config                 = env.datastores.globalConfigDataStore.singleton().futureValue
      config.copy(env = Json.obj("catalog_token" -> "s3cr3t")).save().futureValue
      val authorization          = new AtomicReference[String]("")
      val source                 = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
      source.createContext(
        "/",
        exchange => {
          authorization.set(Option(exchange.getRequestHeaders.getFirst("Authorization")).getOrElse(""))
          val bytes = "[]".getBytes(StandardCharsets.UTF_8)
          exchange.getResponseHeaders.add("Content-Type", "application/json")
          exchange.sendResponseHeaders(200, bytes.length)
          exchange.getResponseBody.write(bytes)
          exchange.close()
        }
      )
      source.start()
      try {
        val path     = "/apis/catalogs.otoroshi.io/v1/remote-catalogs"
        val template = admin("GET", s"$path/_template").json.as[JsObject]
        val created  = admin(
          "POST",
          path,
          Some(
            template ++ Json.obj(
              "id"            -> "remote-catalog-secrets",
              "enabled"       -> true,
              "source_kind"   -> "http",
              "source_config" -> Json.obj(
                "url"     -> s"http://127.0.0.1:${source.getAddress.getPort}/catalog.json",
                "headers" -> Json.obj("Authorization" -> "Bearer ${vault://local/catalog_token}")
              )
            )
          )
        )
        withClue(created.body[String])(created.status mustBe 201)
        // the states of the extension take the catalog a few seconds after
        val deadline = System.currentTimeMillis() + 20000L
        while (authorization.get().isEmpty && System.currentTimeMillis() < deadline) {
          admin("POST", "/api/extensions/remote-catalogs/_deploy", Some(Json.arr(Json.obj("id" -> "remote-catalog-secrets"))))
          if (authorization.get().isEmpty) Thread.sleep(1000)
        }
        authorization.get() mustBe "Bearer s3cr3t"
        admin("DELETE", s"$path/remote-catalog-secrets")
      } finally {
        source.stop(0)
      }
    }
    "shutdown" in {
      stopAll()
    }
  }
}
