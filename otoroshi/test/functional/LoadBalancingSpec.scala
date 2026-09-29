package functional

import otoroshi.env.Env
import otoroshi.models.*
import play.api.mvc.RequestHeader
import play.api.test.FakeRequest

import java.util.concurrent.atomic.AtomicInteger
import scala.util.Try

// the load balancing strategies driven by a counter or by randomness. none of them reads the env it is given
class LoadBalancingSpec extends org.scalatest.wordspec.AnyWordSpec with org.scalatest.matchers.must.Matchers {

  private implicit val env: Env = null

  private val targets = Seq("a", "b", "c").map(name => Target(host = s"$name.lb-spec.oto.tools:80", scheme = "http"))

  private def pick(lb: LoadBalancing, among: Seq[Target] = targets, request: RequestHeader = FakeRequest()): Target =
    lb.select("req", "", request, among, "lb-spec-route", 1)

  private def privateCounterOf(strategy: AnyRef): AtomicInteger = {
    val field = strategy.getClass.getDeclaredField("reqCounter")
    field.setAccessible(true)
    field.get(strategy).asInstanceOf[AtomicInteger]
  }

  "The counter based strategies" should {

    // the counters are shared by every request: past Int.MaxValue they go negative, and so did the index of the target
    "keep selecting every target once their counter has gone past Int.MaxValue" in {
      Seq[(String, LoadBalancing, AtomicInteger)](
        ("RoundRobin", RoundRobin, privateCounterOf(RoundRobin)),
        ("LeastConnections", LeastConnections, privateCounterOf(LeastConnections)),
        ("CookieHash without its cookie", new CookieHash("session"), CookieHash.reqCounter),
        ("QueryHash without its query param", new QueryHash("session"), QueryHash.reqCounter),
        ("HeaderHash without its header", new HeaderHash("session"), HeaderHash.reqCounter)
      ).foreach { case (name, lb, counter) =>
        counter.set(Int.MaxValue - 2)
        val picked = (0 until 6).map(_ => Try(pick(lb)))
        counter.set(0)
        withClue(s"$name: ") {
          picked.filter(_.isFailure) mustBe empty
          picked.map(_.get).toSet mustBe targets.toSet
        }
      }
    }

    "go through the targets in turn" in {
      val counter = privateCounterOf(RoundRobin)
      counter.set(0)
      (0 until 6).map(_ => pick(RoundRobin)) mustBe Seq(1, 2, 0, 1, 2, 0).map(targets)
    }
  }

  "PowerOfTwoRandomChoices" should {

    "compare two different targets and take the less loaded one" in {
      val loaded = targets(1)
      (0 until 10).foreach(_ => LocalTargetsInflightRequestMonitor.incrementInflightRequestsFor(loaded))
      try {
        val amongThree = (0 until 300).map(_ => pick(PowerOfTwoRandomChoices))
        amongThree must not contain loaded
        amongThree.toSet mustBe Set(targets(0), targets(2))
        val amongTwo   = (0 until 100).map(_ => pick(PowerOfTwoRandomChoices, among = Seq(targets(0), loaded)))
        amongTwo.toSet mustBe Set(targets(0))
      } finally {
        (0 until 10).foreach(_ => LocalTargetsInflightRequestMonitor.decrementInflightRequestsFor(loaded))
      }
    }

    "work with a single target" in {
      (0 until 10).map(_ => pick(PowerOfTwoRandomChoices, among = Seq(targets(0)))).toSet mustBe Set(targets(0))
    }
  }
}
