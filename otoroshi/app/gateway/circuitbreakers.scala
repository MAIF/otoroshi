package otoroshi.gateway

import akka.http.scaladsl.util.FastFuture._

import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import akka.Done
import akka.actor.Scheduler
import akka.http.scaladsl.util.FastFuture
import akka.pattern.{CircuitBreaker => AkkaCircuitBreaker}
import akka.stream.scaladsl.Flow
import otoroshi.env.Env
import otoroshi.events._
import otoroshi.health.HealthCheck
import otoroshi.models.{ApiKey, ClientConfig, GlobalConfig, ServiceDescriptor, Target}
import otoroshi.utils.TypedMap
import play.api.Logger
import play.api.http.websocket.{Message => PlayWSMessage}
import play.api.mvc.{RequestHeader, Result}

import otoroshi.utils.syntax.implicits._

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise, duration}
import scala.util.control.NoStackTrace
import scala.util.{Failure, Success}

object Timeout {

  def timeout[A](message: => A, duration: FiniteDuration)(implicit
      ec: ExecutionContext,
      scheduler: Scheduler
  ): Future[A] = {
    val p = Promise[A]()
    scheduler.scheduleOnce(duration) {
      p.success(message)
    }
    p.future
  }
}

object Retry {

  lazy val logger = Logger("otoroshi-circuit-breaker")

  private[this] def retryPromise[T](
      totalCalls: Int,
      times: Int,
      delay: Long,
      factor: Long,
      promise: Promise[T],
      failure: Option[Throwable],
      ctx: String,
      f: Int => Future[T],
      counter: AtomicInteger
  )(implicit ec: ExecutionContext, scheduler: Scheduler): Unit = {
    try {
      (times, failure) match {
        case (0, Some(e)) =>
          logger.warn(s"Retry failure ($totalCalls attempts) for $ctx => ${e.getMessage}")
          promise.tryFailure(e)
        case (0, None)    =>
          logger.warn(s"Retry failure ($totalCalls attempts) for $ctx => lost exception")
          promise.tryFailure(new RuntimeException("Failure, but lost track of exception :-("))
        case (i, _)       =>
          if (totalCalls > 1 && (times < totalCalls)) {
            logger.warn(s"Retrying call for $ctx ($times/$totalCalls attempts)")
          }
          counter.incrementAndGet()
          f((totalCalls - times) + 1).onComplete {
            case Success(t) =>
              promise.trySuccess(t)
            case Failure(e) =>
              logger.warn(s"Error calling $ctx ($times/$totalCalls attempts) : ${e.getMessage}")
              if (delay == 0L) {
                retryPromise[T](totalCalls, times - 1, 0L, factor, promise, Some(e), ctx, f, counter)
              } else {
                val newDelay: Long = delay * factor
                Timeout.timeout(Done, delay.millis).fast.map { _ =>
                  retryPromise[T](totalCalls, times - 1, newDelay, factor, promise, Some(e), ctx, f, counter)
                }
              }
          }(ec)
      }
    } catch {
      case e: Throwable => promise.tryFailure(e)
    }
  }

  def retry[T](
      times: Int,
      delay: Long = 0,
      factor: Long = 2L,
      ctx: String,
      counter: AtomicInteger = new AtomicInteger(0)
  )(
      f: Int => Future[T]
  )(implicit ec: ExecutionContext, scheduler: Scheduler): Future[T] = {
    val promise = Promise[T]()
    retryPromise[T](times, times, delay, factor, promise, None, ctx, f, counter)
    promise.future
  }
}

case object BodyAlreadyConsumedException extends RuntimeException("Request body already consumed") with NoStackTrace
case object RequestTimeoutException      extends RuntimeException("Global request timeout") with NoStackTrace
case object AllCircuitBreakersOpenException
    extends RuntimeException("All targets circuit breakers are open")
    with NoStackTrace

object ServiceDescriptorCircuitBreaker {
  val falseAtomic = new AtomicBoolean(false)
}

case class AkkaCircuitBreakerWrapper(
    cb: AkkaCircuitBreaker,
    maxFailures: Int,
    callTimeout: FiniteDuration,
    callAndStreamTimeout: FiniteDuration,
    resetTimeout: FiniteDuration
)

class ServiceDescriptorCircuitBreaker()(implicit ec: ExecutionContext, scheduler: Scheduler, env: Env) {

  val reqCounter = new AtomicInteger(0)
  val breakers   = new TrieMap[String, AkkaCircuitBreakerWrapper]()

  lazy val logger = Logger("otoroshi-circuit-breaker")

  def clear(): Unit = breakers.clear()

  def getCircuitBreaker(descriptor: ServiceDescriptor, path: String): AkkaCircuitBreaker = {
    ClientConfig.logger.debug(s"[circuitbreaker] using callTimeout - 1: ${descriptor.clientConfig.extractTimeout(path, _.callTimeout, _.callTimeout)}")
    new AkkaCircuitBreaker(
      scheduler = scheduler,
      maxFailures = descriptor.clientConfig.maxErrors,
      callTimeout = descriptor.clientConfig.extractTimeout(path, _.callTimeout, _.callTimeout),
      resetTimeout = descriptor.clientConfig.sampleInterval.millis
    )
  }

