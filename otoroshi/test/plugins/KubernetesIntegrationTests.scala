package plugins

import akka.Done
import akka.stream.scaladsl.Source
import com.dimafeng.testcontainers.GenericContainer
import functional.PluginsTestSpec
import io.netty.handler.ssl.SslContextBuilder
import org.testcontainers.containers.output.ToStringConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.{BindMode, Network}
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.MountableFile
import otoroshi.security.IdGenerator
import otoroshi.utils.syntax.implicits.{BetterJsValueReader, BetterSyntax}
import play.api.libs.json.Json
import reactor.core.publisher.Mono
import reactor.netty.ByteBufFlux
import reactor.netty.http.client.HttpClient

import java.io.{File, FileInputStream}
import java.nio.file.{Files, Paths, StandardOpenOption}
import java.security.cert.CertificateFactory
import scala.annotation.tailrec
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Future, Promise}
import scala.sys.process._

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

  def deployK3s(registries: Option[String] = None): GenericContainer = {
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

      registries.foreach { path =>
        c.withCopyToContainer(
          MountableFile.forHostPath(path),
          "/etc/rancher/k3s/registries.yaml"
        )
      }
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

  def applyManifest(kubectlContainer: GenericContainer, manifestFilename: String, namespace: String = "default") = {
    println(s"Apply manifest: $manifestFilename in namespace: $namespace")
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

  def prepareManifest(
      manifestFilename: String,
      registryUrl: String
  ): Future[String] = Future {
    println(s"Preparing manifest: $manifestFilename with registry: $registryUrl")

    val resourceUrl = getClass.getResource(s"/kubernetes/$manifestFilename")
    if (resourceUrl == null) {
      throw new RuntimeException(s"Manifest not found: /kubernetes/$manifestFilename")
    }

    val manifestPath    = Paths.get(resourceUrl.toURI)
    val originalContent = Files.readString(manifestPath)

    val updatedContent = originalContent.replace("@@IMAGE_FROM_REGISTRY@@", registryUrl)

    val kubernetesDir = Paths.get(getClass.getResource("/kubernetes").toURI)
    val tmpDir        = kubernetesDir.resolve("tmp")

    // Create tmp directory if it doesn't exist
    if (!Files.exists(tmpDir)) {
      Files.createDirectories(tmpDir)
    }

    val tempFileName = s"prepared-$instanceId-$manifestFilename"
    val tempFile     = tmpDir.resolve(tempFileName)
    Files.writeString(tempFile, updatedContent)

    println(s"✓ Prepared manifest at: ${tempFile.toAbsolutePath}")
    tempFileName
  }
  def build() = {
    val projectRoot                            = new File("../..").getCanonicalFile
    val otoroshiPath                           = new File(projectRoot, "otoroshi").getAbsolutePath
    val outputJar                              = new File("/tmp/otoroshi.jar")
    var sbtContainer: Option[GenericContainer] = None

    if (outputJar.exists() && outputJar.length() > 0) {} else {
      println(s"Building from: $otoroshiPath to ${outputJar.getAbsolutePath}")

      val logConsumer = new ToStringConsumer()

      sbtContainer = new GenericContainer(
        "sbtscala/scala-sbt:eclipse-temurin-17.0.15_6_1.11.7_3.7.4"
      ).configure { c =>
        // Mount the source code
        c.withFileSystemBind(otoroshiPath, "/app", BindMode.READ_WRITE)
        c.withWorkingDirectory("/app")
        c.withCommand("tail", "-f", "/dev/null")
        c.withLogConsumer(logConsumer)
      }.some

      sbtContainer.get.start()

      println("Running sbt packageBin...")
      val result = sbtContainer.get.execInContainer(
        "sh",
        "-c",
        "cd /app/otoroshi && sbt ';set Test / skip := true;clean;compile;dist;assembly'"
      )

      // Print output
      println("=== Build Output ===")
      println(result.getStdout)

      if (result.getExitCode != 0) {
        System.err.println("=== Build Errors ===")
        System.err.println(result.getStderr)
        throw new RuntimeException(s"Build failed with exit code: ${result.getExitCode}")
      }

      sbtContainer.get.copyFileFromContainer(
        "/app/otoroshi/target/scala-2.12/otoroshi.jar",
        outputJar.getAbsolutePath // Destination on host
      )
      println(s"✓ JAR copied to: ${outputJar.getAbsolutePath}")
    }

    val registry = GenericContainer("registry:3", exposedPorts = Seq(5000))
      .configure { c =>
        {
          c.withNetwork(network)
          c.withNetworkAliases("registry")

        }
      }

    registry.start()

    val registryPort = registry.mappedPort(5000)
    val registryUrl  = s"${registry.host}:${registryPort}"
    println(s"Local registry running at: $registryUrl")
    val imageName    = "otoroshi-local:latest"
    val completeName = s"$registryUrl/$imageName"

    println(s"✅ Image to build: $completeName")
    // registry:5000/otoroshi-local:latest

    val dockerImage = new ImageFromDockerfile(imageName)
      .withDockerfileFromBuilder { builder =>
        builder
          .from("eclipse-temurin:17")
          .run("mkdir -p /usr/app")
          .workDir("/usr/app")
          .copy("otoroshi.jar", "/usr/app")
          .copy("entrypoint-jar.sh", "/usr/app/")
          .entryPoint("./entrypoint-jar.sh")
          .expose(8080)
          .cmd("")
          .build()
      }
      .withFileFromPath("otoroshi.jar", Paths.get("./target/otoroshi.jar"))
      .withFileFromPath("entrypoint-jar.sh", Paths.get("./scripts/entrypoint-jar.sh"))

    s"docker push $imageName".!

    val registriesYamlContent =
      s"""
     |mirrors:
     |  "$registryUrl":
     |    endpoint:
     |      - "http://$registryUrl"
     |""".stripMargin

    val tmpYamlPath = Paths.get("/tmp/registries.yaml")
    Files.write(
      tmpYamlPath,
      registriesYamlContent.getBytes(),
      StandardOpenOption.CREATE,
      StandardOpenOption.TRUNCATE_EXISTING
    )

    val k3sContainer: GenericContainer = deployK3s(tmpYamlPath.toAbsolutePath.toString.some)
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
      leaderFilename   <- prepareManifest("leader.yaml", completeName)
      _                <- applyManifest(kubectlContainer, s"tmp/$leaderFilename", namespace)
      _                <- waitForReady(Seq("kubectl", "get", "pods", "-n", namespace), kubectlContainer)
      _                <- call(k3sContainer, "Wait leader health ...", "otoroshi.k3s.local", "/health")
      _                <- cleanup(k3sContainer, kubectlContainer)
      _                <- Future {
                            registry.stop()
                            sbtContainer.map(_.stop())
                          }
    } yield {}

    // TODO -The JAR is built and pushed to the registry, but the image cannot be pulled.
    // Consider avoiding the local registry and pushing the image directly to Docker without creating a new registry.
  }
}
