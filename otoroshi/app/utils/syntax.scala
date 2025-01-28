package otoroshi.utils.syntax

import akka.NotUsed
import akka.http.scaladsl.util.FastFuture
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.auth0.jwt.interfaces.DecodedJWT
import com.github.blemale.scaffeine.Cache
import com.typesafe.config.{ConfigFactory, ConfigRenderOptions}
import org.apache.commons.codec.binary.{Base64, Hex}
import otoroshi.env.Env
import otoroshi.models.WSProxyServerJson
import otoroshi.next.utils.JsonHelpers
import otoroshi.ssl.DynamicSSLEngineProvider
import otoroshi.utils.reactive.ReactiveStreamUtils
import otoroshi.utils.{AsyncUtils, JsonPathUtils, Regex, RegexPool}
import play.api.libs.json._
import play.api.libs.ws.{DefaultWSCookie, WSCookie, WSProxyServer}
import play.api.mvc.Cookie
import play.api.{ConfigLoader, Configuration, Logger}
import reactor.core.publisher.Mono

import java.io.{ByteArrayInputStream, File}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.{CertificateFactory, X509Certificate}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicReference}
import scala.collection.TraversableOnce
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.reflect.ClassTag
import scala.util.control.{NoStackTrace, NonFatal}
import scala.util.{Failure, Success, Try}

object implicits {

  // type Result[Success, Failure] = Either[Failure, Success]
  // type Ok[Success, Failure] = Right[Failure, Success]
  // type Err[Success, Failure] = Left[Failure, Success]

  implicit class BetterSeqOfInt(val seq: Seq[Int])                            extends AnyVal {
    def theMin(default: Int): Int = {
      if (seq.isEmpty) {
        default
      } else {
        seq.min
      }
    }
    def theMax(default: Int): Int = {
      if (seq.isEmpty) {
        default
      } else {
        seq.max
      }
    }
  }
  implicit class BetterSeqOfTupleStringString(val seq: Seq[(String, String)]) extends AnyVal {
    @inline
    def appendOpt[A](opt: Option[A], f: A => (String, String)): Seq[(String, String)] = {
      opt match {
        case None    => seq
        case Some(k) => seq :+ f(k)
      }
    }

    @inline
    def appendIf(f: => Boolean, header: => (String, String)): Seq[(String, String)] =
      if (f) {
        seq :+ header
      } else {
        seq
      }

    @inline
    def appendIfElse(f: => Boolean, name: String, ten: => String, els: => String): Seq[(String, String)] =
      if (f) {
        seq :+ (name, ten)
      } else {
        seq :+ (name, els)
      }

    @inline
    def removeIf(name: String, f: => Boolean): Seq[(String, String)] =
      if (f) seq.filterNot(_._1.toLowerCase() == name.toLowerCase()) else seq

    @inline
    def remove(name: String): Seq[(String, String)] = seq.filterNot(_._1.toLowerCase() == name.toLowerCase())

    @inline
    def removeAll(names: Seq[String]): Seq[(String, String)] = {
      val lowerNames = names.map(_.toLowerCase)
      seq.filterNot(h => lowerNames.contains(h._1.toLowerCase()))
    }

    @inline
    def removeAllArgs(names: String*): Seq[(String, String)] = {
      val lowerNames = names.map(_.toLowerCase)
      seq.filterNot(h => lowerNames.contains(h._1.toLowerCase()))
    }

    @inline
    def appendAll(other: Seq[(String, String)]): Seq[(String, String)] = seq ++ other

    @inline
    def appendAllArgs(other: (String, String)*): Seq[(String, String)] = seq ++ other

    @inline
    def appendAllArgsIf(f: => Boolean)(other: (String, String)*): Seq[(String, String)] = if (f) seq ++ other else seq

    @inline
    def lazyAppendAllArgsIf(f: => Boolean)(other: => Seq[(String, String)]): Seq[(String, String)] =
      if (f) seq ++ other else seq

    def debug(name: String): Seq[(String, String)] = {
      // println(name, seq.mkString("\n"))
      seq
    }
  }

