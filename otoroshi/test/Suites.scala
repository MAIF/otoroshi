import java.io.File

import com.typesafe.config.ConfigFactory
import functional.OtoroshiBasicSpec
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
      .parseString("""
         |{
         |  app.storage = "leveldb"
         |  app.leveldb.path = "./target/leveldb-test"
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
    Seq(new OtoroshiBasicSpec(name, config))
  }
}

class OtoroshiTests extends Suites(OtoroshiTests.getSuites():_*) with BeforeAndAfterAll {

  override protected def beforeAll(): Unit = {
    FileUtils.deleteDirectory(new File("./target/leveldb-test"))
  }

  override protected def afterAll(): Unit = {
    FileUtils.deleteDirectory(new File("./target/leveldb-test"))
  }
}