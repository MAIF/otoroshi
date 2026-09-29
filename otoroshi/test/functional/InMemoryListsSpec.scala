package functional

import org.apache.pekko.actor.ActorSystem
import org.scalatest.BeforeAndAfterAll
import otoroshi.storage.RedisLike
import otoroshi.storage.drivers.inmemory.{ModernSwappableInMemoryRedis, SwappableInMemoryRedis}

import scala.concurrent.Await
import scala.concurrent.duration.*

// the lists of the in-memory stores behave like the redis commands the code is written for: LPUSH puts values at the
// head, LRANGE and LTRIM take an inclusive stop and negative indexes counted from the end. the embedded metrics keep
// their latest samples with lpush + ltrim(0, n), the audit log pages with lrange(from, to)
class InMemoryListsSpec
    extends org.scalatest.wordspec.AnyWordSpec
    with org.scalatest.matchers.must.Matchers
    with BeforeAndAfterAll {

  private val system = ActorSystem("in-memory-lists-spec")

  override def afterAll(): Unit = Await.result(system.terminate(), 10.seconds)

  // none of the list commands reads the env
  private val stores: Seq[(String, RedisLike)] = Seq(
    "modern" -> new ModernSwappableInMemoryRedis(false, null, system),
    "legacy" -> new SwappableInMemoryRedis(false, null, system)
  )

  private def await[A](f: scala.concurrent.Future[A]): A = Await.result(f, 5.seconds)

  private def range(store: RedisLike, key: String, start: Long, stop: Long): Seq[String] =
    await(store.lrange(key, start, stop)).map(_.utf8String)

  stores.foreach { case (name, store) =>
    s"The $name in-memory store" should {

      "put pushed values at the head of the list, as LPUSH does" in {
        await(store.lpush("push-one", "a"))
        await(store.lpush("push-one", "b"))
        range(store, "push-one", 0, -1) mustBe Seq("b", "a")
        await(store.lpush("push-many", "a", "b", "c"))
        range(store, "push-many", 0, -1) mustBe Seq("c", "b", "a")
      }

      "read a range with an inclusive stop and negative indexes, as LRANGE does" in {
        (0 until 10).foreach(i => await(store.lpush("range", i.toString)))
        range(store, "range", 0, 2) mustBe Seq("9", "8", "7")
        range(store, "range", 2, 4) mustBe Seq("7", "6", "5")
        range(store, "range", -3, -1) mustBe Seq("2", "1", "0")
        range(store, "range", 5, 100) mustBe Seq("4", "3", "2", "1", "0")
        range(store, "range", 20, 30) mustBe Seq.empty
        range(store, "range", 0, -1).size mustBe 10
      }

      "trim to an inclusive range, as LTRIM does" in {
        (0 until 10).foreach(i => await(store.lpush("trim", i.toString)))
        await(store.ltrim("trim", 0, 2))
        range(store, "trim", 0, -1) mustBe Seq("9", "8", "7")
        await(store.ltrim("trim", -2, -1))
        range(store, "trim", 0, -1) mustBe Seq("8", "7")
      }

      "keep the latest samples of a list trimmed after each push" in {
        (0 until 150).foreach { i =>
          await(store.lpush("samples", s"v$i"))
          await(store.ltrim("samples", 0, 99))
        }
        range(store, "samples", 0, 99) mustBe (149 to 50 by -1).map(i => s"v$i")
      }

      "page through a list" in {
        (0 until 30).foreach(i => await(store.lpush("pages", s"e$i")))
        range(store, "pages", 10, 19) mustBe (19 to 10 by -1).map(i => s"e$i")
      }
    }
  }
}
