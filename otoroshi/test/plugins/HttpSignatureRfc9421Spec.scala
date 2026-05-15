package plugins

import org.scalatest.{MustMatchers, WordSpec}
import otoroshi.next.plugins._

import java.nio.charset.StandardCharsets
import java.util.Base64

// Unit tests for the pure RFC 9421 logic (no Otoroshi runtime required).
//
// The reference vector below is RFC 9421 §B.2.5 — "Signing a Request using hmac-sha256" — which is the only
// vector with a fully-deterministic outcome (HMAC is symmetric, so we can re-compute and compare bytes).
// Asymmetric vectors involve a random k value and cannot be re-derived; for those we round-trip sign-then-verify.
class HttpSignatureRfc9421Spec extends WordSpec with MustMatchers {

  // -- B.2.5 fixture --------------------------------------------------------------------------------------------------

  // The RFC's example request (B.1.1).
  private val msg: HttpSigMessage = SimpleSigMessage(
    method = "POST",
    fullUri = "https://example.com/foo?param=Value&Pet=dog",
    headers = Seq(
      "Host"           -> "example.com",
      "Date"           -> "Tue, 20 Apr 2021 02:07:55 GMT",
      "Content-Type"   -> "application/json",
      "Content-Digest" -> "sha-256=:X48E9qOokqqrvdts8nOJRJN3OWDUoyWxBf7kbu9DBPE=:",
      "Content-Length" -> "18"
    ),
    status = None
  )

  // The shared secret used in B.2.5. (Base64 from the RFC.)
  private val sharedSecret: Array[Byte] = Base64.getDecoder.decode(
    "uzvJfB4u3N0Jy4T7NZ75MDVcr8zSTInedJtkgcu46YW4XByzNJjxBdtjUkdJPBtbmHhIDi6pcl8jsasjlTMtDQ=="
  )

  "Structured-fields parser" should {

    "round-trip a Signature-Input dictionary" in {
      val raw            = """sig1=("@method" "@target-uri" "host" "content-digest");created=1618884473;keyid="test-key-ed25519""""
      val parsed         = HttpSigStructuredFields.parseSignatureInputDict(raw).right.get
      parsed.size mustBe 1
      val (label, value) = parsed.head
      label mustBe "sig1"
      value.components.map(_.name) mustBe List("@method", "@target-uri", "host", "content-digest")
      value.created mustBe Some(1618884473L)
      value.keyid mustBe Some("test-key-ed25519")
      val reserialized   = HttpSigStructuredFields.serializeSignatureInputDict(parsed)
      // Re-parsing the reserialized form must produce the same structure.
      val again          = HttpSigStructuredFields.parseSignatureInputDict(reserialized).right.get
      again.head._2.components.map(_.name) mustBe value.components.map(_.name)
    }

    "parse a Signature byte-sequence dictionary" in {
      val raw    = "sig1=:cHJvb2Y=:"
      val parsed = HttpSigStructuredFields.parseSignatureDict(raw).right.get
      parsed.size mustBe 1
      new String(parsed.head._2, StandardCharsets.UTF_8) mustBe "proof"
    }

    "reject a malformed Signature-Input value" in {
      val raw = "sig1=this-is-not-a-list"
      HttpSigStructuredFields.parseSignatureInputDict(raw).isLeft mustBe true
    }

    "preserve case in `name` parameter of @query-param" in {
      val raw    = "sig1=(\"@query-param\";name=\"Pet\");created=1"
      val parsed = HttpSigStructuredFields.parseSignatureInputDict(raw).right.get
      val comp   = parsed.head._2.components.head
      comp.name mustBe "@query-param"
      comp.paramString("name") mustBe Some("Pet")
    }
  }

