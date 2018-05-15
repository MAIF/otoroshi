import java.io.File

import com.typesafe.config.ConfigFactory
import functional.{OtoroshiApiSpec, OtoroshiBasicSpec}
import org.apache.commons.io.FileUtils
import org.scalatest.{BeforeAndAfterAll, Suite, Suites}
import play.api.Configuration
import storage.cassandra.CassandraDataStores
import storage.inmemory.InMemoryDataStores
import storage.leveldb.LevelDbDataStores
import storage.redis.RedisDataStores

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

  val LevelDBConfiguration = Configuration(
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
    Seq(
      new OtoroshiBasicSpec(name, config), // add private path, additional header, routing headers, matching root, target root, wildcard domain, whitelist, blacklist
      new OtoroshiApiSpec(name, config)
      // alerts spec
      // audit spec
      // websocket spec
      // circuit breaker spec
      // rate limit & quotas spec
      // apikeys spec with quotas in headers
      // canary spec
    )
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