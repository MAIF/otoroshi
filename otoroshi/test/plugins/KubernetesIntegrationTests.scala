package plugins

import akka.Done
import akka.stream.scaladsl.Source
import com.dimafeng.testcontainers.GenericContainer
import com.github.dockerjava.api.model.ExposedPort
import functional.PluginsTestSpec
import io.netty.handler.ssl.SslContextBuilder
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.BetterJsValueReader
import play.api.libs.json.Json
import reactor.core.publisher.Mono
import reactor.netty.ByteBufFlux
import reactor.netty.http.client.HttpClient

import java.io.FileInputStream
import java.nio.file.{Files, Paths}
import java.security.cert.CertificateFactory
import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}

class KubernetesIntegrationTests(parent: PluginsTestSpec) {

  import parent._

  val instanceId = IdGenerator.uuid

  val network = Network.newNetwork()

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
      exposedPorts = Seq(6443, 31080),
      command = Seq("server", "--disable=traefik"),
      waitStrategy = Wait.forLogMessage(".*k3s is up and running.*", 1)
    ).configure { c =>
      println("Configuring container...")
      c.withPrivilegedMode(true)
      c.withEnv("K3S_KUBECONFIG_OUTPUT", "/output/kubeconfig.yaml")
      c.withEnv("K3S_KUBECONFIG_MODE", "666")
      c.withNetwork(network)
      c.withNetworkAliases("k3s")
//      c.withCreateContainerCmdModifier(cmd => {
//        cmd.withExposedPorts(ExposedPort.tcp(6443), ExposedPort.tcp(31080))
//      })
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

          body
        }
      }
      .doOnError(error => {
        println(s"ERROR during readyz check: ${error.getMessage}")
        promise.failure(error)
      })
      .subscribe(
        _ => {},
        err => promise.failure(err)
      )
    promise.future
  }

  def createKubectl(k3sContainer: GenericContainer) = {
    Future {
      println("createKubectl")
      val kubeconfigResult = k3sContainer.execInContainer("cat", "/etc/rancher/k3s/k3s.yaml")
      if (kubeconfigResult.getExitCode != 0) {
        throw new RuntimeException(s"Failed to get kubeconfig: ${kubeconfigResult.getStderr}")
      }
      val kubeconfig       = kubeconfigResult.getStdout

      val internalIp         =
        k3sContainer.container.getContainerInfo.getNetworkSettings.getNetworks.values().iterator().next().getIpAddress
      val modifiedKubeconfig = kubeconfig.replace("127.0.0.1:6443", s"$internalIp:6443")

      println(s"k3s internal IP: $internalIp")

      val tempKubeconfig = Files.createTempFile("kubeconfig", ".yaml")
      Files.write(tempKubeconfig, modifiedKubeconfig.getBytes())

      val resourceUrl = getClass.getResource("/kubernetes")
      if (resourceUrl == null) {
        throw new RuntimeException(s"Resource path not found: kubernetes")
      }

      val resourcePath = Paths.get(resourceUrl.toURI).toString

      val kubectlContainer = GenericContainer(
        dockerImage = "alpine/k8s:1.34.1"
      ).configure { c =>
        c.withCommand("sh", "-c", "while true; do sleep 30; done")
        c.withFileSystemBind(
          tempKubeconfig.toString,
          "/root/.kube/config",
          org.testcontainers.containers.BindMode.READ_ONLY
        )
        c.withFileSystemBind(
          resourcePath,
          "/manifests",
          org.testcontainers.containers.BindMode.READ_ONLY
        )
        c.withNetwork(network)
      }

      kubectlContainer.start()
      println("kubectl container started!")

      val testResult = kubectlContainer.execInContainer("kubectl", "version", "--client")
      println(s"kubectl version: ${testResult.getStdout}")

      kubectlContainer
    }
  }

  def cleanup(k3sContainer: GenericContainer, kubectlContainer: GenericContainer) = Future {
    val clientCert = s"/tmp/${instanceId}client-admin.crt"
    val clientKey  = s"/tmp/${instanceId}client-admin.key"
    val serverCa   = s"/tmp/${instanceId}server-ca.crt"

    Files.deleteIfExists(Paths.get(clientCert))
    Files.deleteIfExists(Paths.get(clientKey))
    Files.deleteIfExists(Paths.get(serverCa))

    network.close()
    k3sContainer.close()
    kubectlContainer.close()
  }

  def applyManifest(kubectlContainer: GenericContainer, manifestFilename: String, namespace: String = "default") =
    Future {
      val applyResult = kubectlContainer.execInContainer(
        "kubectl",
        "apply",
        "-f",
        s"/manifests/$manifestFilename",
        "-n",
        namespace
      )

      if (applyResult.getExitCode != 0) {
        println(s"Failed to apply manifests: ${applyResult.getStderr}")
        throw new RuntimeException("Failed to apply manifests")
      }

      println(s"Successfully applied manifests: ${applyResult.getStdout}")
    }

  def waitForResource(
      kubectlContainer: GenericContainer,
      resourceType: String,
      resourceName: String,
      namespace: String = "foo",
      timeoutSeconds: Int = 120
  ): Future[String] = {

    println(s"Waiting for $resourceType/$resourceName to be ready...")

    waitForReady(
      Seq(
        "kubectl",
        "get",
        s"$resourceType/$resourceName",
        s"--timeout=${timeoutSeconds}s",
        "-n",
        namespace,
        "-o",
        "jsonpath={.status}"
      ),
      kubectlContainer,
      timeoutSeconds
    )
  }

  def waitForReady(
      commands: Seq[String],
      kubectlContainer: GenericContainer,
      timeoutSeconds: Int = 120
  ): Future[String] = Future {

    @tailrec
    def check(attemptsLeft: Int): String = {
      if (attemptsLeft <= 0) {
        throw new RuntimeException(s"Timeout waiting after ${timeoutSeconds}s")
      }

      val getResult = kubectlContainer.execInContainer(commands: _*)
      val output    = getResult.getStdout
      val stderr    = getResult.getStderr

      println(s"Exit code: ${getResult.getExitCode}")
      println(s"Stdout: $output")
      println(s"Stderr: $stderr")

      if (getResult.getExitCode != 0) {
        println(s"Command failed, retrying... ($attemptsLeft left)")
        Thread.sleep(2000)
        check(attemptsLeft - 1)
      } else if (output.isEmpty || !output.contains("Running") || output.contains("Init")) {
        println(s"($attemptsLeft left)")
        Thread.sleep(2000)
        check(attemptsLeft - 1)
      } else {
        output
      }
    }

    check(timeoutSeconds / 2)
  }

  def call(k3sContainer: GenericContainer, waitingMessage: String, host: String, path: String): Future[Done] = {
    println(waitingMessage)

    val hostPort = k3sContainer.mappedPort(31080)

    Source
      .tick(1.millisecond, 1.second, ())
      .mapAsync(1) { _ =>
        ws
          .url(s"http://127.0.0.1:$hostPort$path")
          .withHttpHeaders("Host" -> host)
          .withRequestTimeout(1.second)
          .get()
          .map(r => {
            println(s"Status: ${r.status}, Body: ${r.body}")
            r.status mustBe play.mvc.Http.Status.OK
            r.status
          })
          .recover { case e =>
            println(s"Error: ${e.getMessage}")
            0
          }
      }
      .filter(_ == play.mvc.Http.Status.OK)
      .take(1)
      .run()
  }

  def clusterWithOneLeader() = {
    val k3sContainer: GenericContainer = deployK3s()
    val namespace                      = "foo"

    for {
      token            <- mintToken(k3sContainer)
      _                <- callReadyz(k3sContainer, token)
      kubectlContainer <- createKubectl(k3sContainer)
      _                <- applyManifest(kubectlContainer, "namespace.yaml")
      _                <- applyManifest(kubectlContainer, "common/serviceAccount.yaml", namespace)
      _                <- applyManifest(kubectlContainer, "common/crds.yaml")
      _                <- applyManifest(kubectlContainer, "common/rbac.yaml")
      _                <- applyManifest(kubectlContainer, "common/redis.yaml", namespace)
      _                <- applyManifest(kubectlContainer, "leader.yaml", namespace)
      _                <- waitForReady(Seq("kubectl", "get", "pods", "-n", namespace), kubectlContainer)
      _                <- call(k3sContainer, "Wait leader health ...", "otoroshi.k3s.local", "/health")
      _                <- cleanup(k3sContainer, kubectlContainer)
    } yield {}
  }

  def scanEntities() = {
    val k3sContainer: GenericContainer = deployK3s()
    val namespace                      = "foo"

    for {
      token            <- mintToken(k3sContainer)
      _                <- callReadyz(k3sContainer, token)
      kubectlContainer <- createKubectl(k3sContainer)
      _                <- applyManifest(kubectlContainer, "namespace.yaml")
      _                <- applyManifest(kubectlContainer, "common/serviceAccount.yaml", namespace)
      _                <- applyManifest(kubectlContainer, "common/crds.yaml")
      _                <- applyManifest(kubectlContainer, "common/rbac.yaml")
      _                <- applyManifest(kubectlContainer, "common/redis.yaml", namespace)
      _                <- applyManifest(kubectlContainer, "leader.yaml", namespace)
      _                <- waitForReady(Seq("kubectl", "get", "pods", "-n", namespace), kubectlContainer)
      _                <- call(k3sContainer, "Wait leader health ...", "otoroshi.k3s.local", "/health")
      _                <- applyManifest(kubectlContainer, "foo-route.yaml", namespace)
      _                <- call(k3sContainer, "Wait foo route", "foo.k3s.local", "/")
      _                <- cleanup(k3sContainer, kubectlContainer)
    } yield {}
  }

}