  "Signature base construction" should {

    "produce the canonical lines for the B.2.5 example" in {
      val params   = List[(String, HttpSigStructuredFields.Param)](
        "created" -> HttpSigStructuredFields.ParamInt(1618884473L),
        "keyid"   -> HttpSigStructuredFields.ParamString("test-shared-secret")
      )
      val sigInput = HttpSigStructuredFields.SignatureInputValue(
        components = List(
          HttpSigStructuredFields.ComponentId("date", Nil),
          HttpSigStructuredFields.ComponentId("@authority", Nil),
          HttpSigStructuredFields.ComponentId("content-type", Nil)
        ),
        params = params
      )
      val base     = HttpSigBase.build(msg, sigInput, None).right.get
      // The expected canonical signature base (verbatim from the RFC).
      val expected =
        """"date": Tue, 20 Apr 2021 02:07:55 GMT""" + "\n" +
          """"@authority": example.com""" + "\n" +
          """"content-type": application/json""" + "\n" +
          """"@signature-params": ("date" "@authority" "content-type");created=1618884473;keyid="test-shared-secret""""
      base mustBe expected
    }

    "fail on duplicate component identifiers" in {
      val sigInput = HttpSigStructuredFields.SignatureInputValue(
        components = List(
          HttpSigStructuredFields.ComponentId("date", Nil),
          HttpSigStructuredFields.ComponentId("date", Nil)
        ),
        params = Nil
      )
      HttpSigBase.build(msg, sigInput, None).isLeft mustBe true
    }

    "fail on missing header" in {
      val sigInput = HttpSigStructuredFields.SignatureInputValue(
        components = List(HttpSigStructuredFields.ComponentId("X-Missing", Nil)),
        params = Nil
      )
      HttpSigBase.build(msg, sigInput, None).isLeft mustBe true
    }

    "compute @authority lowercase with port omitted when default" in {
      val m    = SimpleSigMessage("GET", "https://Example.COM:443/", Seq.empty, None)
      val sig  = HttpSigStructuredFields.SignatureInputValue(
        components = List(HttpSigStructuredFields.ComponentId("@authority", Nil)),
        params = Nil
      )
      val base = HttpSigBase.build(m, sig, None).right.get
      base must include("\"@authority\": example.com\n")
    }

    "compute @authority with non-default port" in {
      val m    = SimpleSigMessage("GET", "https://example.com:8443/", Seq.empty, None)
      val sig  = HttpSigStructuredFields.SignatureInputValue(
        components = List(HttpSigStructuredFields.ComponentId("@authority", Nil)),
        params = Nil
      )
      val base = HttpSigBase.build(m, sig, None).right.get
      base must include("\"@authority\": example.com:8443\n")
    }

    "support @query-param;name= with case-sensitive value" in {
      val sig  = HttpSigStructuredFields.SignatureInputValue(
        components = List(
          HttpSigStructuredFields.ComponentId(
            "@query-param",
            List("name" -> HttpSigStructuredFields.ParamString("Pet"))
          )
        ),
        params = Nil
      )
      val base = HttpSigBase.build(msg, sig, None).right.get
      base must include("\"@query-param\";name=\"Pet\": dog\n")
    }

    // RFC 9421 §2.2.7: when the URI has no query, @query value is the single "?" character.
    "compute @query as '?' when URI has no query string" in {
      val m    = SimpleSigMessage("GET", "https://example.com/foo", Seq.empty, None)
      val sig  = HttpSigStructuredFields.SignatureInputValue(
        components = List(HttpSigStructuredFields.ComponentId("@query", Nil)),
        params = Nil
      )
      val base = HttpSigBase.build(m, sig, None).right.get
      base must include("\"@query\": ?\n")
    }

    // RFC 9421 §2.2.6: when the URI has no path, the @path value is "/".
    "compute @path as '/' when URI has no path" in {
      val m    = SimpleSigMessage("GET", "https://example.com", Seq.empty, None)
      val sig  = HttpSigStructuredFields.SignatureInputValue(
        components = List(HttpSigStructuredFields.ComponentId("@path", Nil)),
        params = Nil
      )
      val base = HttpSigBase.build(m, sig, None).right.get
      base must include("\"@path\": /\n")
    }

    // RFC 9421 §2.1: multi-valued header fields MUST be joined with ", " in the order they appear.
    "join multi-valued header fields with ', ' separator" in {
      val m    = SimpleSigMessage(
        "GET",
        "https://example.com/",
        Seq("X-Custom" -> "first", "X-Custom" -> "second"),
        None
      )
      val sig  = HttpSigStructuredFields.SignatureInputValue(
        components = List(HttpSigStructuredFields.ComponentId("x-custom", Nil)),
        params = Nil
      )
      val base = HttpSigBase.build(m, sig, None).right.get
      base must include("\"x-custom\": first, second\n")
    }

    // RFC 9421 §2.1: leading/trailing whitespace MUST be stripped from each individual value.
    "strip leading and trailing OWS from header values" in {
      val m    = SimpleSigMessage("GET", "https://example.com/", Seq("Date" -> "  Tue, 20 Apr 2021 02:07:55 GMT  "), None)
      val sig  = HttpSigStructuredFields.SignatureInputValue(
        components = List(HttpSigStructuredFields.ComponentId("date", Nil)),
        params = Nil
      )
      val base = HttpSigBase.build(m, sig, None).right.get
      base must include("\"date\": Tue, 20 Apr 2021 02:07:55 GMT\n")
    }

    // RFC 9421 §2.5: the signature base MUST NOT end with a trailing linefeed.
    "not end with a trailing linefeed" in {
      val sigInput = HttpSigStructuredFields.SignatureInputValue(
        components = List(HttpSigStructuredFields.ComponentId("date", Nil)),
        params = List("created" -> HttpSigStructuredFields.ParamInt(1L))
      )
      val base = HttpSigBase.build(msg, sigInput, None).right.get
      base.endsWith("\n") mustBe false
      base.endsWith("\"@signature-params\": (\"date\");created=1") mustBe true
    }
  }

