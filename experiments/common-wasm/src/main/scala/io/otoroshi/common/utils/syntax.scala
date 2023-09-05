package io.otoroshi.common.utils

import akka.NotUsed
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.apache.commons.codec.binary.{Base64, Hex}
import play.api.Logger
import play.api.libs.json._

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.cert.{CertificateFactory, X509Certificate}
import scala.collection.TraversableOnce
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

private[common] object implicits {

  implicit class BetterSyntax[A](private val obj: A) extends AnyVal {
    def seq: Seq[A] = Seq(obj)

    def set: Set[A] = Set(obj)

    def list: List[A] = List(obj)

    def some: Option[A] = Some(obj)

    def none: Option[A] = None

    def option: Option[A] = Some(obj)

    def left[B]: Either[A, B] = Left(obj)

    def right[B]: Either[B, A] = Right(obj)

    @inline
    def vfuture: Future[A] = {
      // Future.successful(obj)
      FastFuture.successful(obj)
    }

    @inline
    def stdFuture: Future[A] = Future.successful(obj)

    @inline
    def future: Future[A] = FastFuture.successful(obj)

    def asFuture: Future[A] = FastFuture.successful(obj)

    def toFuture: Future[A] = FastFuture.successful(obj)

    def somef: Future[Option[A]] = FastFuture.successful(Some(obj))

    def leftf[B]: Future[Either[A, B]] = FastFuture.successful(Left(obj))

    def rightf[B]: Future[Either[B, A]] = FastFuture.successful(Right(obj))

    def debug(f: A => Any): A = {
      f(obj)
      obj
    }

    def debugPrintln: A = {
      println(obj)
      obj
    }

    def debugPrintlnWithPrefix(prefix: String): A = {
      println(prefix + " - " + obj)
      obj
    }

    def debugLogger(logger: Logger): A = {
      if (logger.isDebugEnabled) logger.debug(s"$obj")
      obj
    }

    def applyOn[B](f: A => B): B = f(obj)

    def applyOnIf(predicate: => Boolean)(f: A => A): A = if (predicate) f(obj) else obj

    def applyOnWithOpt[B](opt: => Option[B])(f: (A, B) => A): A = if (opt.isDefined) f(obj, opt.get) else obj

    def applyOnWithPredicate(predicate: A => Boolean)(f: A => A): A = if (predicate(obj)) f(obj) else obj

    def seffectOn(f: A => Unit): A = {
      f(obj)
      obj
    }

    def seffectOnIf(predicate: => Boolean)(f: A => Unit): A = {
      if (predicate) {
        f(obj)
        obj
      } else obj
    }

    def seffectOnWithPredicate(predicate: A => Boolean)(f: A => Unit): A = {
      if (predicate(obj)) {
        f(obj)
        obj
      } else obj
    }

    def singleSource: Source[A, NotUsed] = Source.single(obj)
  }

  implicit class BetterString(private val obj: String) extends AnyVal {

    def byteString: ByteString = ByteString(obj)

    def bytes: Array[Byte] = obj.getBytes(StandardCharsets.UTF_8)

    def json: JsValue = JsString(obj)

    def parseJson: JsValue = Json.parse(obj)

    def encodeBase64: String = Base64.encodeBase64String(obj.getBytes(StandardCharsets.UTF_8))

    def base64: String = Base64.encodeBase64String(obj.getBytes(StandardCharsets.UTF_8))

    def base64UrlSafe: String = Base64.encodeBase64URLSafeString(obj.getBytes(StandardCharsets.UTF_8))

    def fromBase64: String = new String(Base64.decodeBase64(obj), StandardCharsets.UTF_8)

    def decodeBase64: String = new String(Base64.decodeBase64(obj), StandardCharsets.UTF_8)

    def sha256: String =
      Hex.encodeHexString(MessageDigest.getInstance("SHA-256").digest(obj.getBytes(StandardCharsets.UTF_8)))

    def sha512: String =
      Hex.encodeHexString(MessageDigest.getInstance("SHA-512").digest(obj.getBytes(StandardCharsets.UTF_8)))

    def chunks(size: Int): Source[String, NotUsed] = Source(obj.grouped(size).toList)

    def camelToSnake: String = {
      obj.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase
      // obj.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase
    }
  }

  implicit class BetterByteString(private val obj: ByteString) extends AnyVal {

    def chunks(size: Int): Source[ByteString, NotUsed] = Source(obj.grouped(size).toList)

    def sha256: String = Hex.encodeHexString(MessageDigest.getInstance("SHA-256").digest(obj.toArray))

    def sha512: String = Hex.encodeHexString(MessageDigest.getInstance("SHA-512").digest(obj.toArray))
  }

  implicit class BetterBoolean(private val obj: Boolean) extends AnyVal {
    def json: JsValue = JsBoolean(obj)
  }

  implicit class BetterDouble(private val obj: Double) extends AnyVal {
    def json: JsValue = JsNumber(obj)
  }

  implicit class BetterInt(private val obj: Int) extends AnyVal {
    def json: JsValue = JsNumber(obj)

    def bytes: Array[Byte] = {
      Array[Byte](
        ((obj >> 24) & 0xff).asInstanceOf[Byte],
        ((obj >> 16) & 0xff).asInstanceOf[Byte],
        ((obj >> 8) & 0xff).asInstanceOf[Byte],
        ((obj >> 0) & 0xff).asInstanceOf[Byte]
      )
    }
  }

  implicit class BetterLong(private val obj: Long) extends AnyVal {
    def json: JsValue = JsNumber(obj)

    def bytes: Array[Byte] = {
      Array[Byte](
        ((obj >> 56) & 0xff).asInstanceOf[Byte],
        ((obj >> 48) & 0xff).asInstanceOf[Byte],
        ((obj >> 40) & 0xff).asInstanceOf[Byte],
        ((obj >> 32) & 0xff).asInstanceOf[Byte],
        ((obj >> 24) & 0xff).asInstanceOf[Byte],
        ((obj >> 16) & 0xff).asInstanceOf[Byte],
        ((obj >> 8) & 0xff).asInstanceOf[Byte],
        ((obj >> 0) & 0xff).asInstanceOf[Byte]
      )
    }
  }

  implicit class BetterJsValue(private val obj: JsValue) extends AnyVal {

    def stringify: String = Json.stringify(obj)

    def prettify: String = Json.prettyPrint(obj)

    def select(name: String): JsLookupResult = obj \ name

    def select(index: Int): JsLookupResult = obj \ index

    def at(path: String): JsLookupResult = {
      val parts = path.split("\\.").toSeq
      parts.foldLeft(Option(obj)) {
        case (Some(source: JsObject), part) => (source \ part).asOpt[JsValue]
        case (Some(source: JsArray), part) => (source \ part.toInt).asOpt[JsValue]
        case (Some(value), part) => None
        case (None, _) => None
      } match {
        case None => JsUndefined(s"path '${path}' does not exists")
        case Some(value) => JsDefined(value)
      }
    }

    def atPointer(path: String): JsLookupResult = {
      val parts = path.split("/").toSeq.filterNot(_.trim.isEmpty)
      parts.foldLeft(Option(obj)) {
        case (Some(source: JsObject), part) => (source \ part).asOpt[JsValue]
        case (Some(source: JsArray), part) => (source \ part.toInt).asOpt[JsValue]
        case (Some(value), part) => None
        case (None, _) => None
      } match {
        case None => JsUndefined(s"path '${path}' does not exists")
        case Some(value) => JsDefined(value)
      }
    }
  }

  implicit class BetterJsValueOption(private val obj: Option[JsValue]) extends AnyVal {
    def orJsNull: JsValue = obj.getOrElse(JsNull)
  }

  implicit class BetterJsLookupResult(private val obj: JsLookupResult) extends AnyVal {
    def select(name: String): JsLookupResult = obj \ name

    def select(index: Int): JsLookupResult = obj \ index

    def strConvert(): Option[String] = {
      obj.asOpt[JsValue].getOrElse(JsNull) match {
        case JsNull => "null".some
        case JsNumber(v) => v.toString().some
        case JsString(v) => v.some
        case JsBoolean(v) => v.toString.some
        case o@JsObject(_) => o.stringify.some
        case a@JsArray(_) => a.stringify.some
        case _ => None
      }
    }
  }

  implicit class BetterJsReadable(private val obj: JsReadable) extends AnyVal {
    def asString: String = obj.as[String]

    def asInt: Int = obj.as[Int]

    def asDouble: Double = obj.as[Double]

    def asLong: Long = obj.as[Long]

    def asBoolean: Boolean = obj.as[Boolean]

    def asObject: JsObject = obj.as[JsObject]

    def asArray: JsArray = obj.as[JsArray]

    def asValue: JsValue = obj.as[JsValue]

    def asOptString: Option[String] = obj.asOpt[String]

    def asOptBoolean: Option[Boolean] = obj.asOpt[Boolean]

    def asOptInt: Option[Int] = obj.asOpt[Int]

    def asOptLong: Option[Long] = obj.asOpt[Long]
  }

  implicit class BetterFuture[A](private val obj: Future[A]) extends AnyVal {

    def fleft[B](implicit ec: ExecutionContext): Future[Either[A, B]] = obj.map(v => Left(v))

    def fright[B](implicit ec: ExecutionContext): Future[Either[B, A]] = obj.map(v => Right(v))

    def asLeft[R](implicit executor: ExecutionContext): Future[Either[A, R]] = obj.map(a => Left[A, R](a))

    def asRight[R](implicit executor: ExecutionContext): Future[Either[R, A]] = obj.map(a => Right[R, A](a))

    def fold[U](pf: PartialFunction[Try[A], U])(implicit executor: ExecutionContext): Future[U] = {
      val promise = Promise[U]
      obj.andThen {
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
      obj.andThen {
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
  }

  implicit class BetterMapOfStringAndB[B](val theMap: Map[String, B]) extends AnyVal {
    def addAll(other: Map[String, B]): Map[String, B] = theMap.++(other)

    def put(key: String, value: B): Map[String, B] = theMap.+((key, value))

    def put(tuple: (String, B)): Map[String, B] = theMap.+(tuple)

    def remove(key: String): Map[String, B] = theMap.-(key)

    def removeIgnoreCase(key: String): Map[String, B] = theMap.-(key).-(key.toLowerCase())

    def containsIgnoreCase(key: String): Boolean = theMap.contains(key) || theMap.contains(key.toLowerCase())

    def getIgnoreCase(key: String): Option[B] = theMap.get(key).orElse(theMap.get(key.toLowerCase()))

    def removeAndPutIgnoreCase(tuple: (String, B)): Map[String, B] = removeIgnoreCase(tuple._1).put(tuple)
  }

  implicit class BetterTrieMapOfStringAndB[B](val theMap: TrieMap[String, B]) extends AnyVal {
    def add(tuple: (String, B)): TrieMap[String, B] = theMap.+=(tuple)

    def addAll(all: TraversableOnce[(String, B)]): TrieMap[String, B] = theMap.++=(all)

    def rem(key: String): TrieMap[String, B] = theMap.-=(key)

    def remIgnoreCase(key: String): TrieMap[String, B] = theMap.-=(key).-=(key.toLowerCase())

    def remAll(keys: TraversableOnce[String]): TrieMap[String, B] = theMap.--=(keys)

    def remAllIgnoreCase(keys: TraversableOnce[String]): TrieMap[String, B] =
      theMap.--=(keys).--=(keys.map(_.toLowerCase()))

    def containsIgnoreCase(key: String): Boolean = theMap.contains(key) || theMap.contains(key.toLowerCase())

    def getIgnoreCase(key: String): Option[B] = theMap.get(key).orElse(theMap.get(key.toLowerCase()))

    def remAndAddIgnoreCase(tuple: (String, B)): TrieMap[String, B] = remIgnoreCase(tuple._1).add(tuple)

    def getOrUpdate(k: String)(op: => B): B = theMap.getOrElseUpdate(k, op)
  }

  implicit class BetterSeqOfA[A](val seq: Seq[A]) extends AnyVal {
    def avgBy(f: A => Int): Double = {
      if (seq.isEmpty) 0.0
      else {
        val sum = seq.map(f).foldLeft(0) { case (a, b) =>
          a + b
        }
        sum / seq.size
      }
    }

    def findFirstSome[B](f: A => Option[B]): Option[B] = {
      if (seq.isEmpty) {
        None
      } else {
        for (a <- seq) {
          val res = f(a)
          if (res.isDefined) {
            return res
          }
        }
        None
      }
    }
  }
}