  implicit class BetterMapOf[K, V](val map: Map[K, V]) extends AnyVal {
    def containsAll(elems: Map[K, V]): Boolean = {
      elems.forall(e => map.contains(e._1) && map.get(e._1).contains(e._2))
    }
  }

  implicit class BetterMapOfStringString(val seq: Map[String, String]) extends AnyVal {
    @inline
    def appendOpt[A](opt: Option[A], f: A => (String, String)): Map[String, String] = {
      opt match {
        case None    => seq
        case Some(k) => seq + f(k)
      }
    }

    @inline
    def appendIf(f: => Boolean, header: => (String, String)): Map[String, String] = {
      if (f) {
        seq + header
      } else {
        seq
      }
    }

    @inline
    def appendIfElse(f: => Boolean, name: String, ten: => String, els: => String): Map[String, String] = {
      if (f) {
        seq + ((name, ten))
      } else {
        seq + ((name, els))
      }
    }

    @inline
    def removeIf(name: String, f: => Boolean): Map[String, String] =
      if (f) seq.filterNot(_._1.toLowerCase() == name.toLowerCase()) else seq

    @inline
    def remove(name: String): Map[String, String] = seq.filterNot(_._1.toLowerCase() == name.toLowerCase())

    @inline
    def removeAll(names: Seq[String]): Map[String, String] = {
      val lowerNames = names.map(_.toLowerCase)
      seq.filterNot(h => lowerNames.contains(h._1.toLowerCase()))
    }

    @inline
    def removeAllArgs(names: String*): Map[String, String] = {
      val lowerNames = names.map(_.toLowerCase)
      seq.filterNot(h => lowerNames.contains(h._1.toLowerCase()))
    }

    @inline
    def appendAll(other: Seq[(String, String)]): Map[String, String] = seq ++ other

    @inline
    def appendAll(other: Map[String, String]): Map[String, String] = seq ++ other

    @inline
    def appendAllArgs(other: (String, String)*): Map[String, String] = seq ++ other

    @inline
    def appendAllArgsIf(f: => Boolean)(other: (String, String)*): Map[String, String] = if (f) seq ++ other else seq

    @inline
    def lazyAppendAllArgsIf(f: => Boolean)(other: => Seq[(String, String)]): Map[String, String] =
      if (f) seq ++ other else seq
  }