  "HMAC sign/verify round-trip (RFC 9421 §B.2.5 algorithm)" should {

    "produce a signature that verifies with the same key" in {
      val params   = List[(String, HttpSigStructuredFields.Param)](
        "created" -> HttpSigStructuredFields.ParamInt(1618884473L),
        "keyid"   -> HttpSigStructuredFields.ParamString("test-shared-secret")
      )
      val sigInput = HttpSigStructuredFields.SignatureInputValue(
        components = List(
          HttpSigStructuredFields.ComponentId("date", Nil),
          HttpSigStructuredFields.ComponentId("@authority", Nil),
          HttpSigStructuredFields.ComponentId("content-type", Nil)
        ),
        params = params
      )
      val base     = HttpSigBase.build(msg, sigInput, None).right.get
      val sig      = HttpSigAlgorithms
        .sign(
          HttpSigAlgorithms.Hmac256,
          base.getBytes(StandardCharsets.UTF_8),
          Left(sharedSecret)
        )
        .right
        .get
      // Verification with the same secret succeeds.
      HttpSigAlgorithms
        .verify(
          HttpSigAlgorithms.Hmac256,
          base.getBytes(StandardCharsets.UTF_8),
          sig,
          Left(sharedSecret)
        )
        .isRight mustBe true
      // Tampering the signature flips one bit; verification must fail.
      sig(0) = (sig(0) ^ 0x01).toByte
      HttpSigAlgorithms
        .verify(
          HttpSigAlgorithms.Hmac256,
          base.getBytes(StandardCharsets.UTF_8),
          sig,
          Left(sharedSecret)
        )
        .isLeft mustBe true
    }
  }

