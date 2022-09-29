package otoroshi.utils.reactive

import org.reactivestreams.Publisher

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

object ReactiveStreamUtils {
  object MonoUtils {
    import reactor.core.publisher.Mono
    def fromFuture[A](future: => Future[A])(implicit ec: ExecutionContext): Mono[A] = {
      Mono.create[A] { sink =>
        future.andThen {
          case Success(value) => sink.success(value)
          case Failure(exception) => sink.error(exception)
        }
      }
    }
    def toFuture[A](mono: Mono[A]): Future[A] = {
      val promise = Promise.apply[A]()
      mono.toFuture.handle[Unit] { (res: A, ex: Throwable) =>
        if (ex != null) {
          promise.tryFailure(ex)
        } else {
          promise.trySuccess(res)
        }
        ()
      }
      promise.future
    }

  }
  object FluxUtils {
    import reactor.core.publisher._
    def fromFPublisher[A](future: => Future[Publisher[A]])(implicit ec: ExecutionContext): Flux[A] = {
      Mono.create[Publisher[A]] { sink =>
        future.andThen {
          case Success(value) => sink.success(value)
          case Failure(exception) => sink.error(exception)
        }
      }.flatMapMany(i => i)
    }
    def toFuture[A](flux: Flux[A]): Future[A] = {
      val promise = Promise.apply[A]()
      flux
        .doOnError(err => promise.tryFailure(err))
        .doOnCancel(() => promise.tryFailure(new RuntimeException("flux canceled")))
        .doOnComplete(() => promise.tryFailure(new RuntimeException("flux completed without element")))
        .doOnNext(e => promise.trySuccess(e))
        .subscribe()
      promise.future
    }
  }
}