package functional

import otoroshi.cluster.MemberView
import otoroshi.env.Env
import play.api.Configuration
import play.api.libs.json.*

// a member of the cluster as the leader reads it: the relay settings it sent are kept
class ClusterMemberViewSpec extends OtoroshiSpec {

  override def getTestConfiguration(configuration: Configuration): Configuration = configuration

  private def member(relay: JsObject): JsObject = Json.obj(
    "id"         -> "node_1",
    "name"       -> "a-worker",
    "location"   -> "10.0.0.1",
    "lastSeen"   -> System.currentTimeMillis(),
    "timeout"    -> 10000,
    "memberType" -> "Worker",
    "stats"      -> Json.obj(),
    "relay"      -> relay
  )

  "A member of the cluster" should {
    "warm up" in {
      startOtoroshi()
    }
    "keep the relay settings it sent" in {
      given env: Env = otoroshiComponents.env
      val read       = MemberView.fromJsonSafe(member(Json.obj("enabled" -> false, "leaderOnly" -> true))).get
      read.relay.enabled mustBe false
      read.relay.leaderOnly mustBe true
      // a member that sent no relay settings is relayed to, as before
      val none       = MemberView.fromJsonSafe(member(Json.obj())).get
      none.relay.enabled mustBe true
      none.relay.leaderOnly mustBe false
    }
    "shutdown" in {
      stopAll()
    }
  }
}
