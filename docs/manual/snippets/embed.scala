package myapp

import java.nio.file.Files
import models.{ServiceDescriptor, Target, ApiKey, ServiceGroup}
import com.typesafe.config.ConfigFactory
import otoroshi.api.Otoroshi
import play.core.server.ServerConfig

object MyApp extends App {

  val rootDir = Files.createTempDirectory("otoroshi-embed").toFile

  val descriptor = ServiceDescriptor(
    id = "embed-test",
    name = "embed-test",
    env = "prod",
    subdomain = "api",
    domain = "oto.tools",
    targets = Seq(
      Target(
        host = s"127.0.0.1:8080",
        scheme = "http"
      )
    ),
    forceHttps = false,
    enforceSecureCommunication = false,
    publicPatterns = Seq("/.*")
  )

  val apiKey = ApiKey(
    clientId = "1234",
    clientSecret = "1234567890",
    clientName = "test-key",
    enabled = true,
    authorizedGroup = "embed-group"
  )

  val group = ServiceGroup(
    id = "embed-group",
    name = "Embed group"
  )

  val otoroshi = Otoroshi(
    ServerConfig(
      address = "0.0.0.0",
      port = Some(8888),
      rootDir = rootDir
    ),
    ConfigFactory.parseString(s"""
      |app {
      |  storage = "file"
      |  importFrom = "./my-state.json"
      |  env = "prod"
      |  adminapi {
      |    targetSubdomain = "otoroshi-admin-internal-api"
      |    exposedSubdomain = "otoroshi-api"
      |    defaultValues {
      |      backOfficeGroupId = "admin-api-group"
      |      backOfficeApiKeyClientId = "admin-api-apikey-id"
      |      backOfficeApiKeyClientSecret = "admin-api-apikey-secret"
      |      backOfficeServiceId = "admin-api-service"
      |    }
      |  }
      |  claim {
      |    sharedKey = "mysecret"
      |  }
      |  filedb {
      |    path = "./file"
      |  }
      |}
      """.stripMargin)
  ).start()

  // will be useful to use Otoroshi internal apis
  implicit val env = otoroshi.env
  implicit val ec = otoroshi.executionContext

  for {
    _ <- otoroshi.dataStores.serviceGroupDataStore.set(group)
    _ <- otoroshi.dataStores.apiKeyDataStore.set(apiKey)
    _ <- otoroshi.dataStores.serviceDescriptorDataStore.set(descriptor)
  } yield {
    // here your otoroshi is configured to serve http://127.0.0.1:8080 on http://api.oto.tools:8888
    // ...
    otoroshi.stop()
    System.exit(0)
  }
}




