package otoroshi.next.utils

import otoroshi.utils.syntax.implicits._

import scala.concurrent.{ExecutionContext, Future}

/**
 * A very simple and straightforward implementation of EitherT where T is Future (Future[Either[Err, Res]]) to avoid using Cats
 */
class FEither[L, R](val value: Future[Either[L, R]]) {

  @inline
  def map[S](f: R => S)(implicit executor: ExecutionContext): FEither[L, S] = {
    val result = value.map {
      case Right(r)    => Right(f(r))
      case Left(error) => Left(error)
    }
    new FEither[L, S](result)
  }

  @inline
  def flatMap[S](f: R => FEither[L, S])(implicit executor: ExecutionContext): FEither[L, S] = {
    val result = value.flatMap {
      case Right(r)    => f(r).value
      case Left(error) => Left(error).vfuture
    }
    new FEither(result)
  }

  @inline
  def leftMap[S](f: L => S)(implicit executor: ExecutionContext): FEither[S, R] = {
    val result = value.map {
      case Right(r)    => Right(r)
      case Left(error) => Left(f(error))
    }
    new FEither[S, R](result)
  }

  @inline
  def leftFlatMap[S](f: L => FEither[S, R])(implicit executor: ExecutionContext): FEither[S, R] = {
    val result = value.flatMap {
      case Right(r)    => Right(r).vfuture
      case Left(error) => f(error).value
    }
    new FEither(result)
  }

  @inline
  def mapBoth[S, T](f: R => S, f2: L => T)(implicit executor: ExecutionContext): FEither[T, S] = {
    val result = value.map {
      case Right(r)    => Right(f(r))
      case Left(error) => Left(f2(error))
    }
    new FEither[T, S](result)
  }

  @inline
  def flatMapBoth[S, T](f: R => FEither[T, S], f2: L => FEither[T, S])(implicit
      executor: ExecutionContext
  ): FEither[T, S] = {
    val result = value.flatMap {
      case Right(r)    => f(r).value
      case Left(error) => f2(error).value
    }
    new FEither(result)
  }

  @inline
  def ensure(onFailure: => L)(f: R => Boolean)(implicit executor: ExecutionContext): FEither[L, R] = {
    val result = value.flatMap {
      case e @ Right(r) if f(r) => e.vfuture
      case Right(_)             => Left(onFailure).vfuture
      case l @ Left(_)          => l.vfuture
    }
    new FEither[L, R](result)
  }

  @inline
  def fold[S](f1: L => S, f2: R => S)(implicit executor: ExecutionContext): FEither[L, S] = {
    val result = value.map {
      case Right(r)    => Right(f2(r))
      case Left(error) => Right(f1(error))
    }
    new FEither[L, S](result)
  }

  @inline
  def foldF[S](f1: L => Future[S], f2: R => Future[S])(implicit executor: ExecutionContext): FEither[L, S] = {
    val result = value.flatMap {
      case Right(r)    => f2(r).map(r => Right(r))
      case Left(error) => f1(error).map(r => Right(r))
    }
    new FEither[L, S](result)
  }

  @inline
  def isLeft(implicit executor: ExecutionContext): Future[Boolean] = value.map {
    case Right(r)    => false
    case Left(error) => true
  }

  @inline
  def isRight(implicit executor: ExecutionContext): Future[Boolean] = value.map {
    case Right(r)    => true
    case Left(error) => false
  }

  @inline
  def swap(implicit executor: ExecutionContext): FEither[R, L] = {
    val result = value.map {
      case Right(r)    => Left(r)
      case Left(error) => Right(error)
    }
    new FEither[R, L](result)
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

  @inline
  def apply[L, R](value: Future[Either[L, R]]): FEither[L, R]       = new FEither[L, R](value)
  @inline
  def fromEitherT[L, R](value: Future[Either[L, R]]): FEither[L, R] = new FEither[L, R](value)

  @inline
  def fromEither[L, R](value: Either[L, R]): FEither[L, R] = new FEither[L, R](value.vfuture)
  @inline
  def apply[L, R](value: Either[L, R]): FEither[L, R]      = fromEither(value)

  @inline
  def leftT[L, R](value: L): FEither[L, R]                                         = new FEither[L, R](Left(value).vfuture)
  @inline
  def left[L, R](value: L): FEither[L, R]                                          = new FEither[L, R](Left(value).vfuture)
  @inline
  def fleft[L, R](value: Future[L])(implicit ec: ExecutionContext): FEither[L, R]  =
    new FEither[L, R](value.map(v => Left(v)))
  @inline
  def rightT[L, R](value: R): FEither[L, R]                                        = new FEither[L, R](Right(value).vfuture)
  @inline
  def right[L, R](value: R): FEither[L, R]                                         = new FEither[L, R](Right(value).vfuture)
  @inline
  def fright[L, R](value: Future[R])(implicit ec: ExecutionContext): FEither[L, R] =
    new FEither[L, R](value.map(v => Right(v)))
  @inline
  def liftF[L, R](value: Future[R])(implicit ec: ExecutionContext): FEither[L, R]  = fright(value)

  @inline
  def fromOption[L, R](value: Option[R], err: L): FEither[L, R]                                         = value match {
    case None    => new FEither[L, R](Left(err).vfuture)
    case Some(v) => new FEither[L, R](Right(v).vfuture)
  }
  @inline
  def fromOptionF[L, R](value: Future[Option[R]], err: L)(implicit ec: ExecutionContext): FEither[L, R] =
    new FEither[L, R](value.map {
      case None    => Left(err)
      case Some(v) => Right(v)
    })
}
