package otoroshi.utils.misc

import scala.concurrent.Future

package object experiments {

  import Result._

  object Result {

    case class StoreError(messages: Seq[String])

    type Result[+E] = Either[StoreError, E]
    def ok[E](event: E): Result[E]             = Right(event)
    def error[E](error: StoreError): Result[E] = Left(error)
    def error[E](messages: String*): Result[E] = Left(StoreError(messages))

    implicit class ResultOps[E](r: Result[E]) {
      def collect[E2](p: PartialFunction[E, E2]): Result[E2] =
        r match {
          case Right(elt) if p.isDefinedAt(elt) => Result.ok(p(elt))
          case Right(elt)                       => Result.error("error.result.filtered")
          case Left(e)                          => Result.error(e)
        }
    }
  }

  trait DataStore[+Command, +Event] {
    def apply[E >: Event, C[_] >: Command](command: C[E])(implicit m: Manifest[E]): Future[Result[E]]
  }
}
