package otoroshi.utils.reactive

import org.reactivestreams.Publisher

import scala.concurrent.{ExecutionContext, Future}
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
  }
}