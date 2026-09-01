import com.typesafe.config.ConfigFactory
import functional.*
import org.scalatest.{BeforeAndAfterAll, Suite, Suites}
import otoroshi.greenscore.GreenScoreTestSpec
import play.api.Configuration
import tools.*

import scala.util.Try

object Configurations {

  val InMemoryConfiguration = Configuration(
    ConfigFactory
      .parseString("""
         |{
         |  app.storage = "inmemory"
         |}
       """.stripMargin)
      .resolve()
  )

  val RedisConfiguration = Configuration(
    ConfigFactory
      .parseString("""
         |{
         |  app.storage = "redis"
         |}
       """.stripMargin)
      .resolve()
  )

  val CassandraNaiveConfiguration = Configuration(
    ConfigFactory
      .parseString("""
         |{
         |  app.storage = "cassandra-naive"
         |}
       """.stripMargin)
      .resolve()
  )

  val CassandraConfiguration = Configuration(
    ConfigFactory
      .parseString("""
                     |{
                     |  app.storage = "cassandra"
                     |}
                   """.stripMargin)
      .resolve()
  )

  val PgConfiguration = Configuration(
    ConfigFactory
      .parseString("""
                     |{
                     |  app.storage = "experimental-pg"
                     |  app.pg.testMode = true
                     |}
       """.stripMargin)
      .resolve()
  )
}

object OtoroshiTests {

  def getNameAndConfig(): (String, Configuration) = {
    Try(Option(System.getenv("TEST_STORE"))).toOption.flatten.getOrElse("inmemory") match {
      case "redis"           => ("Redis", Configurations.RedisConfiguration)
      case "inmemory"        => ("InMemory", Configurations.InMemoryConfiguration)
      case "cassandra-naive" => ("Cassandra Naive", Configurations.CassandraNaiveConfiguration)
      case "cassandra"       => ("Cassandra", Configurations.CassandraConfiguration)
      case "experimental-pg" => ("Experimental PG", Configurations.PgConfiguration)
      case e                 => throw new RuntimeException(s"Bad storage value from conf: $e")
    }
  }

  def getSuites(): Seq[Suite] = {
    val (name, config) = getNameAndConfig()
    val suites         = Seq(
      new BasicSpec,
      new AdminApiSpec(name, config),
      new CircuitBreakerSpec(name, config),
      new AlertAndAnalyticsSpec(name, config),
      // new AnalyticsSpec(name, config),
      new ApiKeysSpec(name, config),
      new CanarySpec(name, config),
      new QuotasSpec(name, config),
      new JWTVerificationSpec(name, config),
      new JWTVerificationRefSpec(name, config),
      new SnowMonkeySpec(name, config),
      new Version149Spec(name, config),
      new Version1410Spec(name, config),
      new Version1413Spec(name, config),
      // new WebsocketSpec(name, config),
      new ServiceGroupApiSpec(name, config),
      new TcpServiceApiSpec(name, config),
      new ScriptApiSpec(name, config),
      new AuthModuleConfigApiSpec(name, config),
      new ClientValidatorApiSpec(name, config),
      new JWTVerifierApiSpec(name, config),
      new CertificateApiSpec(name, config),
      //[REMOVE SERVICEDESC] new ServicesApiSpec(name, config),
      new ApikeyGroupApiSpec(name, config),
      //[REMOVE SERVICEDESC] new ApikeyServiceApiSpec(name, config),
      new ApikeyApiSpec(name, config),
      new ApikeyBearerSpec(),
      new ThrottlingTestSpec(),
      new Log4ShellSpec()
    )
    Option(System.getenv("TEST_ANALYTICS")) match {
      case Some("true") => suites :+ new AnalyticsSpec(name, config)
      case _            => suites
    }
  }
}

class OtoroshiTests extends Suites(OtoroshiTests.getSuites()*) with BeforeAndAfterAll {}

class DevOtoroshiTests
    extends Suites(
      new AdminApiSpec("DEV", Configurations.InMemoryConfiguration)
    )

