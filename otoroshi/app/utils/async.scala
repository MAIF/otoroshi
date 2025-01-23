package otoroshi.utils

import otoroshi.utils.syntax.implicits.BetterSyntax

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

/**
 * This one should let use avoid using akka-stream when doing something like Source(seq).mapAsync(1) { ... }.runWith(Sink.seq)
 * or Source(seq).mapAsync(1) { ... }.runWith(Sink.ignore)
 */
object AsyncUtils {

  def mapAsync[A](items: Seq[Future[A]])(implicit ec: ExecutionContext): Future[Seq[A]] = {

    val promise = Promise[Seq[A]]()
    var results = Seq.empty[A]

    def next(futures: Seq[Future[A]]): Unit = {
      if (futures.isEmpty) {
        promise.trySuccess(results)
      } else {
        val head = futures.head
        head.andThen {
          case Failure(e) => promise.tryFailure(e)
          case Success(value) => {
            results = results :+ value
            if (futures.size == 1) {
              promise.trySuccess(results)
            } else {
              next(futures.tail)
            }
          }
        }
      }
    }

    next(items)
    promise.future
  }

  def mapAsyncF[I, A](items: Seq[I])(f: Function[I, Future[A]])(implicit ec: ExecutionContext): Future[Seq[A]] = {

    val promise = Promise[Seq[A]]()
    var results = Seq.empty[A]

    def next(all: Seq[I]): Unit = {
      if (all.isEmpty) {
        promise.trySuccess(results)
      } else {
        val head = all.head
        f(head).andThen {
          case Failure(e) => promise.tryFailure(e)
          case Success(value) => {
            results = results :+ value
            if (all.size == 1) {
              promise.trySuccess(results)
            } else {
              next(all.tail)
            }
          }
        }
      }
    }

    next(items)
    promise.future
  }

  def foreachAsync[A](items: Seq[Future[A]])(implicit ec: ExecutionContext): Future[Unit] = {

    val promise = Promise[Unit]()

    def next(futures: Seq[Future[A]]): Unit = {
      if (futures.isEmpty) {
        promise.trySuccess(())
      } else {
        val head = futures.head
        head.andThen {
          case Failure(e) => promise.tryFailure(e)
          case Success(_) => {
            if (futures.size == 1) {
              promise.trySuccess(())
            } else {
              next(futures.tail)
            }
          }
        }
      }
    }

    next(items)
    promise.future
  }

  def foreachAsyncF[I, A](items: Seq[I])(f: Function[I, Future[A]])(implicit ec: ExecutionContext): Future[Unit] = {

    val promise = Promise[Unit]()

    def next(all: Seq[I]): Unit = {
      if (all.isEmpty) {
        promise.trySuccess(())
      } else {
        val head = all.head
        f(head).andThen {
          case Failure(e) => promise.tryFailure(e)
          case Success(_) => {
            if (all.size == 1) {
              promise.trySuccess(())
            } else {
              next(all.tail)
            }
          }
        }
      }
    }

    next(items)
    promise.future
  }

  def chainAsync[A](items: Seq[Function[A, Future[A]]])(input: A)(implicit ec: ExecutionContext): Future[A] = {
    val promise = Promise[A]()
    var latest: A = input

    def next(futures: Seq[Function[A, Future[A]]]): Unit = {
      if (futures.isEmpty) {
        promise.trySuccess(latest)
      } else {
        val head = futures.head
        head(latest).andThen {
          case Failure(e) => promise.tryFailure(e)
          case Success(l) => {
            latest = l
            if (futures.size == 1) {
              promise.trySuccess(latest)
            } else {
              next(futures.tail)
            }
          }
        }
      }
    }

    next(items)
    promise.future
  }

  def chainAsyncF[I, A](items: Seq[I])(input: A)(f: Function2[I, A, Future[A]])(implicit ec: ExecutionContext): Future[A] = {
    val promise = Promise[A]()
    var latest: A = input

    def next(all: Seq[I]): Unit = {
      if (all.isEmpty) {
        promise.trySuccess(latest)
      } else {
        val head = all.head
        f(head, latest).andThen {
          case Failure(e) => promise.tryFailure(e)
          case Success(l) => {
            latest = l
            if (all.size == 1) {
              promise.trySuccess(latest)
            } else {
              next(all.tail)
            }
          }
        }
      }
    }

    next(items)
    promise.future
  }

  def chainAsyncE[Err, A](items: Seq[Function[A, Future[Either[Err, A]]]])(input: A)(implicit ec: ExecutionContext): Future[Either[Err, A]] = {
    val promise = Promise[Either[Err, A]]()
    var latest: A = input

    def next(futures: Seq[Function[A, Future[Either[Err, A]]]]): Unit = {
      if (futures.isEmpty) {
        promise.trySuccess(latest.right)
      } else {
        val head = futures.head
        head(latest).andThen {
          case Failure(e) => promise.tryFailure(e)
          case Success(Left(err)) => promise.trySuccess(err.left)
          case Success(Right(value)) => {
            latest = value
            if (futures.size == 1) {
              promise.trySuccess(latest.right)
            } else {
              next(futures.tail)
            }
          }
        }
      }
    }

    next(items)
    promise.future
  }

  def chainAsyncFE[Err, I, A](items: Seq[I])(input: A)(f: Function2[I, A, Future[Either[Err, A]]])(implicit ec: ExecutionContext): Future[Either[Err, A]] = {
    val promise = Promise[Either[Err, A]]()
    var latest: A = input

    def next(all: Seq[I]): Unit = {
      if (all.isEmpty) {
        promise.trySuccess(latest.right)
      } else {
        val head = all.head
        f(head, latest).andThen {
          case Failure(e) => promise.tryFailure(e)
          case Success(Left(err)) => promise.trySuccess(err.left)
          case Success(Right(value)) => {
            latest = value
            if (all.size == 1) {
              promise.trySuccess(latest.right)
            } else {
              next(all.tail)
            }
          }
        }
      }
    }

    next(items)
    promise.future
  }

}
