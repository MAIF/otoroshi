import com.typesafe.config.ConfigFactory
import functional.OtoroshiBasicSpec
import org.scalatest.Suites
import play.api.Configuration

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
}

class OtoroshiInMemoryTests
  extends Suites(
    new OtoroshiBasicSpec("InMemory", Configurations.InMemoryConfiguration)
  )

// class OtoroshiLevelDBTests
//   extends Suites(
//     new OtoroshiBasicSpec("LevelDB", Configurations.LevelDBConfiguration)
//   ) with BeforeAndAfterAll {
//
//   override protected def beforeAll(): Unit = {
//     FileUtils.deleteDirectory(new File("./target/leveldb-test"))
//   }
//
//   override protected def afterAll(): Unit = {
//     FileUtils.deleteDirectory(new File("./target/leveldb-test"))
//   }
// }