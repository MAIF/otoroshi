package otoroshi.next.utils

import otoroshi.utils.syntax.implicits._

import scala.concurrent.{ExecutionContext, Future}

/**
 * A very simple and straightforward implementation of EitherT where T is Future (Future[Either[Err, Res]]) to avoid using Cats
 */
class FEither[L, R](val value: Future[Either[L, R]]) {

  def map[S](f: R => S)(implicit executor: ExecutionContext): FEither[L, S] = {
    val result = value.map {
      case Right(r)    => Right(f(r))
      case Left(error) => Left(error)
    }
    new FEither[L, S](result)
  }

  def flatMap[S](f: R => FEither[L, S])(implicit executor: ExecutionContext): FEither[L, S] = {
    val result = value.flatMap {
      case Right(r)    => f(r).value
      case Left(error) => Left(error).vfuture
    }
    new FEither(result)
  }

  // def filter(f: R => Boolean)(implicit executor: ExecutionContext): FEither[String, R] = {
  //   val result = value.flatMap {
  //     case e @ Right(r) if f(r) => e.vfuture
  //     case Right(_) => Left("predicate does not match").vfuture
  //     case l @ Left(_)  => l.vfuture
  //   }
  //   new FEither[String, R](result)
  // }
}

object FEither {
  def apply[L, R](value: Future[Either[L, R]]): FEither[L, R]                      = new FEither[L, R](value)
  def apply[L, R](value: Either[L, R]): FEither[L, R]                              = new FEither[L, R](value.vfuture)
  def left[L, R](value: L): FEither[L, R]                                          = new FEither[L, R](Left(value).vfuture)
  def fleft[L, R](value: Future[L])(implicit ec: ExecutionContext): FEither[L, R]  =
    new FEither[L, R](value.map(v => Left(v)))
  def right[L, R](value: R): FEither[L, R]                                         = new FEither[L, R](Right(value).vfuture)
  def fright[L, R](value: Future[R])(implicit ec: ExecutionContext): FEither[L, R] =
    new FEither[L, R](value.map(v => Right(v)))
}
