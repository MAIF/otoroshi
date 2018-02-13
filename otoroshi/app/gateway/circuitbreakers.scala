package gateway

import akka.http.scaladsl.util.FastFuture._

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

import akka.Done
import akka.actor.Scheduler
import akka.http.scaladsl.util.FastFuture
import akka.pattern.{CircuitBreaker => AkkaCircuitBreaker}
import akka.stream.scaladsl.Flow
import env.Env
import events._
import models.{ServiceDescriptor, Target}
import play.api.Logger
import play.api.http.websocket.{Message => PlayWSMessage}
import play.api.mvc.Result

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success}

object Timeout {

  def timeout[A](message: => A, duration: FiniteDuration)(implicit ec: ExecutionContext,
                                                          scheduler: Scheduler): Future[A] = {
    val p = Promise[A]()
    scheduler.scheduleOnce(duration) {
      p.success(message)
    }
    p.future
  }
}

object Retry {

  lazy val logger = Logger("otoroshi-circuit-breaker")

  private[this] def retryPromise[T](totalCalls: Int,
                                    times: Int,
                                    delay: Long,
                                    factor: Long,
                                    promise: Promise[T],
                                    failure: Option[Throwable],
                                    ctx: String,
                                    f: () => Future[T])(implicit ec: ExecutionContext, scheduler: Scheduler): Unit =
    (times, failure) match {
      case (0, Some(e)) =>
        logger.warn(s"Retry failure ($totalCalls attemps) for $ctx => ${e.getMessage}")
        promise.tryFailure(e)
      case (0, None) =>
        logger.warn(s"Retry failure ($totalCalls attemps) for $ctx => lost exception")
        promise.tryFailure(new RuntimeException("Failure, but lost track of exception :-("))
      case (i, _) =>
        if (totalCalls > 1 && (times < totalCalls) {
          logger.warn(s"Retrying call for $ctx ($times/$totalCalls attemps)")
        }
        f().onComplete {
          case Success(t) =>
            promise.trySuccess(t)
          case Failure(e) =>
            logger.warn(s"Error calling $ctx ($times/$totalCalls attemps) : ${e.getMessage}")
            if (delay == 0L) {
              retryPromise[T](totalCalls, times - 1, 0L, factor, promise, Some(e), ctx, f)
            } else {
              val newDelay: Long = delay * factor
              Timeout.timeout(Done, delay.millis).fast.map { _ =>
                retryPromise[T](totalCalls, times - 1, newDelay, factor, promise, Some(e), ctx, f)
              }
            }
        }(ec)
    }

  def retry[T](times: Int, delay: Long = 0, factor: Long = 2L, ctx: String)(f: () => Future[T])(implicit ec: ExecutionContext,
                                                                                   scheduler: Scheduler): Future[T] = {
    val promise = Promise[T]()
    retryPromise[T](times, times, delay, factor, promise, None, ctx, f)
    promise.future
  }
}

case object BodyAlreadyConsumedException extends RuntimeException("Request body already consumed") with NoStackTrace
case object RequestTimeoutException      extends RuntimeException("Global request timeout") with NoStackTrace
case object AllCircuitBreakersOpenException
    extends RuntimeException("All targets circuit breakers are open")
    with NoStackTrace

class ServiceDescriptorCircuitBreaker()(implicit ec: ExecutionContext, scheduler: Scheduler, env: Env) {

  val reqCounter = new AtomicInteger(0)
  val breakers   = new ConcurrentHashMap[String, AkkaCircuitBreaker]()

  lazy val logger = Logger("otoroshi-circuit-breaker")

  def clear(): Unit = breakers.clear()