  "Ed25519 round-trip" should {
    "sign with a fresh keypair and verify" in {
      val kpg      = java.security.KeyPairGenerator.getInstance("Ed25519")
      val kp       = kpg.generateKeyPair()
      val sigInput = HttpSigStructuredFields.SignatureInputValue(
        components = List(HttpSigStructuredFields.ComponentId("date", Nil)),
        params = List("alg" -> HttpSigStructuredFields.ParamString("ed25519"))
      )
      val base     = HttpSigBase.build(msg, sigInput, None).right.get
      val sig      = HttpSigAlgorithms
        .sign(
          HttpSigAlgorithms.Ed25519Alg,
          base.getBytes(StandardCharsets.UTF_8),
          Right(kp.getPrivate)
        )
        .right
        .get
      HttpSigAlgorithms
        .verify(
          HttpSigAlgorithms.Ed25519Alg,
          base.getBytes(StandardCharsets.UTF_8),
          sig,
          Right(kp.getPublic)
        )
        .isRight mustBe true
    }
  }

  "ECDSA P-256 round-trip" should {
    "sign with a fresh keypair and verify (raw r||s P1363 format)" in {
      val kpg      = java.security.KeyPairGenerator.getInstance("EC")
      kpg.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"))
      val kp       = kpg.generateKeyPair()
      val sigInput = HttpSigStructuredFields.SignatureInputValue(
        components = List(HttpSigStructuredFields.ComponentId("date", Nil)),
        params = List("alg" -> HttpSigStructuredFields.ParamString("ecdsa-p256-sha256"))
      )
      val base     = HttpSigBase.build(msg, sigInput, None).right.get
      val sig      = HttpSigAlgorithms
        .sign(
          HttpSigAlgorithms.EcdsaP256,
          base.getBytes(StandardCharsets.UTF_8),
          Right(kp.getPrivate)
        )
        .right
        .get
      // RFC 9421 mandates 64-byte fixed-length output for P-256 (32 bytes r || 32 bytes s).
      sig.length mustBe 64
      HttpSigAlgorithms
        .verify(
          HttpSigAlgorithms.EcdsaP256,
          base.getBytes(StandardCharsets.UTF_8),
          sig,
          Right(kp.getPublic)
        )
        .isRight mustBe true
    }
  }

  "Content-Digest" should {
    "compute and verify sha-256" in {
      val body   = """{"hello": "world"}""".getBytes(StandardCharsets.UTF_8)
      val header = HttpSigContentDigest.compute("sha-256", body).right.get
      // The vector from RFC 9421 §B.1.1.
      header mustBe "sha-256=:X48E9qOokqqrvdts8nOJRJN3OWDUoyWxBf7kbu9DBPE=:"
      HttpSigContentDigest.verify(header, body).isRight mustBe true
      HttpSigContentDigest.verify(header, "tampered".getBytes(StandardCharsets.UTF_8)).isLeft mustBe true
    }

    "compute and verify sha-512" in {
      val body   = "hello".getBytes(StandardCharsets.UTF_8)
      val header = HttpSigContentDigest.compute("sha-512", body).right.get
      header must startWith("sha-512=:")
      HttpSigContentDigest.verify(header, body).isRight mustBe true
    }

    "verify all entries when multiple algorithms are present in one header" in {
      // Operators sometimes ship both digests for compatibility. Both MUST match the body.
      val body  = "payload".getBytes(StandardCharsets.UTF_8)
      val sha256 = HttpSigContentDigest.compute("sha-256", body).right.get.stripPrefix("sha-256=")
      val sha512 = HttpSigContentDigest.compute("sha-512", body).right.get.stripPrefix("sha-512=")
      val combined = s"sha-256=$sha256, sha-512=$sha512"
      val checked  = HttpSigContentDigest.verify(combined, body).right.get
      checked.toSet mustBe Set("sha-256", "sha-512")
      // If any one entry is wrong, the whole header is rejected.
      val bad      = s"sha-256=$sha256, sha-512=:AAAA:"
      HttpSigContentDigest.verify(bad, body).isLeft mustBe true
    }

    "reject unknown digest algorithm" in {
      HttpSigContentDigest.compute("md5", Array.empty[Byte]).isLeft mustBe true
      HttpSigContentDigest.verify("md5=:abc:", Array.empty[Byte]).isLeft mustBe true
    }
  }

