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
       """.stripMargin).resolve()
  )

  def LevelDBConfiguration = Configuration(
    ConfigFactory
      .parseString(s"""
         |{
         |  app.storage = "leveldb"
         |  app.leveldb.path = "./target/leveldbs/test-${System.currentTimeMillis()}"
         |}
       """.stripMargin).resolve()
  )

  val RedisConfiguration = Configuration(
    ConfigFactory
      .parseString("""
         |{
         |  app.storage = "redis"
         |}
       """.stripMargin).resolve()
  )

  val CassandraConfiguration = Configuration(
    ConfigFactory
      .parseString("""
         |{
         |  app.storage = "cassandra"
         |}
       """.stripMargin).resolve()
  )
}

object OtoroshiTests {
  def getSuites(): Seq[Suite] = {
    val (name, config) = Try(Option(System.getenv("TEST_STORE"))).toOption.flatten.getOrElse("inmemory") match {
      case "redis"     => ("Redis", Configurations.RedisConfiguration)
      case "inmemory"  => ("InMemory", Configurations.InMemoryConfiguration)
      case "leveldb"   => ("LevelDB", Configurations.LevelDBConfiguration)
      case "cassandra" => ("Cassandra", Configurations.CassandraConfiguration)
      case e           => throw new RuntimeException(s"Bad storage value from conf: $e")
    }
    if (name == "LevelDB") {
      Seq(
        new BasicSpec(name, Configurations.LevelDBConfiguration),
        new AdminApiSpec(name, Configurations.LevelDBConfiguration),
        new ProgrammaticApiSpec(name, Configurations.LevelDBConfiguration),
        new CircuitBreakerSpec(name, Configurations.LevelDBConfiguration),
        new CanarySpec(name, Configurations.LevelDBConfiguration)
      )
    } else {
      Seq(
        new BasicSpec(name, config),
        new AdminApiSpec(name, config),
        new ProgrammaticApiSpec(name, config),
        new CircuitBreakerSpec(name, config),
        new CanarySpec(name, config)
        // alerts spec & audit spec
        // websocket spec
        // rate limit & quotas spec
      )
    }
  }
}

class OtoroshiTests extends Suites(OtoroshiTests.getSuites():_*) with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    FileUtils.deleteDirectory(new File("./target/leveldbs"))
  }

  override protected def afterAll(): Unit = {
    FileUtils.deleteDirectory(new File("./target/leveldbs"))
  }
}

//class DevOtoroshiTests extends Suites(new QuotasSpec("DEV", Configurations.InMemoryConfiguration))