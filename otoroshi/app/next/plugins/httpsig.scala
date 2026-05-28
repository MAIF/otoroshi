package otoroshi.next.plugins

import akka.http.scaladsl.model.ContentType
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.nimbusds.jose.jwk.{ECKey, JWK, OctetKeyPair, RSAKey}
import otoroshi.env.Env
import otoroshi.next.plugins.api._
import otoroshi.utils.http.RequestImplicits._
import otoroshi.utils.syntax.implicits._
import play.api.Logger
import play.api.libs.json._
import play.api.mvc.{Result, Results}

import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.spec.{MGF1ParameterSpec, PSSParameterSpec}
import java.security.{KeyFactory, MessageDigest, PrivateKey, PublicKey, Signature => JSignature}
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

// =====================================================================================================================
//
//  HTTP Message Signatures (RFC 9421) — built-in plugins for Otoroshi.
//
//  Two plugins are defined here, both visible to operators:
//
//    HttpSignatureVerifyRequest    — NgAccessValidator (+ NgRequestTransformer for body-digest verification).
//                                    Verifies incoming request signatures before forwarding to the backend.
//
//    HttpSignatureSignResponse     — NgRequestTransformer.transformResponse.
//                                    Signs the response Otoroshi returns to the client.
//
//  The objects HttpSig{StructuredFields, Components, Base, Algorithms} below contain the pure RFC 9421 logic —
//  no Env, no Future, no plugin context. They are unit-testable in isolation.
//
// =====================================================================================================================

// ---------------------------------------------------------------------------------------------------------------------
// Structured Fields (RFC 8941) — minimal parser/serializer for the shapes RFC 9421 actually uses
// ---------------------------------------------------------------------------------------------------------------------

object HttpSigStructuredFields {

  // A signature parameter value. We only need the bare-item subset RFC 9421 uses: string, integer, byte-sequence.
  sealed trait Param
  final case class ParamString(value: String)     extends Param
  final case class ParamInt(value: Long)          extends Param
  final case class ParamBytes(value: Array[Byte]) extends Param
  final case class ParamBool(value: Boolean)      extends Param
  final case class ParamToken(value: String)      extends Param

  // A component identifier as it appears inside a Signature-Input inner list: a string with parameters.
  // e.g. `"@query-param";name="foo"` => ComponentId("@query-param", List("name" -> ParamString("foo")))
  // Per RFC 9421 §2.1 the component name is always lowercase. We normalize at construction time so the
  // serialized form is canonical regardless of what the operator typed in their config.
  final case class ComponentId(rawName: String, params: List[(String, Param)]) {
    val name: String                             = rawName.toLowerCase
    def serialize: String                        = "\"" + name + "\"" + serializeParams(params)
    def paramString(key: String): Option[String] = params.collectFirst { case (`key`, ParamString(v)) => v }
    def isReq: Boolean                           = params.exists(_._1 == "req")
    def isSf: Boolean                            = params.exists(_._1 == "sf")
    def isBs: Boolean                            = params.exists(_._1 == "bs")
    def isTr: Boolean                            = params.exists(_._1 == "tr")
  }

  // The inner list of components plus the trailing signature parameters.
  final case class SignatureInputValue(components: List[ComponentId], params: List[(String, Param)]) {
    def serialize: String     = "(" + components.map(_.serialize).mkString(" ") + ")" + serializeParams(params)
    def created: Option[Long] = params.collectFirst { case ("created", ParamInt(v)) => v }
    def expires: Option[Long] = params.collectFirst { case ("expires", ParamInt(v)) => v }
    def keyid: Option[String] = params.collectFirst { case ("keyid", ParamString(v)) => v }
    def alg: Option[String]   = params.collectFirst { case ("alg", ParamString(v)) => v }
    def nonce: Option[String] = params.collectFirst { case ("nonce", ParamString(v)) => v }
    def tag: Option[String]   = params.collectFirst { case ("tag", ParamString(v)) => v }
  }

  // Parse a dictionary header value where each entry maps a label (e.g. "sig1") to an inner-list-with-params.
  // Used for Signature-Input.
  def parseSignatureInputDict(raw: String): Either[String, List[(String, SignatureInputValue)]] = {
    val p = new Parser(raw)
    Try {
      p.skipSpaces()
      val out = collection.mutable.ListBuffer.empty[(String, SignatureInputValue)]
      while (!p.eof) {
        val key  = p.readKey()
        p.expect('=')
        val list = p.readInnerListWithParams()
        out += (key -> list)
        p.skipSpaces()
        if (!p.eof) {
          p.expect(',')
          p.skipSpaces()
        }
      }
      out.toList
    }.toEither.left.map(_.getMessage)
  }

  // Parse a dictionary of byte-sequences, used for the Signature header.
  def parseSignatureDict(raw: String): Either[String, List[(String, Array[Byte])]] = {
    val p = new Parser(raw)
    Try {
      p.skipSpaces()
      val out = collection.mutable.ListBuffer.empty[(String, Array[Byte])]
      while (!p.eof) {
        val key = p.readKey()
        p.expect('=')
        val bs  = p.readByteSequence()
        out += (key -> bs)
        p.skipParams() // ignore params on signature entries
        p.skipSpaces()
        if (!p.eof) {
          p.expect(',')
          p.skipSpaces()
        }
      }
      out.toList
    }.toEither.left.map(_.getMessage)
  }

  // Parse an operator-supplied component string like `@status`, `content-type`, `@query-param;name="foo"`, or
  // `content-digest;sf`. Quotes around the name are optional. Always returns a ComponentId; falls back to a
  // name-only component when params cannot be parsed.
  def parseComponentString(raw: String): ComponentId = {
    val s = raw.trim
    if (s.isEmpty) ComponentId("", Nil)
    else {
      val (name, rest)                  = if (s.startsWith("\"")) {
        // quoted form
        val end = s.indexOf('"', 1)
        if (end < 0) (s, "") else (s.substring(1, end), s.substring(end + 1))
      } else {
        // bare form — name is up to the first `;`
        val semi = s.indexOf(';')
        if (semi < 0) (s, "") else (s.substring(0, semi), s.substring(semi))
      }
      val params: List[(String, Param)] =
        if (rest.isEmpty || !rest.startsWith(";")) Nil
        else {
          val p = new Parser(rest)
          Try(p.readParams()).getOrElse(Nil)
        }
      ComponentId(name, params)
    }
  }

  def serializeSignatureInputDict(entries: List[(String, SignatureInputValue)]): String =
    entries.map { case (k, v) => s"$k=${v.serialize}" }.mkString(", ")

  def serializeSignatureDict(entries: List[(String, Array[Byte])]): String              =
    entries.map { case (k, v) => s"$k=:${Base64.getEncoder.encodeToString(v)}:" }.mkString(", ")

  def serializeParams(params: List[(String, Param)]): String                            = params
    .map {
      case (k, ParamString(v)) => ";" + k + "=\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
      case (k, ParamInt(v))    => ";" + k + "=" + v.toString
      case (k, ParamBytes(v))  => ";" + k + "=:" + Base64.getEncoder.encodeToString(v) + ":"
      case (k, ParamBool(v))   => ";" + k + (if (v) "" else "=?0") // sf-boolean serializes true as bare flag
      case (k, ParamToken(v))  => ";" + k + "=" + v
    }
    .mkString("")

  // Internal recursive-descent parser. Inputs are short so no streaming is needed.
  private final class Parser(input: String) {
    private var i: Int   = 0
    private val len: Int = input.length

    def eof: Boolean = i >= len
    def peek: Char   = input.charAt(i)

    def skipSpaces(): Unit = while (i < len && (input.charAt(i) == ' ' || input.charAt(i) == '\t')) i += 1

    def expect(c: Char): Unit = {
      skipSpaces()
      if (eof || input.charAt(i) != c) throw new IllegalArgumentException(s"expected '$c' at offset $i in '$input'")
      i += 1
    }

    def readKey(): String = {
      skipSpaces()
      val start = i
      while (i < len && isKeyChar(input.charAt(i))) i += 1
      if (start == i) throw new IllegalArgumentException(s"expected key at offset $i in '$input'")
      input.substring(start, i)
    }

