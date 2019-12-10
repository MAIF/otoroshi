package otoroshi.utils

import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy, QueueOfferResult}

import scala.concurrent.{ExecutionContext, Future, Promise}

final class WorkQueue[A](buffer: Int)(implicit mat: Materializer) {

  type Task[A] = () => Future[A]

  def apply(future: => Future[A])(implicit ec: ExecutionContext): Future[A] = run(() => future)

  def run(task: Task[A])(implicit ec: ExecutionContext): Future[A] = {
    val promise = Promise[A]
    queue.offer(task -> promise) flatMap {
      case QueueOfferResult.Enqueued => promise.future
      case result => Future failed new Exception(s"Can't enqueue: $result")
    }
  }

  private val queue = Source
    .queue[(Task[A], Promise[A])](buffer, OverflowStrategy.dropNew)
    .mapAsync(1) {
      case (task, promise) => {
        val rf: Future[A] = task()
        promise.completeWith(rf)
        rf
      }
    }
    .recover {
      case _: Exception => () // keep processing tasks
    }
    .toMat(Sink.ignore)(Keep.left)
    .run
}