  // -- Asymmetric algorithm round-trips (RFC 9421 §3.3.1-§3.3.6) -------------------------------------------------------
  //
  // For asymmetric algos the signature is non-deterministic (RSA-PSS has random salt, ECDSA has random k), so we can't
  // pin a byte vector. We instead verify the sign → verify cycle and the basic format guarantees the RFC mandates.

  "RSA-PSS SHA-512 round-trip (RFC 9421 §3.3.1)" should {
    "sign and verify with a fresh RSA-2048 keypair" in {
      val kpg = java.security.KeyPairGenerator.getInstance("RSA")
      kpg.initialize(2048)
      val kp  = kpg.generateKeyPair()
      val data = "hello".getBytes(StandardCharsets.UTF_8)
      val sig  = HttpSigAlgorithms.sign(HttpSigAlgorithms.RsaPssSha512, data, Right(kp.getPrivate)).right.get
      HttpSigAlgorithms.verify(HttpSigAlgorithms.RsaPssSha512, data, sig, Right(kp.getPublic)).isRight mustBe true
      // Wrong public key MUST fail verification.
      val kp2 = kpg.generateKeyPair()
      HttpSigAlgorithms.verify(HttpSigAlgorithms.RsaPssSha512, data, sig, Right(kp2.getPublic)).isLeft mustBe true
    }
  }

  "RSA v1.5 SHA-256 round-trip (RFC 9421 §3.3.2)" should {
    "sign and verify with a fresh RSA-2048 keypair" in {
      val kpg = java.security.KeyPairGenerator.getInstance("RSA")
      kpg.initialize(2048)
      val kp  = kpg.generateKeyPair()
      val data = "rsa-v15".getBytes(StandardCharsets.UTF_8)
      val sig  = HttpSigAlgorithms.sign(HttpSigAlgorithms.RsaV1_5Sha256, data, Right(kp.getPrivate)).right.get
      HttpSigAlgorithms.verify(HttpSigAlgorithms.RsaV1_5Sha256, data, sig, Right(kp.getPublic)).isRight mustBe true
    }
  }

  "ECDSA P-384 round-trip (RFC 9421 §3.3.5)" should {
    "sign and verify with raw r||s P1363 format" in {
      val kpg = java.security.KeyPairGenerator.getInstance("EC")
      kpg.initialize(new java.security.spec.ECGenParameterSpec("secp384r1"))
      val kp  = kpg.generateKeyPair()
      val data = "p384".getBytes(StandardCharsets.UTF_8)
      val sig  = HttpSigAlgorithms.sign(HttpSigAlgorithms.EcdsaP384, data, Right(kp.getPrivate)).right.get
      // RFC 9421 §3.3.5: P-384 raw r||s is 96 bytes (48 + 48).
      sig.length mustBe 96
      HttpSigAlgorithms.verify(HttpSigAlgorithms.EcdsaP384, data, sig, Right(kp.getPublic)).isRight mustBe true
    }
  }

  "Algorithm dispatch" should {
    "reject an unknown algorithm name" in {
      HttpSigAlgorithms.sign("md5", Array.empty[Byte], Left(Array.empty[Byte])).isLeft mustBe true
      HttpSigAlgorithms.verify("md5", Array.empty[Byte], Array.empty[Byte], Left(Array.empty[Byte])).isLeft mustBe true
    }

    "reject wrong key shape (HMAC algo with asymmetric key)" in {
      val kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
      // sign with hmac alg but pass a Right(PrivateKey) — pattern match must fall through to "unsupported".
      HttpSigAlgorithms.sign(HttpSigAlgorithms.Hmac256, "x".getBytes, Right(kp.getPrivate)).isLeft mustBe true
    }
  }

