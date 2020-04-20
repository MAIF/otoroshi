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

  def LevelDBConfiguration = Configuration(
    ConfigFactory
      .parseString(s"""
         |{
         |  app.storage = "leveldb"
         |  app.leveldb.path = "./target/leveldbs/test-${System.currentTimeMillis()}"
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

  val MongoConfiguration = Configuration(
    ConfigFactory
      .parseString("""
         |{
         |  app.storage = "mongo"
         |  app.mongo.testMode = true
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
      case "leveldb"         => ("LevelDB", Configurations.LevelDBConfiguration)
      case "cassandra-naive" => ("Cassandra Naive", Configurations.CassandraNaiveConfiguration)
      case "cassandra"       => ("Cassandra", Configurations.CassandraConfiguration)
      case "mongo"           => ("Mongo", Configurations.MongoConfiguration)
      case e                 => throw new RuntimeException(s"Bad storage value from conf: $e")
    }
  }

  def getSuites(): Seq[Suite] = {
    val (name, config) = getNameAndConfig()
    val suites = if (name == "LevelDB") {
      Seq(
        new BasicSpec(name, Configurations.LevelDBConfiguration),
        new AdminApiSpec(name, Configurations.LevelDBConfiguration),
        new ProgrammaticApiSpec(name, Configurations.LevelDBConfiguration),
        new CircuitBreakerSpec(name, Configurations.LevelDBConfiguration),
        new QuotasSpec(name, Configurations.LevelDBConfiguration),
        new CanarySpec(name, Configurations.LevelDBConfiguration),
        new AlertAndAnalyticsSpec(name, Configurations.LevelDBConfiguration),
        // new AnalyticsSpec(name, Configurations.LevelDBConfiguration),
        new ApiKeysSpec(name, Configurations.LevelDBConfiguration),
        new SidecarSpec(name, Configurations.LevelDBConfiguration),
        new JWTVerificationSpec(name, Configurations.LevelDBConfiguration),
        new JWTVerificationRefSpec(name, Configurations.LevelDBConfiguration),
        new SnowMonkeySpec(name, Configurations.LevelDBConfiguration),
        new Version149Spec(name, Configurations.LevelDBConfiguration),
        new Version1410Spec(name, Configurations.LevelDBConfiguration),
        new Version1413Spec(name, Configurations.LevelDBConfiguration),
        new WebsocketSpec(name, Configurations.LevelDBConfiguration),
        new Version150Spec(name, config)
      )
    } else {
      Seq(
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
        new WebsocketSpec(name, config),
        new Version150Spec(name, config)
      )
    }
    Option(System.getenv("TEST_ANALYTICS")) match {
      case Some("true") if name == "LevelDB" => suites :+ new AnalyticsSpec(name, Configurations.LevelDBConfiguration)
      case Some("true")                      => suites :+ new AnalyticsSpec(name, config)
      case None                              => suites
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

class DevOtoroshiTests
    extends Suites(
      new Version150Spec("DEV", Configurations.InMemoryConfiguration),
    )