// pure logic specs: no otoroshi instance, no datastore, no network. the whole suite runs in seconds,
// so it belongs in every run rather than being reachable only by name.
class UnitTests
    extends Suites(
      new ApiBusinessRulesSpec(),
      new plugins.HttpSignatureRfc9421Spec(),
      new WebAuthnSpec(),
      new MapFilterSpec(),
      new JsonPathSpec(),
      new ElasticWritesAnalyticsSpec(),
      new LettuceSslOptionsSpec(),
      new VersionSpec(),
      // the three deterministic tree router specs. the two others of NgTreeRouterTests stay out: one
      // is a benchmark over a million routes with no assertion, the other downloads the openapi spec
      // of master from github before matching against it
      new NgTreeRouterSpec(),
      new NgTreeRouterPathParamsSpec(),
      new NgTreeRouterWildcardSpec()
    )

class MapFilterTest
    extends Suites(
      new MapFilterSpec()
    )

class ExpressionLanguageTests
    extends Suites(
      new ExpressionLanguageSpec(Configurations.InMemoryConfiguration)
    )

// differential test and comparison report between the regular json path reader and the opt in fast
// one. prints a report, so it is its own suite rather than part of the default run.
class JsonPathTests
    extends Suites(
      new JsonPathFastReaderSpec(Configurations.InMemoryConfiguration)
    )

class BackendMtlsTests
    extends Suites(
      new BackendMtlsSpec(Configurations.InMemoryConfiguration)
    )

class FrontendTlsTests
    extends Suites(
      new FrontendTlsSpec(Configurations.InMemoryConfiguration)
    )

class ApiPlanMtlsTests
    extends Suites(
      new ApiPlanMtlsSpec(Configurations.InMemoryConfiguration)
    )

class Log4ShellTests
    extends Suites(
      new Log4ShellSpec()
    )

// acme end-to-end against a real acme server (pebble) started with testcontainers: needs docker, so it is
// its own suite rather than part of the default run.
class LetsEncryptTests
    extends Suites(
      new LetsEncryptSpec(Configurations.InMemoryConfiguration)
    )

class NgTreeRouterTests
    extends Suites(
      new NgTreeRouterOpenapiWithEnvSpec(Configurations.InMemoryConfiguration),
      new NgTreeRouterWildcardSpec(),
      //new NgTreeRouterRealLifeSpec(),
      new NgTreeRouterPathParamsSpec(),
      new NgTreeRouterSpec(),
      new NgTreeRouterWithEnvSpec(Configurations.InMemoryConfiguration)
    )

class OpenapiGeneratorTests
    extends Suites(
      new OpenApiSpec()
    )

class PluginDocTests
    extends Suites(
      new PluginDocSpec()
    )

class PluginDocNextTests
    extends Suites(
      new PluginDocNextSpec()
    )

class PluginDocNgTests
    extends Suites(
      new PluginDocNgSpec()
    )

class OneShotTests
    extends Suites(
      new MapFilterSpec()
    )

class ConfigCleanerTests
    extends Suites(
      new ConfigurationCleanupSpec()
    )

class CircuitBreakerTests
    extends Suites(
      new CircuitBreakerSpec("InMemory", Configurations.InMemoryConfiguration)
    )

class AnalyticsTests
    extends Suites(
      new AlertAndAnalyticsSpec("InMemory", Configurations.InMemoryConfiguration)
    )

class GreenScoreTests
    extends Suites(
      new GreenScoreTestSpec("GreenScore", Configurations.InMemoryConfiguration)
    )

//class ApiKeysTest
//    extends Suites(
//      new ApiKeysSpec("ApiKeysSpec", Configurations.InMemoryConfiguration)
//    )

class PgDatastoreTests
    extends Suites(
      new PgDatastoreSpec()
    )

class LettuceDatastoreTests
    extends Suites(
      new LettuceDatastoreSpec()
    )
