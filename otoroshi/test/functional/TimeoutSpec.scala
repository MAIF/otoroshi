package functional

import org.apache.pekko.actor.{ActorSystem, Cancellable, Scheduler}
import org.scalatest.BeforeAndAfterAll
import otoroshi.gateway.{RequestTimeoutException, Timeout}

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

// Timeout.failAfter races every proxied call against its global timeout, and every call of the pekko client against
// its own. it must give what the race it replaced gave, and cancel its timer as soon as the call completes: a timer
// left in the scheduler keeps its closure and promises alive until it fires, i.e. 30 s or more after each request
class TimeoutSpec
    extends org.scalatest.wordspec.AnyWordSpec
    with org.scalatest.matchers.must.Matchers
    with BeforeAndAfterAll {

  private val system                        = ActorSystem("timeout-spec")
  private implicit val ec: ExecutionContext = system.dispatcher

  // the actor system scheduler, keeping the timers it hands out so that the tests can check they were cancelled
  private val timers                        = new ConcurrentLinkedQueue[Cancellable]()
  private implicit val scheduler: Scheduler = new Scheduler {
    override def scheduleOnce(delay: FiniteDuration, runnable: Runnable)(implicit
        executor: ExecutionContext
    ): Cancellable = {
      val timer = system.scheduler.scheduleOnce(delay, runnable)(using executor)
      timers.add(timer)
      timer
    }
    override def schedule(initialDelay: FiniteDuration, interval: FiniteDuration, runnable: Runnable)(implicit
        executor: ExecutionContext
    ): Cancellable = system.scheduler.scheduleAtFixedRate(initialDelay, interval)(runnable)(using executor)
    override def maxFrequency: Double = system.scheduler.maxFrequency
  }

  override def afterAll(): Unit = Await.result(system.terminate(), 10.seconds)

  private def await[A](future: Future[A]): Try[A] = Try(Await.result(future, 10.seconds))

  // calls failAfter and returns its result along with the one timer it scheduled
  private def failAfter[A](duration: FiniteDuration)(future: => Future[A]): (Future[A], Cancellable) = {
    timers.clear()
    val result = Timeout.failAfter(duration, RequestTimeoutException)(future)
    timers.size() mustBe 1
    (result, timers.peek())
  }

  "Timeout.failAfter" should {

    "complete with the result of the future and cancel its timer" in {
      val (result, timer) = failAfter(30.seconds)(Future(42))
      await(result) mustBe Success(42)
      timer.isCancelled mustBe true
    }

    "complete with the failure of the future and cancel its timer" in {
      val boom            = new RuntimeException("boom")
      val (result, timer) = failAfter(30.seconds)(Future(throw boom))
      await(result) mustBe Failure(boom)
      timer.isCancelled mustBe true
    }

    "cancel the timer of an already completed future" in {
      val (result, timer) = failAfter(30.seconds)(Future.successful("done"))
      await(result) mustBe Success("done")
      timer.isCancelled mustBe true
    }

    "fail with the given error when the future is too slow, and ignore its late result" in {
      val slow            = Promise[Int]()
      val (result, timer) = failAfter(50.millis)(slow.future)
      await(result) mustBe Failure(RequestTimeoutException)
      slow.success(1)
      result.value mustBe Some(Failure(RequestTimeoutException))
      timer.isCancelled mustBe false
    }

    "schedule its timer before evaluating the future, so that the timeout covers the evaluation" in {
      var timersSeenByTheCall = -1
      val (result, _)         = failAfter(30.seconds) {
        timersSeenByTheCall = timers.size()
        Future.successful(())
      }
      await(result) mustBe Success(())
      timersSeenByTheCall mustBe 1
    }

    "cancel every timer when many calls complete concurrently" in {
      timers.clear()
      val results = (1 to 2000).map(i => Timeout.failAfter(30.seconds, RequestTimeoutException)(Future(i)))
      await(Future.sequence(results)) mustBe Success((1 to 2000).toVector)
      timers.size() mustBe 2000
      timers.stream().allMatch(_.isCancelled) mustBe true
    }
  }
}