  def chooseTarget(
      descriptor: ServiceDescriptor,
      path: String,
      reqId: String,
      trackingId: String,
      requestHeader: RequestHeader,
      attrs: TypedMap
  ): Option[(Target, AkkaCircuitBreaker)] = {
    val targets = descriptor.targets
      .filter(_.predicate.matches(reqId, requestHeader, attrs))
      .filterNot(t => HealthCheck.badHealth.contains(t.asCleanTarget)) // health check can disable targets
      .filterNot(t => breakers.get(t.host).exists(_.cb.isOpen))
      .flatMap(t => Seq.fill(t.weight)(t))
    // val index = reqCounter.incrementAndGet() % (if (targets.nonEmpty) targets.size else 1)
    // Round robin loadbalancing is happening here !!!!!
    if (targets.isEmpty) {
      None
    } else {
      val target = descriptor.targetsLoadBalancing.select(reqId, trackingId, requestHeader, targets, descriptor)
      //val target = targets.apply(index.toInt)

      def buildBreaker(): Unit = {
        // val cb = new AkkaCircuitBreaker(
        //   scheduler = scheduler,
        //   maxFailures = descriptor.clientConfig.maxErrors,
        //   callTimeout = descriptor.clientConfig.extractTimeout(path, _.callTimeout, _.callTimeout),
        //   resetTimeout = descriptor.clientConfig.sampleInterval.millis
        // )
        val cb = getCircuitBreaker(descriptor, path)
        cb.onOpen {
          env.datastores.globalConfigDataStore.singleton().fast.map { config =>
            env.metrics.markString(s"services.${descriptor.id}.circuit-breaker", "open")
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
            env.metrics.markString(s"services.${descriptor.id}.circuit-breaker", "closed")
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
        ClientConfig.logger.debug(s"[circuitbreaker] using callTimeout - 2: ${descriptor.clientConfig.extractTimeout(path, _.callTimeout, _.callTimeout)}")
        breakers.put(
          target.host,
          AkkaCircuitBreakerWrapper(
            cb,
            maxFailures = descriptor.clientConfig.maxErrors,
            callTimeout = descriptor.clientConfig.extractTimeout(path, _.callTimeout, _.callTimeout),
            resetTimeout = descriptor.clientConfig.sampleInterval.millis,
            callAndStreamTimeout = descriptor.clientConfig.extractTimeout(path, _.callAndStreamTimeout, _.callAndStreamTimeout),
          )
        )
      }

      if (!breakers.contains(target.host)) {
        buildBreaker()
      } else {
        if (
          breakers.get(target.host).exists { cb =>
            cb.maxFailures != descriptor.clientConfig.maxErrors ||
              cb.callTimeout != descriptor.clientConfig.extractTimeout(path, _.callTimeout, _.callTimeout) ||
              cb.callAndStreamTimeout != descriptor.clientConfig.extractTimeout(path, _.callAndStreamTimeout, _.callAndStreamTimeout) ||
              cb.resetTimeout != descriptor.clientConfig.sampleInterval.millis
          }
        ) {
          ClientConfig.logger.debug("[circuitbreaker] breaker rebuild !!!!!!")
          buildBreaker()
        } else {
          ClientConfig.logger.debug("[circuitbreaker] no breaker rebuild")
        }
      }
      val breaker = breakers.apply(target.host)
      Some((target, breaker.cb))
    }
  }

  def callGen[A](
      descriptor: ServiceDescriptor,
      reqId: String,
      trackingId: String,
      path: String,
      requestHeader: RequestHeader,
      bodyAlreadyConsumed: AtomicBoolean,
      ctx: String,
      counter: AtomicInteger,
      attrs: TypedMap,
      f: (Target, Int, AtomicBoolean) => Future[Either[Result, A]]
  )(implicit
      env: Env
  ): Future[Either[Result, A]] = {

    ClientConfig.logger.debug(s"[circuitbreaker] using globalTimeout: ${descriptor.clientConfig.extractTimeout(path, _.globalTimeout, _.globalTimeout)}")
    val failure      = Timeout
      .timeout(Done, descriptor.clientConfig.extractTimeout(path, _.globalTimeout, _.globalTimeout))
      .flatMap(_ => FastFuture.failed(RequestTimeoutException))
    val maybeSuccess = Retry.retry(
      descriptor.clientConfig.retries,
      descriptor.clientConfig.retryInitialDelay,
      descriptor.clientConfig.backoffFactor,
      descriptor.name + " : " + ctx,
      counter
    ) { attempts =>
      if (bodyAlreadyConsumed.get) {
        FastFuture.failed(BodyAlreadyConsumedException)
      } else {
        attrs.get(otoroshi.plugins.Keys.PreExtractedRequestTargetKey).map { target =>
          val alreadyFailed = new AtomicBoolean(false)
          getCircuitBreaker(descriptor, path).withCircuitBreaker {
            logger.debug(s"Try to call target : $target")
            f(target, attempts, alreadyFailed)
          } andThen {
            case Failure(_: scala.concurrent.TimeoutException) => alreadyFailed.set(true)
          }
        } getOrElse {
          chooseTarget(descriptor, path, reqId, trackingId, requestHeader, attrs) match {
            case Some((target, breaker)) =>
              val alreadyFailed = new AtomicBoolean(false)
              breaker.withCircuitBreaker {
                logger.debug(s"Try to call target : $target")
                f(target, attempts, alreadyFailed)
              } andThen {
                case Failure(_: scala.concurrent.TimeoutException) => alreadyFailed.set(true)
              }
            case None                    => FastFuture.failed(AllCircuitBreakersOpenException)
          }
        }
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
