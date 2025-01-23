package otoroshi.utils

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

class AsyncUtils {

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
}