  implicit class BetterSyntax[A](private val obj: A)                   extends AnyVal {
    def seq: Seq[A]                                                 = Seq(obj)
    def set: Set[A]                                                 = Set(obj)
    def list: List[A]                                               = List(obj)
    def some: Option[A]                                             = Some(obj)
    def none: Option[A]                                             = None
    def option: Option[A]                                           = Some(obj)
    def left[B]: Either[A, B]                                       = Left(obj)
    def right[B]: Either[B, A]                                      = Right(obj)
    @inline
    def vfuture: Future[A] = {
      // Future.successful(obj)
      FastFuture.successful(obj)
    }
    @inline
    def stdFuture: Future[A]                                        = Future.successful(obj)
    @inline
    def future: Future[A]                                           = FastFuture.successful(obj)
    def asFuture: Future[A]                                         = FastFuture.successful(obj)
    def toFuture: Future[A]                                         = FastFuture.successful(obj)
    def somef: Future[Option[A]]                                    = FastFuture.successful(Some(obj))
    def leftf[B]: Future[Either[A, B]]                              = FastFuture.successful(Left(obj))
    def rightf[B]: Future[Either[B, A]]                             = FastFuture.successful(Right(obj))
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
    def applyOn[B](f: A => B): B                                    = f(obj)
    def applyOnIf(predicate: => Boolean)(f: A => A): A              = if (predicate) f(obj) else obj
    def applyOnWithOpt[B](opt: => Option[B])(f: (A, B) => A): A     = if (opt.isDefined) f(obj, opt.get) else obj
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
  implicit class RegexOps(sc: StringContext) {
    def rr = new scala.util.matching.Regex(sc.parts.mkString)
  }
  implicit class BetterString(private val obj: String)                 extends AnyVal {
    import otoroshi.utils.string.Implicits._
    def slugify: String                            = obj.slug
    def slugifyWithSlash: String                   = obj.slug2
    def wildcard: Regex                            = RegexPool.apply(obj)
    def regex: Regex                               = RegexPool.regex(obj)
    def byteString: ByteString                     = ByteString(obj)
    def bytes: Array[Byte]                         = obj.getBytes(StandardCharsets.UTF_8)
    def json: JsValue                              = JsString(obj)
    def parseJson: JsValue                         = Json.parse(obj)
    def encodeBase64: String                       = Base64.encodeBase64String(obj.getBytes(StandardCharsets.UTF_8))
    def base64: String                             = Base64.encodeBase64String(obj.getBytes(StandardCharsets.UTF_8))
    def base64UrlSafe: String                      = Base64.encodeBase64URLSafeString(obj.getBytes(StandardCharsets.UTF_8))
    def fromBase64: String                         = new String(Base64.decodeBase64(obj), StandardCharsets.UTF_8)
    def decodeBase64: String                       = new String(Base64.decodeBase64(obj), StandardCharsets.UTF_8)
    def sha256: String                             =
      Hex.encodeHexString(MessageDigest.getInstance("SHA-256").digest(obj.getBytes(StandardCharsets.UTF_8)))
    def sha512: String                             =
      Hex.encodeHexString(MessageDigest.getInstance("SHA-512").digest(obj.getBytes(StandardCharsets.UTF_8)))
    def chunks(size: Int): Source[String, NotUsed] = Source(obj.grouped(size).toList)
    def camelToSnake: String = {
      obj.replaceAll("([a-z])([A-Z]+)", "$1_$2").toLowerCase
      // obj.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase
    }
    def toCertificate: X509Certificate = {
      val certificateFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
      certificateFactory
        .generateCertificate(new ByteArrayInputStream(DynamicSSLEngineProvider.base64Decode(obj)))
        .asInstanceOf[X509Certificate]
    }
  }
  implicit class BetterByteString(private val obj: ByteString)         extends AnyVal {
    def chunks(size: Int): Source[ByteString, NotUsed] = Source(obj.grouped(size).toList)
    def sha256: String                                 = Hex.encodeHexString(MessageDigest.getInstance("SHA-256").digest(obj.toArray))
    def sha512: String                                 = Hex.encodeHexString(MessageDigest.getInstance("SHA-512").digest(obj.toArray))
  }
  implicit class BetterBoolean(private val obj: Boolean)               extends AnyVal {
    def json: JsValue = JsBoolean(obj)
  }
  implicit class BetterDouble(private val obj: Double)                 extends AnyVal {
    def json: JsValue = JsNumber(obj)
  }
  implicit class BetterInt(private val obj: Int)                       extends AnyVal {
    def json: JsValue         = JsNumber(obj)
    def atomic: AtomicInteger = new AtomicInteger(obj)
    def bytes: Array[Byte] = {
      Array[Byte](
        ((obj >> 24) & 0xff).asInstanceOf[Byte],
        ((obj >> 16) & 0xff).asInstanceOf[Byte],
        ((obj >> 8) & 0xff).asInstanceOf[Byte],
        ((obj >> 0) & 0xff).asInstanceOf[Byte]
      )
    }
  }
  implicit class BetterLong(private val obj: Long)                     extends AnyVal {
    def json: JsValue      = JsNumber(obj)
    def atomic: AtomicLong = new AtomicLong(obj)
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
  implicit class BetterJsValue(private val obj: JsValue)               extends AnyVal {
    def stringify: String                    = Json.stringify(obj)
    def prettify: String                     = Json.prettyPrint(obj)
    def select(name: String): JsLookupResult = obj \ name
    def select(index: Int): JsLookupResult   = obj \ index
    def at(path: String): JsLookupResult = {
      val parts = path.split("\\.").toSeq
      parts.foldLeft(Option(obj)) {
        case (Some(source: JsObject), part) => (source \ part).asOpt[JsValue]
        case (Some(source: JsArray), part)  => (source \ part.toInt).asOpt[JsValue]
        case (Some(value), part)            => None
        case (None, _)                      => None
      } match {
        case None        => JsUndefined(s"path '${path}' does not exists")
        case Some(value) => JsDefined(value)
      }
    }
    def atPointer(path: String): JsLookupResult = {
      val parts = path.split("/").toSeq.filterNot(_.trim.isEmpty)
      parts.foldLeft(Option(obj)) {
        case (Some(source: JsObject), part) => (source \ part).asOpt[JsValue]
        case (Some(source: JsArray), part)  => (source \ part.toInt).asOpt[JsValue]
        case (Some(value), part)            => None
        case (None, _)                      => None
      } match {
        case None        => JsUndefined(s"path '${path}' does not exists")
        case Some(value) => JsDefined(value)
      }
    }
    def atPath(path: String): JsLookupResult = {
      JsonPathUtils.getAtPolyJson(obj, path) match {
        case None        => JsUndefined(s"path '${path}' does not exists")
        case Some(value) => JsDefined(value)
      }
    }
  }
  implicit class BetterJsValueOption(private val obj: Option[JsValue]) extends AnyVal {
    def orJsNull: JsValue = obj.getOrElse(JsNull)
  }
  implicit class BetterJsLookupResult(private val obj: JsLookupResult) extends AnyVal {
    def select(name: String): JsLookupResult = obj \ name
    def select(index: Int): JsLookupResult   = obj \ index
    def strConvert(): Option[String] = {
      obj.asOpt[JsValue].getOrElse(JsNull) match {
        case JsNull          => "null".some
        case JsNumber(v)     => v.toString().some
        case JsString(v)     => v.some
        case JsBoolean(v)    => v.toString.some
        case o @ JsObject(_) => o.stringify.some
        case a @ JsArray(_)  => a.stringify.some
        case _               => None
      }
    }
  }
  implicit class BetterJsReadable(private val obj: JsReadable)         extends AnyVal {
    def asString: String              = obj.as[String]
    def asInt: Int                    = obj.as[Int]
    def asDouble: Double              = obj.as[Double]
    def asLong: Long                  = obj.as[Long]
    def asBoolean: Boolean            = obj.as[Boolean]
    def asObject: JsObject            = obj.as[JsObject]
    def asArray: JsArray              = obj.as[JsArray]
    def asValue: JsValue              = obj.as[JsValue]
    def asOptString: Option[String]   = obj.asOpt[String]
    def asOptBoolean: Option[Boolean] = obj.asOpt[Boolean]
    def asOptInt: Option[Int]         = obj.asOpt[Int]
    def asOptLong: Option[Long]       = obj.asOpt[Long]
  }
  implicit class BetterFuture[A](private val obj: Future[A])           extends AnyVal {
    def block(atMost: Duration): A                                            = Await.result(obj, atMost)
    def awaitf(atMost: Duration)(implicit ec: ExecutionContext): A            = Await.result(obj, atMost)
    def awaitfsafe(atMost: Duration)(implicit ec: ExecutionContext): Try[A]   = Try(Await.result(obj, atMost))
    def mono(implicit ec: ExecutionContext): Mono[A]                          = ReactiveStreamUtils.MonoUtils.fromFuture(obj)
    def fleft[B](implicit ec: ExecutionContext): Future[Either[A, B]]         = obj.map(v => Left(v))
    def fright[B](implicit ec: ExecutionContext): Future[Either[B, A]]        = obj.map(v => Right(v))
    def asLeft[R](implicit executor: ExecutionContext): Future[Either[A, R]]  = obj.map(a => Left[A, R](a))
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
    def filterWithCause(cause: String, include: Boolean = false)(
        f: A => Boolean
    )(implicit ec: ExecutionContext): Future[A] = {
      obj.transform { t =>
        if (t.isSuccess) {
          val value = t.asInstanceOf[Success[A]].value
          if (f(value)) {
            t
          } else {
            val inc = if (include) s" - ${value.toString}" else ""
            Failure[A](new FilterExceptionWithoutCause(cause + inc))
          }
        } else {
          val failure = t.asInstanceOf[Failure[A]].exception
          Failure[A](new FilterException(cause, failure))
        }
      }
    }
  }
  class FilterException(str: String, cause: Throwable)                 extends RuntimeException(str, cause) with NoStackTrace
  class FilterExceptionWithoutCause(str: String)                       extends RuntimeException(str) with NoStackTrace
  implicit class BetterCache[A, B](val cache: Cache[A, B])             extends AnyVal {
    def getOrElse(key: A, el: => B): B = {
      cache.getIfPresent(key).getOrElse {
        val res = el
        cache.put(key, res)
        res
      }
    }
  }
  object BetterConfiguration {
    val logger = Logger("otoroshi-better-configuration")
  }
  implicit class BetterConfiguration(val configuration: Configuration) extends AnyVal {

    import collection.JavaConverters._

    private def readFromFile[A](path: String, loader: ConfigLoader[A], classTag: ClassTag[A]): Option[A] = {
      val file = new File(path)
      if (file.exists()) {
        Try {
          val content = Files.readAllLines(file.toPath).asScala.mkString("\n").trim
          Try {
            val config = Configuration(ConfigFactory.parseString(s"""value=${content}""".stripMargin))
            config.getOptional[A]("value")(loader)
          } match {
            case Failure(_)     =>
              classTag.runtimeClass.getName match {
                case "boolean"          => Option(content.toBoolean.asInstanceOf[A])
                case "Boolean"          => Option(content.toBoolean.asInstanceOf[A])
                case "Int"              => Option(content.toInt.asInstanceOf[A])
                case "int"              => Option(content.toInt.asInstanceOf[A])
                case "Double"           => Option(content.toDouble.asInstanceOf[A])
                case "double"           => Option(content.toDouble.asInstanceOf[A])
                case "Long"             => Option(content.toLong.asInstanceOf[A])
                case "long"             => Option(content.toLong.asInstanceOf[A])
                case "String"           => Option(content.asInstanceOf[A])
                case "java.lang.String" => Option(content.asInstanceOf[A])
                case _                  => None
              }
            case Success(value) => value
          }
        }.get
      } else {
        None
      }
    }

    def validateAndComputePath[A](_path: String, f: String => A): String = {
      if (_path.startsWith("app.")) {
        val newPath: String = _path.replaceFirst("app", "otoroshi")
        // BetterConfiguration.logger.error(s"You chould change '${_path}' to '${newPath}'")
        val app             = f(_path)
        val oto             = f(newPath)
        if (app != oto) {
          val name = Try {
            val caller = new Throwable().getStackTrace().tail.head
            (caller.getClassName + "." + caller.getMethodName())
          }.getOrElse("--")
          BetterConfiguration.logger.warn(
            s"configuration key '${_path}' does not match with '${newPath}' on ${name}. Please report this error to https://github.com/MAIF/otoroshi/issues"
          )
          _path
        } else {
          newPath
        }
      } else {
        _path
      }
    }

    def betterHas(_path: String): Boolean = {
      val path = validateAndComputePath(_path, configuration.has(_))
      configuration.has(path)
    }

    def betterGet[A](_path: String)(implicit loader: ConfigLoader[A]): A = {
      val path = validateAndComputePath(_path, p => configuration.getOptional[A](p)(loader))
      configuration.get[A](path)(loader)
    }

    def betterGetOptional[A](_path: String)(implicit loader: ConfigLoader[A]): Option[A] = {
      val path = validateAndComputePath(_path, p => configuration.getOptional[A](p)(loader))
      configuration.getOptional[A](path)(loader)
    }

    def getOpt[A](path: String)(implicit loader: ConfigLoader[A]): Option[A] = {
      try {
        if (configuration.underlying.hasPath(path)) Some(configuration.get[A](path)) else None
      } catch {
        case NonFatal(e) => None
      }
    }

    def getOptionalWithFileSupport[A](
        _path: String
    )(implicit loader: ConfigLoader[A], classTag: ClassTag[A]): Option[A] = {
      val path = validateAndComputePath(_path, p => configuration.getOptional[A](p)(loader))
      Try(configuration.getOptional[A](path)(loader)).toOption.flatten match {
        case None        =>
          Try(configuration.getOptional[String](path)(ConfigLoader.stringLoader)).toOption.flatten match {
            case Some(v) if v.startsWith("file://") => readFromFile[A](v.replace("file://", ""), loader, classTag)
            case _                                  => None
          }
        case Some(value) =>
          value match {
            case Some(v: String) if v.startsWith("file://") =>
              readFromFile[A](v.replace("file://", ""), loader, classTag)
            case v                                          => Some(v)
          }
      }
    }

    def json: JsObject = Json
      .parse(configuration.underlying.root().render(ConfigRenderOptions.concise()))
      .asObject
  }

  implicit class BetterDecodedJWT(val jwt: DecodedJWT)                        extends AnyVal {
    def claimStr(name: String): Option[String]   =
      Option(jwt.getClaim(name)).filterNot(_.isNull).filterNot(_.isMissing).map(_.asString())
    def claimBool(name: String): Option[Boolean] =
      Option(jwt.getClaim(name)).filterNot(_.isNull).filterNot(_.isMissing).map(_.asBoolean())
    def claimInt(name: String): Option[Int]      =
      Option(jwt.getClaim(name)).filterNot(_.isNull).filterNot(_.isMissing).map(_.asInt())
    def claimLng(name: String): Option[Long]     =
      Option(jwt.getClaim(name)).filterNot(_.isNull).filterNot(_.isMissing).map(_.asLong())
    def claimDbl(name: String): Option[Double]   =
      Option(jwt.getClaim(name)).filterNot(_.isNull).filterNot(_.isMissing).map(_.asDouble())
    def json: JsValue                            = Json.obj(
      "header"  -> jwt.getHeader.fromBase64.parseJson,
      "payload" -> jwt.getPayload.fromBase64.parseJson
    )
  }
  implicit class BetterAtomicReference[A](val ref: AtomicReference[A])        extends AnyVal {
    def getOrSet(f: => A): A = {
      val initValue = ref.get()
      if (initValue == null) {
        val value: A = f
        ref.set(value)
        value
      } else {
        initValue
      }
    }
  }
  implicit class BetterFiniteDuration(val duration: FiniteDuration)           extends AnyVal {
    def timeout(implicit env: Env, ec: ExecutionContext): Future[Unit] = await(env, ec)
    def await(implicit env: Env, ec: ExecutionContext): Future[Unit] = {
      val promise = Promise.apply[Unit]()
      env.otoroshiScheduler.scheduleOnce(duration) {
        promise.trySuccess(())
      }
      promise.future
    }
  }
  implicit class BetterDuration(val duration: Duration)                       extends AnyVal {
    final def toHumanReadable: String = {
      val units       = Seq(
        TimeUnit.DAYS,
        TimeUnit.HOURS,
        TimeUnit.MINUTES,
        TimeUnit.SECONDS,
        TimeUnit.MILLISECONDS,
        TimeUnit.MICROSECONDS,
        TimeUnit.NANOSECONDS
      )
      val timeStrings = units
        .foldLeft((Seq.empty[String], duration.toNanos))({ case ((humanReadable, rest), unit) =>
          val name   = unit.toString().toLowerCase()
          val result = unit.convert(rest, TimeUnit.NANOSECONDS)
          val diff   = rest - TimeUnit.NANOSECONDS.convert(result, unit)
          val str    = result match {
            case 0    => humanReadable
            case 1    => humanReadable :+ s"1 ${name.init}" // Drop last 's'
            case more => humanReadable :+ s"$more $name"
          }
          (str, diff)
        })
        ._1
      timeStrings.size match {
        case 0 => ""
        case 1 => timeStrings.head
        case _ => timeStrings.init.mkString(", ") + " and " + timeStrings.last
      }
    }
  }
  implicit class BetterMapOfStringAndB[B](val theMap: Map[String, B])         extends AnyVal {
    def addAll(other: Map[String, B]): Map[String, B]              = theMap.++(other)
    def put(key: String, value: B): Map[String, B]                 = theMap.+((key, value))
    def put(tuple: (String, B)): Map[String, B]                    = theMap.+(tuple)
    def remove(key: String): Map[String, B]                        = theMap.-(key)
    def removeIgnoreCase(key: String): Map[String, B]              = theMap.-(key).-(key.toLowerCase())
    def containsIgnoreCase(key: String): Boolean                   = theMap.contains(key) || theMap.contains(key.toLowerCase())
    def getIgnoreCase(key: String): Option[B]                      = theMap.get(key).orElse(theMap.get(key.toLowerCase()))
    def removeAndPutIgnoreCase(tuple: (String, B)): Map[String, B] = removeIgnoreCase(tuple._1).put(tuple)
  }
  implicit class BetterTrieMapOfStringAndB[B](val theMap: TrieMap[String, B]) extends AnyVal {
    def add(tuple: (String, B)): TrieMap[String, B]                         = theMap.+=(tuple)
    def addAll(all: TraversableOnce[(String, B)]): TrieMap[String, B]       = theMap.++=(all)
    def rem(key: String): TrieMap[String, B]                                = theMap.-=(key)
    def remIgnoreCase(key: String): TrieMap[String, B]                      = theMap.-=(key).-=(key.toLowerCase())
    def remAll(keys: TraversableOnce[String]): TrieMap[String, B]           = theMap.--=(keys)
    def remAllIgnoreCase(keys: TraversableOnce[String]): TrieMap[String, B] =
      theMap.--=(keys).--=(keys.map(_.toLowerCase()))
    def containsIgnoreCase(key: String): Boolean                            = theMap.contains(key) || theMap.contains(key.toLowerCase())
    def getIgnoreCase(key: String): Option[B]                               = theMap.get(key).orElse(theMap.get(key.toLowerCase()))
    def remAndAddIgnoreCase(tuple: (String, B)): TrieMap[String, B]         = remIgnoreCase(tuple._1).add(tuple)
    def getOrUpdate(k: String)(op: => B): B                                 = theMap.getOrElseUpdate(k, op)
  }
  implicit class BetterSeqOfA[A](val seq: Seq[A])                             extends AnyVal {
    def containsAll(elems: Seq[A]): Boolean = {
      elems.forall(e => seq.contains(e))
    }

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

    def mapAsync[O](f: Function[A, Future[O]])(implicit ec: ExecutionContext): Future[Seq[O]] = {
      AsyncUtils.mapAsyncF[A, O](seq)(f)
    }

    def flatmapAsync[O](f: Function[A, Future[Seq[O]]])(implicit ec: ExecutionContext): Future[Seq[O]] = {
      AsyncUtils.flatmapAsyncF[A, O](seq)(f)
    }

    def foreachAsync[O](f: Function[A, Future[O]])(implicit ec: ExecutionContext): Future[Unit] = {
      AsyncUtils.foreachAsyncF[A, O](seq)(f)
    }

    def chainAsync[I](input: I)(f: Function2[A, I, Future[I]])(implicit ec: ExecutionContext): Future[I] = {
      AsyncUtils.chainAsyncF[A, I](seq)(input)(f)
    }

    def chainAsyncE[Err, I](input: I)(f: Function2[A, I, Future[Either[Err, I]]])(implicit ec: ExecutionContext): Future[Either[Err, I]] = {
      AsyncUtils.chainAsyncFE[Err, A, I](seq)(input)(f)
    }
  }

  implicit class BetterWSCookieWithSameSite(val c: otoroshi.utils.http.WSCookieWithSameSite) extends AnyVal {
    def json: JsValue = JsonHelpers.wsCookieToJson(c)
    def toCookie: play.api.mvc.Cookie = {
      play.api.mvc.Cookie(
        name = c.name,
        value = c.value,
        maxAge = c.maxAge.map(_.toInt),
        path = c.path.getOrElse("/"),
        domain = c.domain,
        secure = c.secure,
        httpOnly = c.httpOnly,
        sameSite = c.sameSite
      )
    }
  }

  implicit class BetterCookie(val cookie: Cookie) extends AnyVal {
    def json: JsValue        = JsonHelpers.cookieToJson(cookie)
    def toWSCookie: WSCookie = wsCookie
    def wsCookie: WSCookie   = DefaultWSCookie(
      name = cookie.name,
      value = cookie.value,
      domain = cookie.domain,
      path = cookie.path.some,
      maxAge = cookie.maxAge.map(_.toLong),
      secure = cookie.secure,
      httpOnly = cookie.httpOnly
    )
    // def json: JsValue = Json.obj(
    //   "name" -> cookie.name,
    //   "value" -> cookie.value,
    //   "maxAge" -> cookie.maxAge.map(n => JsNumber(BigDecimal(n))).getOrElse(JsNull).asValue,
    //   "path" -> cookie.path,
    //   "domain" -> cookie.domain.map(JsString.apply).getOrElse(JsNull).asValue,
    //   "secure" -> cookie.secure,
    //   "httpOnly" -> cookie.httpOnly,
    //   "sameSite" -> cookie.sameSite.map(_.value.json).getOrElse(JsNull).asValue
    // )
  }

  implicit class BetterWSProxyServer(val proxy: WSProxyServer) extends AnyVal {
    def json: JsValue = WSProxyServerJson.proxyToJson(proxy)
  }

  implicit class BetterWSCookie(val c: WSCookie) extends AnyVal {
    def mvcCookie: Cookie = toCookie
    def toCookie: play.api.mvc.Cookie = {
      play.api.mvc.Cookie(
        name = c.name,
        value = c.value,
        maxAge = c.maxAge.map(_.toInt),
        path = c.path.getOrElse("/"),
        domain = c.domain,
        secure = c.secure,
        httpOnly = c.httpOnly,
        sameSite = None
      )
    }
    def json: JsValue     = JsonHelpers.wsCookieToJson(c)
    // def json: JsValue = Json.obj(
    //   "name" -> cookie.name,
    //   "value" -> cookie.value,
    //   "maxAge" -> cookie.maxAge.map(n => JsNumber(BigDecimal(n))).getOrElse(JsNull).asValue,
    //   "path" -> cookie.path.map(JsString.apply).getOrElse(JsNull).asValue,
    //   "domain" -> cookie.domain.map(JsString.apply).getOrElse(JsNull).asValue,
    //   "secure" -> cookie.secure,
    //   "httpOnly" -> cookie.httpOnly
    // )
  }
}
