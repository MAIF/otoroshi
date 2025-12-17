package plugins

import akka.http.scaladsl.util.FastFuture
import com.dimafeng.testcontainers.GenericContainer
import functional.PluginsTestSpec
import io.netty.handler.ssl.SslContextBuilder
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.libs.json.Json
import reactor.core.publisher.Mono
import reactor.netty.ByteBufFlux
import reactor.netty.http.client.HttpClient

import java.io.FileInputStream
import java.nio.file.{Files, Paths}
import java.security.cert.CertificateFactory
import scala.concurrent.{Future, Promise}

class KubernetesIntegrationTests(parent: PluginsTestSpec) {

  import parent._

  val instanceId = IdGenerator.uuid

  def isReady(k3sContainer: GenericContainer) = {
    println(".Waiting for k3s to start..")
    var ready       = false
    val maxAttempts = 60
    var attempts    = 0

    while (!ready && attempts < maxAttempts) {
      try {
        val logs = k3sContainer.logs
        if (logs.contains("bootstrap done")) {
          ready = true
          println("k3s is ready!")
        } else {
          Thread.sleep(2000)
          attempts += 1
        }
      } catch {
        case _: Exception =>
          Thread.sleep(2000)
          attempts += 1
      }
    }

    if (!ready) {
      throw new RuntimeException("k3s failed to start within timeout")
    }
  }

  def deployK3s(): GenericContainer = {
    val k3sContainer = GenericContainer(
      dockerImage = "rancher/k3s:v1.28.5-k3s1",
      exposedPorts = Seq(6443),
      command = Seq("server", "--disable=traefik")
    ).configure { c =>
      println("Configuring container...")
      c.withPrivilegedMode(true)
      c.withEnv("K3S_KUBECONFIG_OUTPUT", "/output/kubeconfig.yaml")
      c.withEnv("K3S_KUBECONFIG_MODE", "666")
    }

    k3sContainer.start()
    isReady(k3sContainer)

    k3sContainer.copyFileFromContainer(
      "/var/lib/rancher/k3s/server/tls/client-admin.crt",
      s"/tmp/${instanceId}client-admin.crt"
    )
    k3sContainer.copyFileFromContainer(
      "/var/lib/rancher/k3s/server/tls/client-admin.key",
      s"/tmp/${instanceId}client-admin.key"
    )
    k3sContainer.copyFileFromContainer(
      "/var/lib/rancher/k3s/server/tls/server-ca.crt",
      s"/tmp/${instanceId}server-ca.crt"
    )

    k3sContainer
  }

  def getNettyClient(container: GenericContainer): HttpClient = {
    val tlsBase = Paths.get("/tmp")

    val clientCertFile = new FileInputStream(tlsBase.resolve(s"${instanceId}client-admin.crt").toFile)
    val clientKeyFile  = new FileInputStream(tlsBase.resolve(s"${instanceId}client-admin.key").toFile)
    val caCertFile     = new FileInputStream(tlsBase.resolve(s"${instanceId}server-ca.crt").toFile)

    val pureNettyClient = HttpClient
      .create()
      .host(container.host)
      .port(container.mappedPort(6443))
      .protocol(reactor.netty.http.HttpProtocol.HTTP11)
      .secure { spec =>
        val certFactory = CertificateFactory.getInstance("X.509")
        val caCert      = certFactory
          .generateCertificate(caCertFile)
          .asInstanceOf[java.security.cert.X509Certificate]

        val sslCtxBuilder = SslContextBuilder
          .forClient()
          .trustManager(caCert)
          .keyManager(clientCertFile, clientKeyFile)

        spec.sslContext(sslCtxBuilder)
      }

    pureNettyClient
  }

  def mintToken(container: GenericContainer): Future[String] = {
    val body = Json.obj(
      "apiVersion" -> "authentication.k8s.io/v1",
      "kind"       -> "TokenRequest",
      "spec"       -> Json.obj(
        "audiences"         -> Json.arr("https://kubernetes.default.svc"),
        "expirationSeconds" -> 3600
      )
    )

    val pureNettyClient = getNettyClient(container)

    val promise = Promise[String]()
    pureNettyClient
      .post()
      .uri("/api/v1/namespaces/default/serviceaccounts/default/token")
      .send(ByteBufFlux.fromString(Mono.just(Json.stringify(body))))
      .responseSingle((res, content) => content.asString())
      .doOnError(error => promise.failure(error))
      .subscribe(
        json => {
          promise.success(
            Json
              .parse(json)
              .selectAsObject("status")
              .selectAsString("token")
          )
        },
        err => promise.failure(err)
      )
    promise.future
  }

  def callReadyz(container: GenericContainer, token: String) = {
    println("callReadyz", token)

    val pureNettyClient = getNettyClient(container)

    val promise = Promise[Unit]()
    pureNettyClient
      .headers(h => h.set("Authorization", s"Bearer $token"))
      .get()
      .uri("/readyz")
      .responseSingle { (res, content) =>
        // Need to return a Mono
        content.asString().map { body =>
          val status = res.status().code()
          println(s"readyz response: status=$status, body=$body")

          if (status == 200 && body == "ok") {
            println("✓ readyz check passed")
            promise.success(())
          } else {
            promise.failure(new RuntimeException(s"readyz check failed: status=$status, body=$body"))
          }

          body // Return something to satisfy Mono requirement
        }
      }
      .doOnError(error => {
        println(s"ERROR during readyz check: ${error.getMessage}")
        promise.failure(error)
      })
      .subscribe(
        _ => {}, // Already handled in the map above
        err => promise.failure(err)
      )
    promise.future
  }

  def createKubectl(k3sContainer: GenericContainer) = {
    println("createKubectl")
    val kubeconfigResult = k3sContainer.execInContainer("cat", "/etc/rancher/k3s/k3s.yaml")
    if (kubeconfigResult.getExitCode != 0) {
      throw new RuntimeException(s"Failed to get kubeconfig: ${kubeconfigResult.getStderr}")
    }
    val kubeconfig       = kubeconfigResult.getStdout

    val k3sInternalIp      =
      k3sContainer.container.getContainerInfo.getNetworkSettings.getNetworks.values().iterator().next().getIpAddress
    val modifiedKubeconfig =
      kubeconfig.replace("https://127.0.0.1:6443", s"https://$k3sInternalIp:${k3sContainer.mappedPort(6443)}")

    println(s"k3s internal IP: $k3sInternalIp")

    val tempKubeconfig = Files.createTempFile("kubeconfig", ".yaml")
    Files.write(tempKubeconfig, modifiedKubeconfig.getBytes())

    val kubectlContainer = GenericContainer(
      dockerImage = "bitnami/kubectl:latest"
    ).configure { c =>
      c.withCommand("sleep", "infinity")
      c.withFileSystemBind(
        tempKubeconfig.toString,
        "/root/.kube/config",
        org.testcontainers.containers.BindMode.READ_ONLY
      )
      c.withNetwork(k3sContainer.network)
    }

    kubectlContainer.start()
    println("kubectl container started!")

    // Test kubectl connection
    Thread.sleep(2000)
    val testResult = kubectlContainer.execInContainer("kubectl", "version", "--client")
    println(s"kubectl version: ${testResult.getStdout}")
  }

  def run() = {
    val k3sContainer: GenericContainer = deployK3s()

    for {
      token <- mintToken(k3sContainer)
      _     <- callReadyz(k3sContainer, token)
    } yield {
      createKubectl(k3sContainer)
    }
  }

}
