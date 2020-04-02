package otoroshi.utils.syntax

import akka.http.scaladsl.util.FastFuture
import akka.util.ByteString
import com.github.blemale.scaffeine.Cache
import utils.{Regex, RegexPool}

import scala.concurrent.{ExecutionContext, Future}

object implicits {
  implicit class BetterSyntax[A](private val obj: A) extends AnyVal {
    def some: Option[A] = Some(obj)
    def option: Option[A] = Some(obj)
    def left[B]: Either[A, B] = Left(obj)
    def right[B]: Either[B, A] = Right(obj)
    def future: Future[A] = FastFuture.successful(obj)
    def somef: Future[Option[A]] = FastFuture.successful(Some(obj))
    def leftf[B]: Future[Either[A, B]] = FastFuture.successful(Left(obj))
    def rightf[B]: Future[Either[B, A]] = FastFuture.successful(Right(obj))
  }
  implicit class BetterString(private val obj: String) extends AnyVal {
    import otoroshi.utils.string.Implicits._
    def slugify: String = obj.slug
    def wildcard: Regex = RegexPool.apply(obj)
    def regex: Regex = RegexPool.regex(obj)
    def byteString: ByteString = ByteString(obj)
  }
  implicit class BetterFuture[A](private val obj: Future[A]) extends AnyVal {
    def fleft[B](implicit ec: ExecutionContext): Future[Either[A, B]] = obj.map(v => Left(v))
    def fright[B](implicit ec: ExecutionContext): Future[Either[B, A]] = obj.map(v => Right(v))
  }
  implicit class BetterCache[A, B](val cache: Cache[A, B]) extends AnyVal {
    def getOrElse(key: A, el: => B): B = {
      cache.getIfPresent(key).getOrElse {
        val res = el
        cache.put(key, res)
        res
      }
    }
  }
}