    def readInnerListWithParams(): SignatureInputValue = {
      skipSpaces()
      expect('(')
      val comps  = collection.mutable.ListBuffer.empty[ComponentId]
      skipSpaces()
      while (!eof && peek != ')') {
        comps += readComponentId()
        skipSpaces()
      }
      expect(')')
      val params = readParams()
      SignatureInputValue(comps.toList, params)
    }

    def readComponentId(): ComponentId = {
      skipSpaces()
      val name   = readString()
      val params = readParams()
      ComponentId(name, params)
    }

    def readParams(): List[(String, Param)] = {
      val out = collection.mutable.ListBuffer.empty[(String, Param)]
      while (i < len && input.charAt(i) == ';') {
        i += 1
        val k = readKey()
        if (i < len && input.charAt(i) == '=') {
          i += 1
          out += (k -> readBareItem())
        } else {
          // bare flag => boolean true
          out += (k -> ParamBool(true))
        }
      }
      out.toList
    }

    def skipParams(): Unit = {
      while (i < len && input.charAt(i) == ';') {
        i += 1
        readKey()
        if (i < len && input.charAt(i) == '=') {
          i += 1
          val _ = readBareItem()
        }
      }
    }

    def readBareItem(): Param = {
      if (eof) throw new IllegalArgumentException("unexpected EOF reading bare-item")
      val c = input.charAt(i)
      if (c == '"') ParamString(readString())
      else if (c == ':') ParamBytes(readByteSequence())
      else if (c == '?') {
        i += 1
        if (eof) throw new IllegalArgumentException("unexpected EOF reading boolean")
        val b = input.charAt(i)
        i += 1
        if (b == '0') ParamBool(false)
        else if (b == '1') ParamBool(true)
        else throw new IllegalArgumentException(s"invalid boolean ?$b")
      } else if (c == '-' || c.isDigit) {
        val start = i
        if (c == '-') i += 1
        while (i < len && input.charAt(i).isDigit) i += 1
        ParamInt(input.substring(start, i).toLong)
      } else {
        // sf-token (used for alg in some encodings; we treat as string)
        val start = i
        while (i < len && !",;) ".contains(input.charAt(i))) i += 1
        ParamToken(input.substring(start, i))
      }
    }

    def readString(): String = {
      skipSpaces()
      expect('"')
      val sb = new StringBuilder
      while (i < len && input.charAt(i) != '"') {
        val c = input.charAt(i)
        if (c == '\\' && i + 1 < len) {
          val n = input.charAt(i + 1)
          sb.append(n)
          i += 2
        } else {
          sb.append(c)
          i += 1
        }
      }
      expect('"')
      sb.toString()
    }

    def readByteSequence(): Array[Byte] = {
      skipSpaces()
      expect(':')
      val start = i
      while (i < len && input.charAt(i) != ':') i += 1
      val s     = input.substring(start, i)
      expect(':')
      Base64.getDecoder.decode(s)
    }

