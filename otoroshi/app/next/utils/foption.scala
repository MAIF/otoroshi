package otoroshi.next.utils

import otoroshi.utils.syntax.implicits._

import scala.concurrent.{ExecutionContext, Future}

/**
 * A very simple and straightforward implementation of EitherT where T is Option (Future[Option[Res]]) to avoid using Cats
 */
class FOption[R](val value: Future[Option[R]]) {

  def map[S](f: R => S)(implicit executor: ExecutionContext): FOption[S] = {
    val result = value.map {
      case Some(r)    => Some(f(r))
      case None => None
    }
    new FOption[S](result)
  }

  def flatMap[S](f: R => FOption[S])(implicit executor: ExecutionContext): FOption[S] = {
    val result = value.flatMap {
      case Some(r)    => f(r).value
      case None => None.vfuture
    }
    new FOption(result)
  }

  def map[S](f: R => S, f2: => S)(implicit executor: ExecutionContext): FOption[S] = {
    val result = value.map {
      case Some(r) => Some(f(r))
      case None => Some(f2)
    }
    new FOption[S](result)
  }

  def flatMap[S, T](f: R => FOption[S], f2: => FOption[S])(implicit executor: ExecutionContext): FOption[S] = {
    val result = value.flatMap {
      case Some(r)    => f(r).value
      case None => f2.value
    }
    new FOption(result)
  }

  def fold[S](f1: => S, f2: R => S)(implicit executor: ExecutionContext): FOption[S] = {
    val result = value.map {
      case Some(r)    => Some(f2(r))
      case None => Some(f1)
    }
    new FOption[S](result)
  }

  def foldF[S](f1: => Future[S], f2: R => Future[S])(implicit executor: ExecutionContext): FOption[S] = {
    val result = value.flatMap {
      case Some(r)    => f2(r).map(r => Some(r))
      case None => f1.map(r => Some(r))
    }
    new FOption[S](result)
  }

  def isNone(implicit executor: ExecutionContext): Future[Boolean] = value.map {
    case Some(_)    => false
    case None => true
  }

  def isSome(implicit executor: ExecutionContext): Future[Boolean] = value.map {
    case Some(_)    => true
    case None => false
  }
}

object FOption {
  def apply[R](value: Future[Option[R]]): FOption[R] = new FOption[R](value)
  def fromOption[T](value: Option[T]): FOption[T] = new FOption[T](value.vfuture)
  def some[R](value: R): FOption[R]                                         = new FOption[R](Some(value).vfuture)
  def fsome[L, R](value: Future[R])(implicit ec: ExecutionContext): FOption[R] = new FOption[R](value.map(v => Some(v)))
  def liftF[R](value: Future[R])(implicit ec: ExecutionContext): FOption[R]  = fsome(value)
  def fromOptionF[R](value: Future[Option[R]])(implicit ec: ExecutionContext): FOption[R] = new FOption[R](value)
}
