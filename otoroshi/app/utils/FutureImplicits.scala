package utils

import akka.http.scaladsl.util.FastFuture

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

package object future {

  object Implicits {

    implicit final class EnhancedObject[A](any: A) {
      def asFuture: Future[A] = FastFuture.successful(any)
      def future: Future[A]   = FastFuture.successful(any)
    }

    implicit final class EnhancedFuture[A](future: Future[A]) {

      import akka.http.scaladsl.util.FastFuture._

      def fold[U](pf: PartialFunction[Try[A], U])(implicit executor: ExecutionContext): Future[U] = {
        val promise = Promise[U]
        future.andThen {
          case underlying: Try[A] => {
            try {
              promise.trySuccess(pf(underlying))
            } catch {
              case e: Throwable => promise.tryFailure(e)
            }
          }
        }
        promise.future
      }

      def foldM[U](pf: PartialFunction[Try[A], Future[U]])(implicit executor: ExecutionContext): Future[U] = {
        val promise = Promise[U]
        future.andThen {
          case underlying: Try[A] => {
            try {
              pf(underlying).andThen {
                case Success(v) => promise.trySuccess(v)
                case Failure(e) => promise.tryFailure(e)
              }
            } catch {
              case e: Throwable => promise.tryFailure(e)
            }
          }
        }
        promise.future
      }

      def asLeft[R](implicit executor: ExecutionContext): Future[Either[A, R]] =
        future.map(a => Left[A, R](a))

      def asRight[R](implicit executor: ExecutionContext): Future[Either[R, A]] =
        future.map(a => Right[R, A](a))
    }
  }
}