    private def isKeyChar(c: Char): Boolean =
      (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.' || c == '*'
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Message abstraction — let the signature base algorithm work on either a request or a response.
// We keep it as a plain trait so the same code paths apply to outgoing responses or incoming requests.
// ---------------------------------------------------------------------------------------------------------------------

trait HttpSigMessage {
  def method: String                 // upper-case verb, "GET" etc.
  def fullUri: String                // scheme://authority/path?query
  def headers: Seq[(String, String)] // ordered, original case preserved for storage; lookups are case-insensitive
  def status: Option[Int]            // Some(code) on responses
  def header(name: String): Option[String] = {
    val lc      = name.toLowerCase
    val matches = headers.filter(_._1.toLowerCase == lc).map(_._2)
    if (matches.isEmpty) None else Some(matches.map(_.trim).mkString(", "))
  }
}

final case class SimpleSigMessage(
    method: String,
    fullUri: String,
    headers: Seq[(String, String)],
    status: Option[Int]
) extends HttpSigMessage

object HttpSigMessage {
  // Build a signing-base message from the inbound RequestHeader. Used both by the verify-request plugin (the inbound
  // request itself) and by the sign-response plugin (when a covered component carries `;req` and needs to reference
  // the originating request).
  def fromRequest(req: play.api.mvc.RequestHeader)(implicit env: Env): SimpleSigMessage = SimpleSigMessage(
    method = req.method,
    fullUri = req.theProtocol + "://" + req.theHost + req.relativeUri,
    headers = req.headers.toMap.toSeq.flatMap { case (k, vs) => vs.map(v => (k, v)) },
    status = None
  )
}

// ---------------------------------------------------------------------------------------------------------------------
// Signature base construction (RFC 9421 §2.5)
// ---------------------------------------------------------------------------------------------------------------------

object HttpSigBase {

  import HttpSigStructuredFields._

  // Build the canonical signature base. `relatedRequest` is consulted when a component carries the ;req parameter,
  // which is used for response signatures that cover request components.
  def build(
      msg: HttpSigMessage,
      input: SignatureInputValue,
      relatedRequest: Option[HttpSigMessage]
  ): Either[String, String] = {
    val sb      = new StringBuilder
    val seenIds = collection.mutable.Set.empty[String]

    val it = input.components.iterator
    while (it.hasNext) {
      val cid         = it.next()
      val canonicalId = cid.serialize
      if (!seenIds.add(canonicalId)) return Left(s"duplicate component identifier: $canonicalId")
      componentValue(msg, cid, relatedRequest) match {
        case Left(err) => return Left(err)
        case Right(v)  =>
          sb.append(canonicalId).append(": ").append(v).append('\n')
      }
    }
    sb.append("\"@signature-params\": ")
      .append("(")
      .append(input.components.map(_.serialize).mkString(" "))
      .append(")")
    sb.append(HttpSigStructuredFields.serializeParams(input.params))
    Right(sb.toString())
  }

  private def componentValue(
      msg: HttpSigMessage,
      cid: ComponentId,
      related: Option[HttpSigMessage]
  ): Either[String, String] = {
    val target = if (cid.isReq) {
      related match {
        case Some(r) => r
        case None    => return Left(s"component ${cid.serialize} is marked ;req but no related request is available")
      }
    } else msg

    cid.name match {
      case "@method"         => Right(target.method.toUpperCase)
      case "@target-uri"     => Right(target.fullUri)
      case "@authority"      =>
        Try(new URI(target.fullUri)).toOption.flatMap { uri =>
          Option(uri.getHost).map { host =>
            val port        = uri.getPort
            val scheme      = Option(uri.getScheme).getOrElse("").toLowerCase
            val defaultPort = (scheme == "https" && port == 443) || (scheme == "http" && port == 80)
            if (port < 0 || defaultPort) host.toLowerCase
            else s"${host.toLowerCase}:$port"
          }
        } match {
          case Some(v) => Right(v)
          case None    => Left("@authority cannot be derived from target URI")
        }
      case "@scheme"         =>
        Try(new URI(target.fullUri)).toOption.flatMap(u => Option(u.getScheme)).map(_.toLowerCase) match {
          case Some(v) => Right(v)
          case None    => Left("@scheme cannot be derived")
        }
      case "@request-target" =>
        Try(new URI(target.fullUri)).toOption.map { u =>
          val p = Option(u.getRawPath).filter(_.nonEmpty).getOrElse("/")
          val q = Option(u.getRawQuery).map("?" + _).getOrElse("")
          p + q
        } match {
          case Some(v) => Right(v)
          case None    => Left("@request-target cannot be derived")
        }
      case "@path"           =>
        Try(new URI(target.fullUri)).toOption.map { u =>
          Option(u.getRawPath).filter(_.nonEmpty).getOrElse("/")
        } match {
          case Some(v) => Right(v)
          case None    => Left("@path cannot be derived")
        }
      case "@query"          =>
        Try(new URI(target.fullUri)).toOption.map { u =>
          Option(u.getRawQuery).map("?" + _).getOrElse("?")
        } match {
          case Some(v) => Right(v)
          case None    => Left("@query cannot be derived")
        }
      case "@query-param"    =>
        cid.paramString("name") match {
          case None       => Left("@query-param requires a ;name parameter")
          case Some(name) =>
            Try(new URI(target.fullUri)).toOption.flatMap(u => Option(u.getRawQuery)) match {
              case None     => Left(s"@query-param;name=$name: no query string")
              case Some(qs) =>
                val pairs = parseQuery(qs)
                pairs.find(_._1 == name) match {
                  case None         => Left(s"@query-param;name=$name: not present")
                  case Some((_, v)) => Right(v)
                }
            }
        }
      case "@status"         =>
        target.status match {
          case Some(s) => Right(s.toString)
          case None    => Left("@status component referenced on a message without a status code")
        }
      case header            =>
        if (header.startsWith("@")) {
          Left(s"unknown derived component: $header")
        } else {
          // RFC 9421 §2.1: header field name is lowercase. We already lowercased via canonicalId,
          // but the cid.name might mixed-case from the operator config — be liberal in what we accept.
          val name = header.toLowerCase
          target.header(name) match {
            case None      => Left(s"missing header for signed component: $name")
            case Some(raw) =>
              if (cid.isBs) {
                // bytewise wrap — base64 of the raw header value
                Right(":" + Base64.getEncoder.encodeToString(raw.getBytes(StandardCharsets.UTF_8)) + ":")
              } else if (cid.isSf) {
                // We pass the value through unchanged. Strict re-serialization would require a full SF parser per
                // field type; the safe default (used by most implementations) is to require the operator to ensure
                // the value is already canonical. We documented this caveat in the plugin description.
                Right(raw)
              } else {
                Right(raw)
              }
          }
        }
    }
  }

  private def parseQuery(raw: String): Seq[(String, String)] = {
    raw.split('&').toSeq.filter(_.nonEmpty).map { pair =>
      val idx = pair.indexOf('=')
      if (idx < 0) (urlDecode(pair), "")
      else (urlDecode(pair.substring(0, idx)), urlDecode(pair.substring(idx + 1)))
    }
  }

  private def urlDecode(s: String): String =
    java.net.URLDecoder.decode(s, StandardCharsets.UTF_8)
}

// ---------------------------------------------------------------------------------------------------------------------
// Algorithms (RFC 9421 §3.3)
// ---------------------------------------------------------------------------------------------------------------------

object HttpSigAlgorithms {

  // Algorithm names exactly as they appear in the `alg` parameter and what they map to.
  val Hmac256       = "hmac-sha256"
  val RsaPssSha512  = "rsa-pss-sha512"
  val RsaV1_5Sha256 = "rsa-v1_5-sha256"
  val EcdsaP256     = "ecdsa-p256-sha256"
  val EcdsaP384     = "ecdsa-p384-sha384"
  val Ed25519Alg    = "ed25519"

  val all: Seq[String] = Seq(Hmac256, RsaPssSha512, RsaV1_5Sha256, EcdsaP256, EcdsaP384, Ed25519Alg)

  def isHmac(alg: String): Boolean = alg == Hmac256

  // Sign `base` bytes with the algorithm and key material. HMAC keys are raw bytes; asymmetric keys are PrivateKey.
  def sign(alg: String, base: Array[Byte], key: Either[Array[Byte], PrivateKey]): Either[String, Array[Byte]] = {
    Try {
      (alg, key) match {
        case (Hmac256, Left(secret))      =>
          val mac = Mac.getInstance("HmacSHA256")
          mac.init(new SecretKeySpec(secret, "HmacSHA256"))
          mac.doFinal(base)
        case (RsaPssSha512, Right(priv))  =>
          val s = JSignature.getInstance("RSASSA-PSS")
          s.setParameter(new PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1))
          s.initSign(priv)
          s.update(base)
          s.sign()
        case (RsaV1_5Sha256, Right(priv)) =>
          val s = JSignature.getInstance("SHA256withRSA")
          s.initSign(priv)
          s.update(base)
          s.sign()
        case (EcdsaP256, Right(priv))     =>
          val s = JSignature.getInstance("SHA256withECDSAinP1363Format")
          s.initSign(priv)
          s.update(base)
          s.sign()
        case (EcdsaP384, Right(priv))     =>
          val s = JSignature.getInstance("SHA384withECDSAinP1363Format")
          s.initSign(priv)
          s.update(base)
          s.sign()
        case (Ed25519Alg, Right(priv))    =>
          val s = JSignature.getInstance("Ed25519")
          s.initSign(priv)
          s.update(base)
          s.sign()
        case (other, _)                   =>
          throw new IllegalArgumentException(s"unsupported algorithm or key shape: $other")
      }
    }.toEither.left.map(_.getMessage)
  }

  def verify(
      alg: String,
      base: Array[Byte],
      sig: Array[Byte],
      key: Either[Array[Byte], PublicKey]
  ): Either[String, Unit] = {
    Try {
      val ok = (alg, key) match {
        case (Hmac256, Left(secret))     =>
          val mac      = Mac.getInstance("HmacSHA256")
          mac.init(new SecretKeySpec(secret, "HmacSHA256"))
          val computed = mac.doFinal(base)
          MessageDigest.isEqual(computed, sig)
        case (RsaPssSha512, Right(pub))  =>
          val s = JSignature.getInstance("RSASSA-PSS")
          s.setParameter(new PSSParameterSpec("SHA-512", "MGF1", MGF1ParameterSpec.SHA512, 64, 1))
          s.initVerify(pub)
          s.update(base)
          s.verify(sig)
        case (RsaV1_5Sha256, Right(pub)) =>
          val s = JSignature.getInstance("SHA256withRSA")
          s.initVerify(pub)
          s.update(base)
          s.verify(sig)
        case (EcdsaP256, Right(pub))     =>
          val s = JSignature.getInstance("SHA256withECDSAinP1363Format")
          s.initVerify(pub)
          s.update(base)
          s.verify(sig)
        case (EcdsaP384, Right(pub))     =>
          val s = JSignature.getInstance("SHA384withECDSAinP1363Format")
          s.initVerify(pub)
          s.update(base)
          s.verify(sig)
        case (Ed25519Alg, Right(pub))    =>
          val s = JSignature.getInstance("Ed25519")
          s.initVerify(pub)
          s.update(base)
          s.verify(sig)
        case (other, _)                  =>
          throw new IllegalArgumentException(s"unsupported algorithm or key shape: $other")
      }
      if (!ok) throw new IllegalStateException("signature mismatch")
    }.toEither.left.map(_.getMessage)
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Content-Digest (RFC 9530) — SHA-256/SHA-512 over the body, wrapped as a structured-field dictionary.
// ---------------------------------------------------------------------------------------------------------------------

object HttpSigContentDigest {

  private val SupportedAlgs = Set("sha-256", "sha-512")

  // Compute a Content-Digest header value covering one algorithm. Returns e.g. `sha-256=:abc...:`.
  def compute(alg: String, body: Array[Byte]): Either[String, String] = {
    val normalized = alg.toLowerCase
    if (!SupportedAlgs.contains(normalized)) Left(s"unsupported content-digest algorithm: $alg")
    else {
      val md  = MessageDigest.getInstance(if (normalized == "sha-256") "SHA-256" else "SHA-512")
      val out = md.digest(body)
      Right(s"$normalized=:${Base64.getEncoder.encodeToString(out)}:")
    }
  }

  // Verify that ALL entries in a Content-Digest header value match the given body bytes.
  // Returns the list of algorithms successfully checked (so callers can require at least one strong digest).
  def verify(headerValue: String, body: Array[Byte]): Either[String, Seq[String]] = {
    HttpSigStructuredFields.parseSignatureDict(headerValue).flatMap { entries =>
      val checked = collection.mutable.ListBuffer.empty[String]
      val it      = entries.iterator
      while (it.hasNext) {
        val (alg, bs)  = it.next()
        val normalized = alg.toLowerCase
        if (!SupportedAlgs.contains(normalized)) return Left(s"unsupported content-digest algorithm: $alg")
        val md         = MessageDigest.getInstance(if (normalized == "sha-256") "SHA-256" else "SHA-512")
        val computed   = md.digest(body)
        if (!MessageDigest.isEqual(computed, bs)) return Left(s"content-digest mismatch for $alg")
        checked += normalized
      }
      Right(checked.toList)
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Key sources
// ---------------------------------------------------------------------------------------------------------------------

sealed trait HttpSigKeySource {
  def kind: String
  def keyid: Option[String]
  // The algorithm bound to this key. Verifiers prefer this over the `alg` parameter in the signature (which can
  // be spoofed); when both are present the parameter must match this value.
  def alg: Option[String]
  def json: JsValue
}
final case class HttpSigKeyCertRef(certId: String, keyid: Option[String], alg: Option[String])
    extends HttpSigKeySource  {
  def kind = "cert"
  def json = Json.obj("kind" -> kind, "cert_id" -> certId, "keyid" -> keyid, "alg" -> alg)
}
final case class HttpSigKeyInline(secretOrPem: String, keyid: Option[String], alg: Option[String])
    extends HttpSigKeySource  {
  def kind = "inline"
  def json = Json.obj("kind" -> kind, "secret_or_pem" -> secretOrPem, "keyid" -> keyid, "alg" -> alg)
}
final case class HttpSigKeyJwks(url: String, keyid: Option[String], alg: Option[String]) extends HttpSigKeySource {
  def kind = "jwks"
  def json = Json.obj("kind" -> kind, "url" -> url, "keyid" -> keyid, "alg" -> alg)
}

object HttpSigKeySource {
  def read(json: JsValue): JsResult[HttpSigKeySource] = Try {
    val keyid = (json \ "keyid").asOpt[String].filter(_.nonEmpty)
    val alg   = (json \ "alg").asOpt[String].filter(_.nonEmpty).map(_.toLowerCase)
    (json \ "kind").asOpt[String].getOrElse("inline") match {
      case "cert" => HttpSigKeyCertRef((json \ "cert_id").as[String], keyid, alg)
      case "jwks" => HttpSigKeyJwks((json \ "url").as[String], keyid, alg)
      case _      => HttpSigKeyInline((json \ "secret_or_pem").asOpt[String].getOrElse(""), keyid, alg)
    }
  } match {
    case Success(v) => JsSuccess(v)
    case Failure(e) => JsError(e.getMessage)
  }
}

object HttpSigKeyResolver {

  private val logger = Logger("otoroshi-plugins-httpsig-keys")

  private val jwksCache               = new java.util.concurrent.ConcurrentHashMap[String, (Long, Map[String, JWK])]()
  private val jwksTtl: FiniteDuration = 10.minutes

  // Public key lookup for verification.
  def publicKey(source: HttpSigKeySource, alg: String, kid: Option[String])(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[String, Either[Array[Byte], PublicKey]]] = {
    source match {
      case HttpSigKeyCertRef(certId, _, _)     =>
        env.proxyState.certificate(certId) match {
          case None       => Future.successful(Left(s"unknown cert id '$certId'"))
          case Some(cert) =>
            if (HttpSigAlgorithms.isHmac(alg))
              Future.successful(Left("HMAC requires an inline secret, not a certificate"))
            else
              Try(cert.cryptoKeyPair.getPublic) match {
                case Failure(e) => Future.successful(Left(s"cannot read public key from cert $certId: ${e.getMessage}"))
                case Success(p) => Future.successful(Right(Right(p)))
              }
        }
      case HttpSigKeyInline(secretOrPem, _, _) =>
        if (HttpSigAlgorithms.isHmac(alg)) {
          Future.successful(Right(Left(decodeSecret(secretOrPem))))
        } else {
          parsePemPublic(secretOrPem) match {
            case Left(err)  => Future.successful(Left(err))
            case Right(pub) => Future.successful(Right(Right(pub)))
          }
        }
      case HttpSigKeyJwks(url, _, _)           =>
        if (HttpSigAlgorithms.isHmac(alg)) Future.successful(Left("HMAC keys cannot be served via JWKS"))
        else
          fetchJwks(url).map {
            case Left(err)   => Left(err)
            case Right(keys) =>
              kid match {
                case None      => Left("JWKS lookup requires a keyid")
                case Some(kid) =>
                  keys.get(kid) match {
                    case None      => Left(s"no JWK with kid '$kid' at $url")
                    case Some(jwk) =>
                      jwkToPublicKey(jwk, alg) match {
                        case Left(err)  => Left(err)
                        case Right(pub) => Right(Right(pub))
                      }
                  }
              }
          }
    }
  }

  // Private key lookup for signing.
  def privateKey(source: HttpSigKeySource, alg: String)(implicit
      env: Env,
      ec: ExecutionContext
  ): Future[Either[String, Either[Array[Byte], PrivateKey]]] = {
    source match {
      case HttpSigKeyCertRef(certId, _, _)     =>
        env.proxyState.certificate(certId) match {
          case None       => Future.successful(Left(s"unknown cert id '$certId'"))
          case Some(cert) =>
            if (HttpSigAlgorithms.isHmac(alg))
              Future.successful(Left("HMAC requires an inline secret, not a certificate"))
            else
              Try(cert.cryptoKeyPair.getPrivate) match {
                case Failure(e) =>
                  Future.successful(Left(s"cannot read private key from cert $certId: ${e.getMessage}"))
                case Success(p) => Future.successful(Right(Right(p)))
              }
        }
      case HttpSigKeyInline(secretOrPem, _, _) =>
        if (HttpSigAlgorithms.isHmac(alg)) Future.successful(Right(Left(decodeSecret(secretOrPem))))
        else
          parsePemPrivate(secretOrPem) match {
            case Left(err)   => Future.successful(Left(err))
            case Right(priv) => Future.successful(Right(Right(priv)))
          }
      case _: HttpSigKeyJwks                   =>
        Future.successful(Left("JWKS sources are read-only and cannot be used for signing"))
    }
  }

  // Decode an inline HMAC secret. Prefixes let the operator be explicit about encoding; without a prefix the
  // raw UTF-8 bytes are used. Exposed (non-private) so unit tests can exercise every prefix variant directly.
  def decodeSecret(s: String): Array[Byte] = {
    if (s.startsWith("base64:")) Base64.getDecoder.decode(s.drop(7))
    else if (s.startsWith("base64url:")) Base64.getUrlDecoder.decode(s.drop(10))
    else if (s.startsWith("hex:")) hexDecode(s.drop(4))
    else s.getBytes(StandardCharsets.UTF_8)
  }

  private def hexDecode(s: String): Array[Byte] = {
    val clean = s.filter(c => !c.isWhitespace)
    if (clean.length % 2 != 0) throw new IllegalArgumentException("hex string must have even length")
    val out = new Array[Byte](clean.length / 2)
    var i   = 0
    while (i < out.length) {
      out(i) = Integer.parseInt(clean.substring(i * 2, i * 2 + 2), 16).toByte
      i += 1
    }
    out
  }

  private def parsePemPublic(pem: String): Either[String, PublicKey] = Try {
    val cleaned = stripPem(pem)
    val der     = Base64.getDecoder.decode(cleaned)
    val keySpec = new java.security.spec.X509EncodedKeySpec(der)
    // Try each algorithm in order — the X509EncodedKeySpec carries the AlgorithmIdentifier so KeyFactory.getInstance
    // picks the right curve automatically for ECDSA, but RSA/RSASSA-PSS/Ed25519 each need their own factory class.
    Iterator("RSA", "EC", "Ed25519", "RSASSA-PSS")
      .map(a => Try(KeyFactory.getInstance(a).generatePublic(keySpec)))
      .collectFirst { case Success(k) =>
        k
      }
      .getOrElse(throw new IllegalArgumentException("cannot parse PEM as a known public key"))
  }.toEither.left.map(e => s"invalid PEM public key: ${e.getMessage}")

  private def parsePemPrivate(pem: String): Either[String, PrivateKey] = Try {
    val cleaned = stripPem(pem)
    val der     = Base64.getDecoder.decode(cleaned)
    val keySpec = new java.security.spec.PKCS8EncodedKeySpec(der)
    Iterator("RSA", "EC", "Ed25519", "RSASSA-PSS")
      .map(a => Try(KeyFactory.getInstance(a).generatePrivate(keySpec)))
      .collectFirst { case Success(k) =>
        k
      }
      .getOrElse(throw new IllegalArgumentException("cannot parse PEM as a known private key"))
  }.toEither.left.map(e => s"invalid PEM private key: ${e.getMessage}")

  private def stripPem(pem: String): String =
    pem
      .replaceAll("-----BEGIN [^-]+-----", "")
      .replaceAll("-----END [^-]+-----", "")
      .replaceAll("\\s", "")

  private def fetchJwks(
      url: String
  )(implicit env: Env, ec: ExecutionContext): Future[Either[String, Map[String, JWK]]] = {
    val now = System.currentTimeMillis()
    Option(jwksCache.get(url)) match {
      case Some((stop, keys)) if stop > now => Future.successful(Right(keys))
      case _                                =>
        env.Ws
          .url(url)
          .withRequestTimeout(10.seconds)
          .get()
          .map { resp =>
            if (resp.status != 200) Left(s"JWKS fetch at $url failed: ${resp.status}")
            else
              Try {
                val obj  = Json.parse(resp.body).as[JsObject]
                val arr  = (obj \ "keys").as[JsArray].value.toList
                val keys = arr.flatMap { k =>
                  val jwk = JWK.parse(Json.stringify(k))
                  Option(jwk.getKeyID).map(kid => kid -> jwk)
                }.toMap
                jwksCache.put(url, (now + jwksTtl.toMillis, keys))
                Right(keys): Either[String, Map[String, JWK]]
              }.recover { case e =>
                logger.warn(s"failed to parse JWKS from $url: ${e.getMessage}")
                Left(s"failed to parse JWKS from $url: ${e.getMessage}"): Either[String, Map[String, JWK]]
              }.get
          }
          .recover { case e =>
            Left(s"failed to fetch JWKS at $url: ${e.getMessage}"): Either[String, Map[String, JWK]]
          }
    }
  }

  private def jwkToPublicKey(jwk: JWK, alg: String): Either[String, PublicKey] = {
    Try {
      jwk match {
        case rsa: RSAKey       => rsa.toRSAPublicKey
        case ec: ECKey         => ec.toECPublicKey
        case okp: OctetKeyPair => okp.toPublicKey
        case _                 => throw new IllegalArgumentException(s"unsupported JWK type for alg $alg")
      }
    }.toEither.left.map(e => s"cannot extract public key from JWK: ${e.getMessage}")
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Configuration objects shared by both plugins
// ---------------------------------------------------------------------------------------------------------------------

object HttpSigConfigJson {
  def keysReader(arr: JsValue): List[HttpSigKeySource]  = arr match {
    case JsArray(items) => items.toList.flatMap(it => HttpSigKeySource.read(it).asOpt)
    case _              => List.empty
  }
  def keysWriter(keys: List[HttpSigKeySource]): JsValue = JsArray(keys.map(_.json))
}

// ---------------------------------------------------------------------------------------------------------------------
// Plugin 1: HttpSignatureVerifyRequest
// ---------------------------------------------------------------------------------------------------------------------

// Note: the Scala field is `label` but it is serialized as `signature_label` in JSON. The `label` JSON key collides
// with the admin UI's label-display walker (see SKILL.md §4 Gotchas), so we use a qualified key.
case class HttpSignatureVerifyRequestConfig(
    keys: List[HttpSigKeySource] = Nil,
    requiredComponents: Seq[String] = Seq("@method", "@target-uri"),
    allowedAlgorithms: Seq[String] = HttpSigAlgorithms.all,
    requireKeyid: Boolean = true,
    maxAgeSeconds: Option[Long] = Some(300L),
    clockSkewSeconds: Long = 30L,
    label: Option[String] = None,
    // Default false so adding the plugin to a route doesn't immediately 401 everything before operators wire keys.
    // Once keys are configured, flip this to true to enforce signatures.
    mandatory: Boolean = false
) extends NgPluginConfig {
  def json: JsValue = HttpSignatureVerifyRequestConfig.format.writes(this)
}

object HttpSignatureVerifyRequestConfig {
  val configFlow: Seq[String]        = Seq(
    "mandatory",
    "keys",
    "required_components",
    "allowed_algorithms",
    "require_keyid",
    "max_age_seconds",
    "clock_skew_seconds",
    "signature_label"
  )
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "mandatory"           -> Json.obj(
        "type"  -> "bool",
        "label" -> "Mandatory",
        "help"  -> "Reject requests without a signature when true. Invalid signatures are always rejected."
      ),
      "keys"                -> Json.obj(
        "type"  -> "any",
        "label" -> "Verification keys",
        "help"  -> "List of accepted keys: { kind: 'cert' | 'inline' | 'jwks', cert_id | secret_or_pem | url, keyid, alg }.",
        "props" -> Json.obj("height" -> "240px", "language" -> "json")
      ),
      "allowed_algorithms"  -> Json.obj(
        "type"  -> "select",
        "array" -> true,
        "label" -> "Allowed algorithms",
        "help"  -> "Reject signatures whose algorithm is not in this list.",
        "props" -> Json.obj("options" -> JsArray(HttpSigAlgorithms.all.map(JsString)))
      ),
      "required_components" -> Json.obj(
        "type"  -> "array",
        "label" -> "Required components",
        "help"  -> "Components the signature MUST cover. When `content-digest` is required, the body is hashed and checked."
      ),
      "require_keyid"       -> Json.obj(
        "type"  -> "bool",
        "label" -> "Require keyid",
        "help"  -> "Reject signatures without a keyid parameter."
      ),
      "max_age_seconds"     -> Json.obj(
        "type"  -> "number",
        "label" -> "Max signature age",
        "help"  -> "In seconds. Reject signatures whose `created` is older than this. Null disables the check."
      ),
      "clock_skew_seconds"  -> Json.obj(
        "type"  -> "number",
        "label" -> "Clock skew tolerance",
        "help"  -> "In seconds. Tolerance applied to created/expires checks."
      ),
      "signature_label"     -> Json.obj(
        "type"  -> "string",
        "label" -> "Signature label",
        "help"  -> "Restrict to a specific signature label. Empty accepts any valid signature."
      )
    )
  )

  val format: Format[HttpSignatureVerifyRequestConfig] = new Format[HttpSignatureVerifyRequestConfig] {
    override def reads(json: JsValue): JsResult[HttpSignatureVerifyRequestConfig] = Try {
      HttpSignatureVerifyRequestConfig(
        keys = (json \ "keys").asOpt[JsArray].map(HttpSigConfigJson.keysReader).getOrElse(Nil),
        requiredComponents = (json \ "required_components").asOpt[Seq[String]].getOrElse(Seq("@method", "@target-uri")),
        allowedAlgorithms = (json \ "allowed_algorithms").asOpt[Seq[String]].getOrElse(HttpSigAlgorithms.all),
        requireKeyid = (json \ "require_keyid").asOpt[Boolean].getOrElse(true),
        maxAgeSeconds = (json \ "max_age_seconds").asOpt[Long],
        clockSkewSeconds = (json \ "clock_skew_seconds").asOpt[Long].getOrElse(30L),
        label = (json \ "signature_label").asOpt[String].filter(_.nonEmpty),
        mandatory = (json \ "mandatory").asOpt[Boolean].getOrElse(false)
      )
    } match {
      case Success(v) => JsSuccess(v)
      case Failure(e) => JsError(e.getMessage)
    }
    override def writes(o: HttpSignatureVerifyRequestConfig): JsValue             = Json.obj(
      "keys"                -> HttpSigConfigJson.keysWriter(o.keys),
      "required_components" -> o.requiredComponents,
      "allowed_algorithms"  -> o.allowedAlgorithms,
      "require_keyid"       -> o.requireKeyid,
      "max_age_seconds"     -> o.maxAgeSeconds,
      "clock_skew_seconds"  -> o.clockSkewSeconds,
      "signature_label"     -> o.label,
      "mandatory"           -> o.mandatory
    )
  }
}

// We stash the parsed signature input across the access/transform boundary so the transformer can know whether
// content-digest body verification is needed without re-parsing the headers.
object HttpSigAttrs {
  import play.api.libs.typedmap.TypedKey
  val PendingDigestKey: TypedKey[String] = TypedKey[String]("otoroshi.next.plugins.httpsig.pendingDigest")
}

class HttpSignatureVerifyRequest extends NgAccessValidator with NgRequestTransformer {

  private val logger = Logger("otoroshi-plugins-httpsig-verify-request")

  override def steps: Seq[NgStep]                          = Seq(NgStep.ValidateAccess, NgStep.TransformRequest)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.AccessControl)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Verify HTTP Message Signature"
  override def description: Option[String]                 =
    "Verifies the RFC 9421 signature of an incoming request".some
  override def defaultConfigObject: Option[NgPluginConfig] = HttpSignatureVerifyRequestConfig().some
  override def configFlow: Seq[String]                     = HttpSignatureVerifyRequestConfig.configFlow
  override def configSchema: Option[JsObject]              = HttpSignatureVerifyRequestConfig.configSchema
  override def noJsForm: Boolean                           = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = true
  override def transformsResponse: Boolean                 = false
  override def transformsError: Boolean                    = false
  override def isAccessAsync: Boolean                      = true

  private def denyJson(status: Int, error: String): Result    =
    Results.Status(status)(Json.obj("error" -> "invalid_http_signature", "details" -> error))

  private def joinHeader(values: Seq[String]): Option[String] =
    if (values.isEmpty) None else Some(values.mkString(", "))

  override def access(ctx: NgAccessContext)(implicit env: Env, ec: ExecutionContext): Future[NgAccess] = {
    val config =
      ctx
        .cachedConfig(internalName)(HttpSignatureVerifyRequestConfig.format)
        .getOrElse(HttpSignatureVerifyRequestConfig())

    // Per RFC 9421 §4.1 the Signature-Input / Signature headers carry structured-fields dictionary values.
    // Repeated header fields are equivalent to a single header field with values joined by ", ".
    val sigInputHeader = joinHeader(ctx.request.headers.getAll("Signature-Input"))
    val sigHeader      = joinHeader(ctx.request.headers.getAll("Signature"))

    (sigInputHeader, sigHeader) match {
      case (None, _) | (_, None) =>
        if (config.mandatory)
          Future.successful(NgAccess.NgDenied(denyJson(401, "missing Signature-Input or Signature header")))
        else Future.successful(NgAccess.NgAllowed)

      case (Some(sigInputRaw), Some(sigRaw)) =>
        val parsed = for {
          inputs <- HttpSigStructuredFields.parseSignatureInputDict(sigInputRaw)
          sigs   <- HttpSigStructuredFields.parseSignatureDict(sigRaw)
        } yield (inputs, sigs)

        parsed match {
          case Left(err)             => Future.successful(NgAccess.NgDenied(denyJson(400, s"malformed signature headers: $err")))
          case Right((inputs, sigs)) =>
            val sigMap     = sigs.toMap
            val candidates = config.label match {
              case Some(l) => inputs.filter(_._1 == l)
              case None    => inputs
            }
            if (candidates.isEmpty)
              Future.successful(
                NgAccess.NgDenied(denyJson(400, s"no signature with label '${config.label.getOrElse("*")}'"))
              )
            else {
              // Try each candidate sequentially; succeed on first verified.
              val msg = HttpSigMessage.fromRequest(ctx.request)
              tryVerifyOne(msg, candidates, sigMap, config, ctx).map {
                case Right(coveredDigest) =>
                  if (coveredDigest) ctx.attrs.put(HttpSigAttrs.PendingDigestKey -> "yes")
                  NgAccess.NgAllowed
                case Left(err)            =>
                  NgAccess.NgDenied(denyJson(401, err))
              }
            }
        }
    }
  }

  private def tryVerifyOne(
      msg: HttpSigMessage,
      candidates: List[(String, HttpSigStructuredFields.SignatureInputValue)],
      sigMap: Map[String, Array[Byte]],
      config: HttpSignatureVerifyRequestConfig,
      ctx: NgAccessContext
  )(implicit env: Env, ec: ExecutionContext): Future[Either[String, Boolean]] = {
    def loop(
        remaining: List[(String, HttpSigStructuredFields.SignatureInputValue)],
        lastErr: String
    ): Future[Either[String, Boolean]] =
      remaining match {
        case Nil                    => Future.successful(Left(lastErr))
        case (label, input) :: tail =>
          verifyOne(msg, label, input, sigMap, config).flatMap {
            case Right(cov) => Future.successful(Right(cov))
            case Left(err)  =>
              if (logger.isDebugEnabled) logger.debug(s"signature '$label' rejected: $err")
              loop(tail, err)
          }
      }
    loop(candidates, "no candidate signature verified")
  }

  private def verifyOne(
      msg: HttpSigMessage,
      label: String,
      input: HttpSigStructuredFields.SignatureInputValue,
      sigMap: Map[String, Array[Byte]],
      config: HttpSignatureVerifyRequestConfig
  )(implicit env: Env, ec: ExecutionContext): Future[Either[String, Boolean]] = {
    val now = System.currentTimeMillis() / 1000L

    // 1. Required components present?
    val coveredNames = input.components.map(_.name)
    val missing      = config.requiredComponents.map(_.toLowerCase).filterNot(rc => coveredNames.contains(rc))
    if (missing.nonEmpty)
      return Future.successful(
        Left(s"signature '$label' does not cover required components: ${missing.mkString(", ")}")
      )

    // 2. Algorithm declared & allowed.
    val declaredAlg = input.alg.map(_.toLowerCase)
    declaredAlg match {
      case Some(a) if !config.allowedAlgorithms.contains(a) =>
        return Future.successful(Left(s"signature '$label' uses disallowed algorithm '$a'"))
      case _                                                => ()
    }

    // 3. created / expires checks
    input.created match {
      case Some(c) =>
        if (c < 0L)
          return Future.successful(Left(s"signature '$label' has a negative created timestamp"))
        if (c - config.clockSkewSeconds > now)
          return Future.successful(Left(s"signature '$label' created in the future"))
        // maxAgeSeconds < 0 would otherwise be a foot-gun (negative numbers compare with `now - c > maxAge + skew`
        // in a way that lets very old signatures through). Coerce to 0 so a config typo can't widen the window.
        config.maxAgeSeconds.foreach { raw =>
          val maxAge = math.max(0L, raw)
          if (now - c > maxAge + config.clockSkewSeconds)
            return Future.successful(Left(s"signature '$label' is older than $maxAge seconds"))
        }
      case None    => ()
    }
    input.expires.foreach { e =>
      if (now > e + config.clockSkewSeconds) return Future.successful(Left(s"signature '$label' has expired"))
    }

    // 4. keyid
    if (config.requireKeyid && input.keyid.isEmpty)
      return Future.successful(Left(s"signature '$label' has no keyid"))

    val sigBytes = sigMap.get(label) match {
      case None    => return Future.successful(Left(s"signature dict is missing label '$label'"))
      case Some(b) => b
    }

    // 5. find a matching key source. Strategy: when the signature carries a keyid, prefer keys that declare the
    //    same id; otherwise (and only then) try keys with no declared keyid as a fallback. Keys with a *different*
    //    keyid are never considered. Without a sig keyid we try everything.
    val keysForKid = input.keyid match {
      case None    => config.keys
      case Some(k) =>
        val explicit = config.keys.filter(_.keyid.contains(k))
        if (explicit.nonEmpty) explicit
        else {
          if (logger.isDebugEnabled)
            logger.debug(s"signature '$label' keyid='$k' has no exact match in configured keys; trying keyless keys")
          config.keys.filter(_.keyid.isEmpty)
        }
    }
    if (keysForKid.isEmpty)
      return Future.successful(Left(s"no key configured for keyid '${input.keyid.getOrElse("")}'"))

    // 6. Build signature base, then try each candidate key.
    HttpSigBase.build(msg, input, None) match {
      case Left(err)   => Future.successful(Left(s"signature '$label': cannot build base — $err"))
      case Right(base) =>
        val baseBytes = base.getBytes(StandardCharsets.UTF_8)

        def loop(remaining: List[HttpSigKeySource], lastErr: String): Future[Either[String, Boolean]] =
          remaining match {
            case Nil          => Future.successful(Left(lastErr))
            case head :: tail =>
              // Per-key alg resolution: when the configured key declares its own `alg`, that wins (immune to
              // algorithm-confusion). When the key has no alg, we accept whatever the signature claims, but the
              // `allowed_algorithms` list still gates the result — operators control the worst case there.
              val resolvedAlg: Either[String, String] = (head.alg, declaredAlg) match {
                case (Some(keyAlg), Some(sigAlg)) if !keyAlg.equalsIgnoreCase(sigAlg) =>
                  Left(s"signature '$label' alg=$sigAlg does not match key '${head.keyid.getOrElse("")}' alg=$keyAlg")
                case (Some(keyAlg), _)                                                => Right(keyAlg)
                case (None, Some(sigAlg))                                             => Right(sigAlg)
                case (None, None)                                                     =>
                  Left(s"signature '$label' has no alg parameter and the configured key does not declare one")
              }
              resolvedAlg match {
                case Left(err)  => loop(tail, err)
                case Right(alg) =>
                  if (!config.allowedAlgorithms.contains(alg)) loop(tail, s"algorithm '$alg' not in allowed_algorithms")
                  else
                    HttpSigKeyResolver.publicKey(head, alg, input.keyid).flatMap {
                      case Left(err)     => loop(tail, err)
                      case Right(keyMat) =>
                        HttpSigAlgorithms.verify(alg, baseBytes, sigBytes, keyMat) match {
                          case Right(_)  => Future.successful(Right(coveredNames.contains("content-digest")))
                          case Left(err) =>
                            if (logger.isDebugEnabled) logger.debug(s"verify failed with key candidate: $err")
                            loop(tail, err)
                        }
                    }
              }
          }
        loop(keysForKid, "no matching key verified signature")
    }
  }

  // Body-digest verification, only entered when the signature claimed coverage of `content-digest`. There is no
  // operator switch to disable this: if a signer asserted body integrity via content-digest, we MUST check it.
  override def transformRequest(
      ctx: NgTransformerRequestContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpRequest]] = {
    val pending = ctx.attrs.get(HttpSigAttrs.PendingDigestKey)
    if (pending.isEmpty) {
      Future.successful(Right(ctx.otoroshiRequest))
    } else {
      ctx.otoroshiRequest.header("Content-Digest") match {
        case None         =>
          Future.successful(Left(denyJson(401, "signature covers content-digest but Content-Digest header is missing")))
        case Some(digest) =>
          ctx.otoroshiRequest.body.runFold(ByteString.empty)(_ ++ _).map { raw =>
            HttpSigContentDigest.verify(digest, raw.toArray) match {
              case Left(err) => Left(denyJson(401, s"content-digest verification failed: $err"))
              case Right(_)  =>
                Right(ctx.otoroshiRequest.copy(body = Source.single(raw)))
            }
          }
      }
    }
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// Plugin 2: HttpSignatureSignResponse
// ---------------------------------------------------------------------------------------------------------------------

case class HttpSignatureSignResponseConfig(
    key: Option[HttpSigKeySource] = None,
    algorithm: String = HttpSigAlgorithms.Ed25519Alg,
    keyid: String = "",
    label: String = "sig1",
    components: Seq[String] = Seq("@status", "content-type", "content-digest"),
    expiresInSeconds: Option[Long] = Some(300L),
    includeCreated: Boolean = true,
    includeNonce: Boolean = false,
    addContentDigest: Boolean = true,
    contentDigestAlgorithm: String = "sha-256",
    tag: Option[String] = None
) extends NgPluginConfig {
  def json: JsValue = HttpSignatureSignResponseConfig.format.writes(this)
}

object HttpSignatureSignResponseConfig {
  val configFlow: Seq[String]        = Seq(
    "key",
    "algorithm",
    "keyid",
    "signature_label",
    "components",
    "add_content_digest",
    "content_digest_algorithm",
    "include_created",
    "expires_in_seconds",
    "include_nonce",
    "tag"
  )
  val configSchema: Option[JsObject] = Some(
    Json.obj(
      "key"                      -> Json.obj(
        "type"  -> "any",
        "label" -> "Signing key",
        "help"  -> "Single key entry: { kind: 'cert' | 'inline', cert_id | secret_or_pem, keyid, alg }. JWKS is not valid for signing.",
        "props" -> Json.obj("height" -> "200px", "language" -> "json")
      ),
      "algorithm"                -> Json.obj(
        "type"  -> "select",
        "label" -> "Algorithm",
        "help"  -> "Signature algorithm. Must match the key type.",
        "props" -> Json.obj("options" -> JsArray(HttpSigAlgorithms.all.map(JsString)))
      ),
      "keyid"                    -> Json.obj(
        "type"  -> "string",
        "label" -> "Key ID",
        "help"  -> "Emitted as the `keyid` parameter so verifiers can pick the right key."
      ),
      "signature_label"          -> Json.obj(
        "type"  -> "string",
        "label" -> "Signature label",
        "help"  -> "Dictionary key in the Signature-Input and Signature headers."
      ),
      "components"               -> Json.obj(
        "type"  -> "array",
        "label" -> "Components to cover",
        "help"  -> "Order is preserved. Use \"@status\", a header name, or \"@target-uri;req\" to refer to the request."
      ),
      "add_content_digest"       -> Json.obj(
        "type"  -> "bool",
        "label" -> "Add Content-Digest header",
        "help"  -> "Compute and inject Content-Digest before signing when `content-digest` is among the covered components."
      ),
      "content_digest_algorithm" -> Json.obj(
        "type"  -> "select",
        "label" -> "Content-Digest algorithm",
        "help"  -> "Hash function used to build the Content-Digest header.",
        "props" -> Json.obj("options" -> JsArray(Seq("sha-256", "sha-512").map(JsString)))
      ),
      "include_created"          -> Json.obj(
        "type"  -> "bool",
        "label" -> "Include `created`",
        "help"  -> "Emit a `created` parameter with the current Unix timestamp."
      ),
      "expires_in_seconds"       -> Json.obj(
        "type"  -> "number",
        "label" -> "Signature validity",
        "help"  -> "In seconds. Sets `expires = created + this`. Null omits the parameter."
      ),
      "include_nonce"            -> Json.obj(
        "type"  -> "bool",
        "label" -> "Include random nonce",
        "help"  -> "Add a fresh random `nonce` parameter to each signature."
      ),
      "tag"                      -> Json.obj(
        "type"  -> "string",
        "label" -> "Application tag",
        "help"  -> "Optional `tag` parameter to scope signatures to a given application context."
      )
    )
  )

  val format: Format[HttpSignatureSignResponseConfig] = new Format[HttpSignatureSignResponseConfig] {
    override def reads(json: JsValue): JsResult[HttpSignatureSignResponseConfig] = Try {
      val keyJson   = (json \ "key").asOpt[JsValue]
      val keySource = keyJson.flatMap(HttpSigKeySource.read(_).asOpt)
      HttpSignatureSignResponseConfig(
        key = keySource,
        algorithm = (json \ "algorithm").asOpt[String].getOrElse(HttpSigAlgorithms.Ed25519Alg).toLowerCase,
        keyid = (json \ "keyid").asOpt[String].getOrElse(""),
        label = (json \ "signature_label").asOpt[String].filter(_.nonEmpty).getOrElse("sig1"),
        components =
          (json \ "components").asOpt[Seq[String]].getOrElse(Seq("@status", "content-type", "content-digest")),
        expiresInSeconds = (json \ "expires_in_seconds").asOpt[Long],
        includeCreated = (json \ "include_created").asOpt[Boolean].getOrElse(true),
        includeNonce = (json \ "include_nonce").asOpt[Boolean].getOrElse(false),
        addContentDigest = (json \ "add_content_digest").asOpt[Boolean].getOrElse(true),
        contentDigestAlgorithm = (json \ "content_digest_algorithm").asOpt[String].getOrElse("sha-256").toLowerCase,
        tag = (json \ "tag").asOpt[String].filter(_.nonEmpty)
      )
    } match {
      case Success(v) => JsSuccess(v)
      case Failure(e) => JsError(e.getMessage)
    }
    override def writes(o: HttpSignatureSignResponseConfig): JsValue             = Json.obj(
      "key"                      -> (o.key.map(_.json).getOrElse(JsNull): JsValue),
      "algorithm"                -> o.algorithm,
      "keyid"                    -> o.keyid,
      "signature_label"          -> o.label,
      "components"               -> o.components,
      "expires_in_seconds"       -> o.expiresInSeconds,
      "include_created"          -> o.includeCreated,
      "include_nonce"            -> o.includeNonce,
      "add_content_digest"       -> o.addContentDigest,
      "content_digest_algorithm" -> o.contentDigestAlgorithm,
      "tag"                      -> o.tag
    )
  }
}

class HttpSignatureSignResponse extends NgRequestTransformer {

  private val logger = Logger("otoroshi-plugins-httpsig-sign-response")

  override def steps: Seq[NgStep]                          = Seq(NgStep.TransformResponse)
  override def categories: Seq[NgPluginCategory]           = Seq(NgPluginCategory.Security)
  override def visibility: NgPluginVisibility              = NgPluginVisibility.NgUserLand
  override def multiInstance: Boolean                      = true
  override def core: Boolean                               = true
  override def name: String                                = "Add HTTP Message signature"
  override def description: Option[String]                 =
    "Signs the outgoing response per RFC 9421".some
  override def defaultConfigObject: Option[NgPluginConfig] = HttpSignatureSignResponseConfig().some
  override def configFlow: Seq[String]                     = HttpSignatureSignResponseConfig.configFlow
  override def configSchema: Option[JsObject]              = HttpSignatureSignResponseConfig.configSchema
  override def noJsForm: Boolean                           = true
  override def usesCallbacks: Boolean                      = false
  override def transformsRequest: Boolean                  = false
  override def transformsResponse: Boolean                 = true
  override def transformsError: Boolean                    = false

  override def transformResponse(
      ctx: NgTransformerResponseContext
  )(implicit env: Env, ec: ExecutionContext, mat: Materializer): Future[Either[Result, NgPluginHttpResponse]] = {
    val config =
      ctx
        .cachedConfig(internalName)(HttpSignatureSignResponseConfig.format)
        .getOrElse(HttpSignatureSignResponseConfig())

    config.key match {
      case None      =>
        logger.warn(s"route '${ctx.route.id}' has the HttpSignatureSignResponse plugin enabled but no key configured")
        Future.successful(Right(ctx.otoroshiResponse))
      case Some(src) =>
        if (!HttpSigAlgorithms.all.contains(config.algorithm)) {
          logger.warn(s"route '${ctx.route.id}': unknown algorithm '${config.algorithm}', skipping signature")
          Future.successful(Right(ctx.otoroshiResponse))
        } else {
          val needsBody                              = config.addContentDigest && config.components.exists(_.equalsIgnoreCase("content-digest"))
          val futureBody: Future[Option[ByteString]] =
            if (needsBody) ctx.otoroshiResponse.body.runFold(ByteString.empty)(_ ++ _).map(Some(_))
            else Future.successful(None)

          futureBody.flatMap { maybeBody =>
            val (respHeaders, respBodySource) = maybeBody match {
              case Some(raw) =>
                HttpSigContentDigest.compute(config.contentDigestAlgorithm, raw.toArray) match {
                  case Right(v)  =>
                    val merged = ctx.otoroshiResponse.headers + ("Content-Digest" -> v)
                    (merged, Source.single(raw))
                  case Left(err) =>
                    logger.warn(s"content-digest compute failed: $err")
                    (ctx.otoroshiResponse.headers, Source.single(raw))
                }
              case None      => (ctx.otoroshiResponse.headers, ctx.otoroshiResponse.body)
            }

            val now         = System.currentTimeMillis() / 1000L
            val inputParams = collection.mutable.ListBuffer.empty[(String, HttpSigStructuredFields.Param)]
            if (config.includeCreated) inputParams += ("created" -> HttpSigStructuredFields.ParamInt(now))
            config.expiresInSeconds.foreach(e =>
              inputParams += ("expires" -> HttpSigStructuredFields.ParamInt(now + e))
            )
            if (config.keyid.nonEmpty) inputParams += ("keyid"   -> HttpSigStructuredFields.ParamString(config.keyid))
            inputParams += ("alg"                                -> HttpSigStructuredFields.ParamString(config.algorithm))
            if (config.includeNonce) inputParams += ("nonce"     -> HttpSigStructuredFields.ParamString(randomNonce()))
            config.tag.foreach(t => inputParams += ("tag" -> HttpSigStructuredFields.ParamString(t)))

            val componentIds = parseComponents(config.components)
            val sigInput = HttpSigStructuredFields.SignatureInputValue(componentIds, inputParams.toList)

            val relatedRequest = HttpSigMessage.fromRequest(ctx.request)
            // Sign content-type as the server will emit it (Akka drops the charset for fixed-charset types like application/json).
            val signingHeaders = respHeaders.map {
              case (k, v) if k.equalsIgnoreCase("content-type") => k -> renderedContentType(v)
              case kv                                           => kv
            }
            val responseMsg    = relatedRequest.copy(
              headers = signingHeaders.toSeq,
              status = Some(ctx.otoroshiResponse.status)
            )

            HttpSigBase.build(responseMsg, sigInput, Some(relatedRequest)) match {
              case Left(err)   =>
                logger.warn(s"cannot build signature base on route '${ctx.route.id}': $err")
                Future.successful(Right(ctx.otoroshiResponse.copy(headers = respHeaders, body = respBodySource)))
              case Right(base) =>
                HttpSigKeyResolver.privateKey(src, config.algorithm).map {
                  case Left(err)  =>
                    logger.warn(s"cannot resolve signing key on route '${ctx.route.id}': $err")
                    Right(ctx.otoroshiResponse.copy(headers = respHeaders, body = respBodySource))
                  case Right(key) =>
                    HttpSigAlgorithms.sign(config.algorithm, base.getBytes(StandardCharsets.UTF_8), key) match {
                      case Left(err)       =>
                        logger.warn(s"signing failed on route '${ctx.route.id}': $err")
                        Right(ctx.otoroshiResponse.copy(headers = respHeaders, body = respBodySource))
                      case Right(sigBytes) =>
                        val sigInputHeader =
                          HttpSigStructuredFields.serializeSignatureInputDict(List(config.label -> sigInput))
                        val sigHeader      = HttpSigStructuredFields.serializeSignatureDict(List(config.label -> sigBytes))
                        val withSigHeaders = respHeaders +
                          ("Signature-Input" -> sigInputHeader) +
                          ("Signature"       -> sigHeader)
                        Right(ctx.otoroshiResponse.copy(headers = withSigHeaders, body = respBodySource))
                    }
                }
            }
          }
        }
    }
  }

  // Component strings in the config may be plain names ("@status", "date") or carry parameters
  // ("@query-param;name=\"foo\"", "content-digest;sf"). The helper in HttpSigStructuredFields handles both.
  private def parseComponents(components: Seq[String]): List[HttpSigStructuredFields.ComponentId] =
    components.toList.map(HttpSigStructuredFields.parseComponentString)

  private def renderedContentType(raw: String): String =
    ContentType.parse(raw) match {
      case Right(ct) => ct.toString()
      case Left(_)   => raw
    }

  private def randomNonce(): String = {
    val bytes = new Array[Byte](16)
    new java.security.SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }
}