  // -- @status and ;req for response signatures (RFC 9421 §2.2.9 + §2.4) ----------------------------------------------

  "Response signature components" should {
    "build base with @status for a response message" in {
      val response = SimpleSigMessage("GET", "https://example.com/", Seq("Content-Type" -> "application/json"), Some(200))
      val sigInput = HttpSigStructuredFields.SignatureInputValue(
        components = List(
          HttpSigStructuredFields.ComponentId("@status", Nil),
          HttpSigStructuredFields.ComponentId("content-type", Nil)
        ),
        params = Nil
      )
      val base = HttpSigBase.build(response, sigInput, None).right.get
      base must include("\"@status\": 200\n")
      base must include("\"content-type\": application/json\n")
    }

    "build base referencing a request component via ;req" in {
      val request  = SimpleSigMessage("POST", "https://api.example.com/foo", Seq("X-Trace" -> "abc-123"), None)
      val response = SimpleSigMessage("POST", "https://api.example.com/foo", Seq("Content-Type" -> "text/plain"), Some(201))
      val sigInput = HttpSigStructuredFields.SignatureInputValue(
        components = List(
          HttpSigStructuredFields.ComponentId("@status", Nil),
          HttpSigStructuredFields.ComponentId(
            "x-trace",
            List("req" -> HttpSigStructuredFields.ParamBool(true))
          )
        ),
        params = Nil
      )
      val base = HttpSigBase.build(response, sigInput, Some(request)).right.get
      base must include("\"@status\": 201\n")
      base must include("\"x-trace\";req: abc-123\n")
    }

    "fail when ;req is set but no related request is provided" in {
      val response = SimpleSigMessage("GET", "https://example.com/", Seq.empty, Some(200))
      val sigInput = HttpSigStructuredFields.SignatureInputValue(
        components = List(
          HttpSigStructuredFields.ComponentId(
            "@method",
            List("req" -> HttpSigStructuredFields.ParamBool(true))
          )
        ),
        params = Nil
      )
      HttpSigBase.build(response, sigInput, None).isLeft mustBe true
    }
  }

  // -- HMAC secret decoding (Otoroshi-specific UX) --------------------------------------------------------------------
  //
  // The plugin accepts inline HMAC secrets in four shapes; getting this wrong means signatures look valid in tests but
  // fail against real-world signers who paste keys in different formats. These tests call the production decoder
  // directly so a behavioural change in `HttpSigKeyResolver.decodeSecret` shows up immediately.

  "HttpSigKeyResolver.decodeSecret" should {
    val truthBytes = Array[Byte](0x74, 0x65, 0x73, 0x74) // "test"

    "decode `hex:` prefixed secrets" in {
      HttpSigKeyResolver.decodeSecret("hex:74657374").toSeq mustBe truthBytes.toSeq
    }

    "decode `base64:` prefixed secrets" in {
      HttpSigKeyResolver.decodeSecret("base64:dGVzdA==").toSeq mustBe truthBytes.toSeq
    }

    "decode `base64url:` prefixed secrets (padding stripped form)" in {
      HttpSigKeyResolver.decodeSecret("base64url:dGVzdA").toSeq mustBe truthBytes.toSeq
    }

    "treat unprefixed strings as raw UTF-8 bytes" in {
      HttpSigKeyResolver.decodeSecret("test").toSeq mustBe truthBytes.toSeq
    }

    "reject hex with odd length" in {
      val thrown = scala.util.Try(HttpSigKeyResolver.decodeSecret("hex:abc"))
      thrown.isFailure mustBe true
    }

    "produce different bytes for different prefixes of visually-similar inputs" in {
      // Guards against the operator's `hex:74657374` being silently treated as the literal string "74657374"
      // because of a prefix-stripping regression.
      HttpSigKeyResolver.decodeSecret("hex:74657374").toSeq must not be HttpSigKeyResolver.decodeSecret("74657374").toSeq
    }
  }
}
