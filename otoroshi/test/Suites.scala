import java.io.File

import com.typesafe.config.ConfigFactory
import functional._
import org.apache.commons.io.FileUtils
import org.scalatest.{BeforeAndAfterAll, Suite, Suites}
import play.api.Configuration

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
      new BasicSpec(name, config),
      new AdminApiSpec(name, config),
      new ProgrammaticApiSpec(name, config),
      new CircuitBreakerSpec(name, config),
      new AlertAndAnalyticsSpec(name, config),
      // new AnalyticsSpec(name, config),
      new ApiKeysSpec(name, config),
      new CanarySpec(name, config),
      new QuotasSpec(name, config),
      new SidecarSpec(name, config),
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
      new ServicesApiSpec(name, config),
      new ApikeyGroupApiSpec(name, config),
      new ApikeyServiceApiSpec(name, config),
      new ApikeyApiSpec(name, config)
    )
    Option(System.getenv("TEST_ANALYTICS")) match {
      case Some("true") => suites :+ new AnalyticsSpec(name, config)
      case _            => suites
    }
  }
}

class OtoroshiTests extends Suites(OtoroshiTests.getSuites(): _*) with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    FileUtils.deleteDirectory(new File("./target/leveldbs"))
  }

  override protected def afterAll(): Unit = {
    FileUtils.deleteDirectory(new File("./target/leveldbs"))
  }
}

class OpenapiGeneratorTests
    extends Suites(
      new OpenApiSpec()
    )

class PluginDocTests
  extends Suites(
    new PluginDocSpec()
  )

class DevOtoroshiTests
    extends Suites(
      new AdminApiSpec("DEV", Configurations.InMemoryConfiguration)
    )
