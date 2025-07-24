package otoroshi.utils

import org.apache.pekko.stream.scaladsl.{Keep, Sink, Source}
import org.apache.pekko.stream.{Materializer, OverflowStrategy, QueueOfferResult}

import scala.concurrent.{ExecutionContext, Future, Promise}

final class WorkQueue[A](buffer: Int)(using mat: Materializer) {

  type Task[A] = () => Future[A]

  def apply(future: => Future[A])(using ec: ExecutionContext): Future[A] = run(() => future)

  def run(task: Task[A])(using ec: ExecutionContext): Future[A] = {
    val promise = Promise[A]()
    queue.offer(task -> promise) flatMap {
      case QueueOfferResult.Enqueued => promise.future
      case result                    => Future failed new Exception(s"Can't enqueue: $result")
    }
  }

  private val queue = Source
    .queue[(Task[A], Promise[A])](buffer, OverflowStrategy.dropHead)
    .mapAsync(1) { case (task, promise) =>
      val rf: Future[A] = task()
      promise.completeWith(rf)
      rf
    }
    .recover { case _: Exception =>
      () // keep processing tasks
    }
    .toMat(Sink.ignore)(Keep.left)
    .run()
}
