package functional

import com.codahale.metrics.{Clock, Meter}
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpec
import otoroshi.cluster.ClusterLeaderUpdateMessage.RouteCallIncr
import otoroshi.greenscore.RouteReservoirs

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

// the metrics of the dynamic green score of a route: the calls are a rate of calls per second
class GreenScoreReservoirsSpec extends AnyWordSpec with Matchers {

  private class ManualClock extends Clock {
    val nanos                  = new AtomicLong(0L)
    override def getTick: Long = nanos.get()
  }

  private def incr(calls: Long, duration: Long, dataIn: Long) = RouteCallIncr(
    routeId = "route",
    calls = new AtomicLong(calls),
    duration = new AtomicLong(duration),
    dataIn = new AtomicLong(dataIn)
  )

  "The reservoirs of a route" should {
    "count the calls as a rate of calls per second" in {
      val clock      = new ManualClock
      val reservoirs = RouteReservoirs(calls = new Meter(clock))
      (1 to 600).foreach(_ => reservoirs.update(incr(calls = 1, duration = 5, dataIn = 100)))
      // the rate is computed once more than 5 seconds went by, from the calls of those 5 seconds
      clock.nanos.set(TimeUnit.SECONDS.toNanos(6))
      reservoirs.callsPerSecond mustBe 120.0 +- 0.001
    }
  }
}
