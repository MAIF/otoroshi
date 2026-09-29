package functional

import otoroshi.storage.drivers.inmemory.ModernMemory
import otoroshi.utils.cache.types.UnboundedTrieMap

// a cluster worker swaps its in-memory state for the one of the leader, but keeps the keys that belong to it only
// (Cluster.filteredKey: local stats, caches, sessions...), as the merge of the legacy store does
class ModernMemorySwapSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  private def memory(entries: (String, Any)*): ModernMemory = {
    val store       = new UnboundedTrieMap[String, Any]()
    val expirations = new UnboundedTrieMap[String, Long]()
    entries.foreach { case (k, v) => store.put(k, v) }
    ModernMemory(store, expirations)
  }

  private val local: String => Boolean = _.startsWith("otoroshi:data:")

  "ModernMemory.swap" should {

    "take the new state and drop the keys it lacks" in {
      val m = memory("otoroshi:routes:a" -> "a", "otoroshi:data:calls" -> 12L)
      m.swap(Map("otoroshi:routes:b" -> "b"), Map.empty)
      m.keys mustBe Set("otoroshi:routes:b")
    }

    "keep the local keys the new state lacks" in {
      val m = memory("otoroshi:routes:a" -> "a", "otoroshi:data:calls" -> 12L)
      m.swap(Map("otoroshi:routes:b" -> "b"), Map.empty, local)
      m.keys mustBe Set("otoroshi:routes:b", "otoroshi:data:calls")
      m.get("otoroshi:data:calls") mustBe Some(12L)
    }

    "not replace a local key with the one of the new state" in {
      val m = memory("otoroshi:data:calls" -> 12L)
      m.swap(Map("otoroshi:data:calls" -> 99L, "otoroshi:routes:b" -> "b"), Map.empty, local)
      m.get("otoroshi:data:calls") mustBe Some(12L)
      m.get("otoroshi:routes:b") mustBe Some("b")
    }

    "keep the expiration of the local keys" in {
      val m = memory("otoroshi:data:calls" -> 12L, "otoroshi:routes:a" -> "a")
      m.putExpiration("otoroshi:data:calls", 42L)
      m.putExpiration("otoroshi:routes:a", 43L)
      m.swap(Map.empty, Map.empty, local)
      m.getExpiration("otoroshi:data:calls") mustBe Some(42L)
      m.getExpiration("otoroshi:routes:a") mustBe None
    }
  }
}
