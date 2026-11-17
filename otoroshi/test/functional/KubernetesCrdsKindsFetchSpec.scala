package functional

import org.apache.pekko.http.scaladsl.model.HttpRequest
import otoroshi.env.Env
import otoroshi.plugins.jobs.kubernetes.{KubernetesClient, KubernetesConfig, KubernetesCrdsKinds}
import play.api.Configuration
import play.api.libs.json.*

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

// the kubernetes job only fetches the kinds of entities (crds) it reads, globally and by namespace (#2929). the api
// server is a fake one answering the lists of crds
class KubernetesCrdsKindsFetchSpec(name: String, configurationSpec: => Configuration) extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration) =
    Configuration.from(Map.empty[String, Any]).withFallback(configurationSpec).withFallback(configuration)

  startOtoroshi()

  val requested = new ConcurrentLinkedQueue[String]()

  def item(kind: String, namespace: String, name: String): JsObject = Json.obj(
    "apiVersion" -> "proxy.otoroshi.io/v1",
    "kind"       -> kind,
    "metadata"   -> Json.obj("name" -> name, "namespace" -> namespace, "uid" -> s"$namespace-$name"),
    "spec"       -> Json.obj("name" -> name)
  )

  val namespaces = Seq("team-a", "team-sandbox", "other")

  // GET /apis/proxy.otoroshi.io/v1/<plural> lists every namespace, /namespaces/<ns>/<plural> one of them
  val apiServer = TargetService(
    None,
    "/",
    "application/json",
    { (r: HttpRequest) =>
      val path   = r.uri.path.toString()
      requested.add(path)
      val parts  = path.stripPrefix("/apis/proxy.otoroshi.io/v1/").split("/").toSeq
      val (nses, plural) = parts match {
        case Seq("namespaces", ns, plural) => (Seq(ns), plural)
        case Seq(plural)                   => (namespaces, plural)
        case _                             => (Seq.empty, "")
      }
      Json.stringify(Json.obj("items" -> JsArray(nses.map(ns => item(plural, ns, s"$plural-of-$ns")))))
    }
  ).await()

  def client(conf: JsObject, watched: Seq[String] = Seq("*")): KubernetesClient = {
    val env: Env = otoroshiComponents.env
    val endpoint = s"http://127.0.0.1:${apiServer.port}"
    val config   = KubernetesConfig
      .theConfig(conf ++ Json.obj("endpoint" -> endpoint, "token" -> "token"))(using env, env.otoroshiExecutionContext)
      .copy(
        endpoint = endpoint,
        token = Some("token"),
        userPassword = None,
        caCert = None,
        clientCert = None,
        clientCertKey = None,
        trust = false,
        namespaces = watched,
        crdsKinds = KubernetesCrdsKinds.read(conf)
      )
    new KubernetesClient(config, env)
  }

  def fetch(cli: KubernetesClient, plural: String): Seq[String] =
    cli.fetchOtoroshiResources[JsValue](plural, Reads.JsValueReads).futureValue.map(_.path).sorted

  def requestedFor(plural: String): Seq[String] = requested.asScala.toSeq.filter(_.endsWith(s"/$plural"))

  s"[$name] The kinds of entities read by the kubernetes job" should {

    val conf = Json.obj(
      "crdsKinds" -> Json.obj(
        "exclude"    -> Json.arr("admins"),
        "namespaces" -> Json.obj(
          "team-*"       -> Json.obj("include" -> Json.arr("routes", "backends")),
          "team-sandbox" -> Json.obj("exclude" -> Json.arr("backends"))
        )
      )
    )

    "read every kind in every namespace without crdsKinds" in {
      fetch(client(Json.obj()), "admins") mustBe Seq("other/admins-of-other", "team-a/admins-of-team-a", "team-sandbox/admins-of-team-sandbox")
    }

    "not even fetch a kind the global rule excludes" in {
      requested.clear()
      fetch(client(conf), "admins") mustBe empty
      requestedFor("admins") mustBe empty
    }

    "keep the resources of the namespaces whose rules read their kind" in {
      fetch(client(conf), "routes") mustBe Seq("other/routes-of-other", "team-a/routes-of-team-a", "team-sandbox/routes-of-team-sandbox")
      fetch(client(conf), "backends") mustBe Seq("other/backends-of-other", "team-a/backends-of-team-a")
      fetch(client(conf), "apikeys") mustBe Seq("other/apikeys-of-other")
    }

    "not fetch the watched namespaces whose rules exclude the kind" in {
      requested.clear()
      val cli = client(conf, watched = Seq("team-a", "team-sandbox", "other"))
      fetch(cli, "backends") mustBe Seq("other/backends-of-other", "team-a/backends-of-team-a")
      requestedFor("backends") must contain theSameElementsAs Seq(
        "/apis/proxy.otoroshi.io/v1/namespaces/team-a/backends",
        "/apis/proxy.otoroshi.io/v1/namespaces/other/backends"
      )
    }

    "shutdown" in {
      apiServer.stop()
      stopAll()
    }
  }
}