  def chooseTarget(descriptor: ServiceDescriptor): Option[(Target, AkkaCircuitBreaker)] = {
    val targets = descriptor.targets.filterNot(t => Option(breakers.get(t.host)).map(_.isOpen).getOrElse(false))
    val index   = reqCounter.incrementAndGet() % (if (targets.nonEmpty) targets.size else 1)
    // Round robin loadbalancing is happening here !!!!!
    if (targets.isEmpty) {
      None
    } else {
      val target = targets.apply(index.toInt)
      if (!breakers.containsKey(target.host)) {
        val cb = new AkkaCircuitBreaker(
          scheduler = scheduler,
          maxFailures = descriptor.clientConfig.maxErrors,
          callTimeout = descriptor.clientConfig.callTimeout.millis,
          resetTimeout = descriptor.clientConfig.sampleInterval.millis
        )
        cb.onOpen {
          env.datastores.globalConfigDataStore.singleton().fast.map { config =>
            env.statsd.set(s"services.${descriptor.id}.circuit-breaker", "open")(config.statsdConfig)
          }
          Audit.send(
            CircuitBreakerOpenedEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              target,
              descriptor
            )
          )
          Alerts.send(
            CircuitBreakerOpenedAlert(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              target,
              descriptor
            )
          )
        }
        cb.onClose {
          env.datastores.globalConfigDataStore.singleton().fast.map { config =>
            env.statsd.set(s"services.${descriptor.id}.circuit-breaker", "closed")(config.statsdConfig)
          }
          Audit.send(
            CircuitBreakerClosedEvent(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              target,
              descriptor
            )
          )
          Alerts.send(
            CircuitBreakerClosedAlert(
              env.snowflakeGenerator.nextIdStr(),
              env.env,
              target,
              descriptor
            )
          )
        }
        breakers.putIfAbsent(target.host, cb)
      }
      val breaker = breakers.get(target.host)
      Some((target, breaker))
    }
  }

  def call(descriptor: ServiceDescriptor, bodyAlreadyConsumed: AtomicBoolean, ctx: String, f: Target => Future[Result])(
      implicit env: Env
  ): Future[Result] = {
    val failure = Timeout
      .timeout(Done, descriptor.clientConfig.globalTimeout.millis)
      .flatMap(_ => FastFuture.failed(RequestTimeoutException))
    val maybeSuccess = Retry.retry(descriptor.clientConfig.retries,
                                   descriptor.clientConfig.retryInitialDelay,
                                   descriptor.clientConfig.backoffFactor,
                                   descriptor.name + " : " + ctx) { () =>
      if (bodyAlreadyConsumed.get) {
        FastFuture.failed(BodyAlreadyConsumedException)
      } else {
        chooseTarget(descriptor) match {
          case Some((target, breaker)) =>
            breaker.withCircuitBreaker {
              logger.debug(s"Try to call target : $target")
              f(target)
            }
          case None => FastFuture.failed(AllCircuitBreakersOpenException)
        }
      }
    }
    Future.firstCompletedOf(Seq(maybeSuccess, failure))
  }

  def callWS(descriptor: ServiceDescriptor, ctx: String, f: Target => Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]])(
      implicit env: Env
  ): Future[Either[Result, Flow[PlayWSMessage, PlayWSMessage, _]]] = {
    val failure = Timeout
      .timeout(Done, descriptor.clientConfig.globalTimeout.millis)
      .flatMap(_ => FastFuture.failed(RequestTimeoutException))
    val maybeSuccess = Retry.retry(descriptor.clientConfig.retries,
                                   descriptor.clientConfig.retryInitialDelay,
                                   descriptor.clientConfig.backoffFactor,
                                   descriptor.name + " : " + ctx) { () =>
      chooseTarget(descriptor) match {
        case Some((target, breaker)) =>
          logger.debug(s"Try to call WS target : $target")
          breaker.withCircuitBreaker(f(target))
        case None => FastFuture.failed(new RuntimeException(AllCircuitBreakersOpenException))
      }

    }
    Future.firstCompletedOf(Seq(maybeSuccess, failure))
  }
}

class CircuitBreakersHolder() {

  val circuitBreakers = new ConcurrentHashMap[String, ServiceDescriptorCircuitBreaker]()

  def get(id: String, defaultValue: () => ServiceDescriptorCircuitBreaker): ServiceDescriptorCircuitBreaker = {
    if (!circuitBreakers.containsKey(id)) {
      circuitBreakers.putIfAbsent(id, defaultValue())
    }
    circuitBreakers.get(id)
  }

  def resetAllCircuitBreakers(): Unit = circuitBreakers.clear()

  def resetCircuitBreakersFor(id: String): Unit =
    Option(circuitBreakers.get(id)).foreach(_.clear())
}
