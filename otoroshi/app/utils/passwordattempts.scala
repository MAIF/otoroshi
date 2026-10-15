package otoroshi.utils

import otoroshi.env.Env
import otoroshi.utils.syntax.implicits.*
import play.api.libs.json.Json
import play.api.mvc.{Result, Results}

import scala.concurrent.{ExecutionContext, Future}

/**
 * Online guessing of passwords: once `maxFailures` checks failed for the same identifier from the same address within
 * the window, the next checks are refused without being run, which also spares the cost of a bcrypt per attempt. The
 * failures are counted per identifier and address, so that nobody can lock a user out from another address.
 */
object PasswordAttempts {

  val tooManyFailuresResult: Result =
    Results.TooManyRequests(Json.obj("error" -> "too many failed attempts, retry later"))

  private def key(scope: String, identifier: String, ip: String)(using env: Env): String =
    s"${env.storageRoot}:password-attempts:${s"$scope:$identifier:$ip".sha256}"

  def tooManyFailures(scope: String, identifier: String, ip: String)(using
      env: Env,
      ec: ExecutionContext
  ): Future[Boolean] = {
    if (!env.passwordAttemptsEnabled) false.vfuture
    else
      env.datastores.rawDataStore
        .get(key(scope, identifier, ip))
        .map(_.flatMap(_.utf8String.toLongOption).exists(_ >= env.passwordAttemptsMaxFailures))
  }

  def failed(scope: String, identifier: String, ip: String)(using env: Env, ec: ExecutionContext): Future[Unit] = {
    if (!env.passwordAttemptsEnabled) ().vfuture
    else {
      val k = key(scope, identifier, ip)
      env.datastores.rawDataStore.incr(k).flatMap {
        case 1L => env.datastores.rawDataStore.pexpire(k, env.passwordAttemptsWindowMillis).map(_ => ())
        case _  => ().vfuture
      }
    }
  }

  def succeeded(scope: String, identifier: String, ip: String)(using env: Env, ec: ExecutionContext): Future[Unit] = {
    if (!env.passwordAttemptsEnabled) ().vfuture
    else env.datastores.rawDataStore.del(Seq(key(scope, identifier, ip))).map(_ => ())
  }

  /**
   * Runs `check` unless too many checks already failed, in which case `blocked` is returned, then records the outcome
   * of the check.
   */
  def guard[A](scope: String, identifier: String, ip: String)(blocked: => A)(isFailure: A => Boolean)(
      check: => Future[A]
  )(using env: Env, ec: ExecutionContext): Future[A] = {
    tooManyFailures(scope, identifier, ip).flatMap {
      case true  => blocked.vfuture
      case false =>
        check.flatMap { result =>
          (if (isFailure(result)) failed(scope, identifier, ip) else succeeded(scope, identifier, ip)).map(_ => result)
        }
    }
  }
}
