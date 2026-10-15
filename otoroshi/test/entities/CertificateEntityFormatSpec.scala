package entities

import org.joda.time.DateTime
import otoroshi.models.*
import otoroshi.ssl.Cert
import play.api.libs.json.*

class CertificateEntityFormatSpec extends EntityFormatSpec {

  // small EC certificates generated once with openssl, valid from 2026-10-15 to 2126-09-21

  // self signed leaf: CN=www.oto.tools, OU=Tests, O=Otoroshi, sans www.oto.tools and *.oto.tools, serial 4242, not a ca
  private val leafPem =
    """-----BEGIN CERTIFICATE-----
      |MIIBvzCCAWSgAwIBAgICEJIwCgYIKoZIzj0EAwIwOzEWMBQGA1UEAwwNd3d3Lm90
      |by50b29sczEOMAwGA1UECwwFVGVzdHMxETAPBgNVBAoMCE90b3Jvc2hpMCAXDTI2
      |MTAxNTEzMTM1N1oYDzIxMjYwOTIxMTMxMzU3WjA7MRYwFAYDVQQDDA13d3cub3Rv
      |LnRvb2xzMQ4wDAYDVQQLDAVUZXN0czERMA8GA1UECgwIT3Rvcm9zaGkwWTATBgcq
      |hkjOPQIBBggqhkjOPQMBBwNCAAT1ncne2erjo5ORvMJ7SGI8vMQ88FR2Wc3DKWT4
      |licJ6FV5ZbeItMJxSh0nkL3yxMgMNQJrQZxYUI8QooCT4n69o1YwVDAMBgNVHRMB
      |Af8EAjAAMCUGA1UdEQQeMByCDXd3dy5vdG8udG9vbHOCCyoub3RvLnRvb2xzMB0G
      |A1UdDgQWBBRCvDpA4D7d+QPV4qwWYNEoqEqCdjAKBggqhkjOPQQDAgNJADBGAiEA
      |5T3eeHG/K6M0/nF9zAq26e4zNXocLNp3F45N54hqOn4CIQCNMtPkdQLZs4s4aoNe
      |ZwKnOv2mFmItynDJkvNg4tYNoQ==
      |-----END CERTIFICATE-----""".stripMargin

  private val leafKey =
    """-----BEGIN PRIVATE KEY-----
      |MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQg8XhNH+dq6vTKK1uh
      |uhHuXAKSQigA+aZujNZ0hcY1M5qhRANCAAT1ncne2erjo5ORvMJ7SGI8vMQ88FR2
      |Wc3DKWT4licJ6FV5ZbeItMJxSh0nkL3yxMgMNQJrQZxYUI8QooCT4n69
      |-----END PRIVATE KEY-----""".stripMargin

  // the leaf key, encrypted with the password secret123
  private val leafEncryptedKey =
    """-----BEGIN ENCRYPTED PRIVATE KEY-----
      |MIH0MF8GCSqGSIb3DQEFDTBSMDEGCSqGSIb3DQEFDDAkBBCgA3ez3gfWiXTnMaL2
      |XVOjAgIIADAMBggqhkiG9w0CCQUAMB0GCWCGSAFlAwQBKgQQZWwXpNMTOfyc3rDy
      |oxOKWASBkMTVk/UeerWLmK4IEr/ji/xoFnKCroVcQAnwYS6hy2VPrJrVF+0HSjtE
      |dw1/Pz3NWZ146EthVHB3jLc0rhA9wU+7+ctPj4PXyF5u4S4wrfaz7TLft4d2Ydyl
      |s/kLiaVyoMH3jqpE/vJH+Xdh0Ng6477z5L8CFs3gBOS4PZNChcloF89RqZ2x4dML
      |3xZZQCs2QA==
      |-----END ENCRYPTED PRIVATE KEY-----""".stripMargin

  // self signed root: CN=Test Root CA, O=Otoroshi, serial 1
  private val caPem =
    """-----BEGIN CERTIFICATE-----
      |MIIBqTCCAU6gAwIBAgIBATAKBggqhkjOPQQDAjAqMRUwEwYDVQQDDAxUZXN0IFJv
      |b3QgQ0ExETAPBgNVBAoMCE90b3Jvc2hpMCAXDTI2MTAxNTEzMTMzN1oYDzIxMjYw
      |OTIxMTMxMzM3WjAqMRUwEwYDVQQDDAxUZXN0IFJvb3QgQ0ExETAPBgNVBAoMCE90
      |b3Jvc2hpMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEDziMZtYFJ57Hsf+7yPhD
      |9FOe9UnEXXw7sMSDstgMeaLDbTv3XbJ+lCx3xs+wnQVvqiBSJXJB/lWCMHksaIgl
      |CqNjMGEwHQYDVR0OBBYEFIl0HOfDzSlY0n0C2MslLI+Rm26pMB8GA1UdIwQYMBaA
      |FIl0HOfDzSlY0n0C2MslLI+Rm26pMA8GA1UdEwEB/wQFMAMBAf8wDgYDVR0PAQH/
      |BAQDAgEGMAoGCCqGSM49BAMCA0kAMEYCIQCHgFH1pAHXdveaFqKByAdzD3E6Xmyv
      |xqXYe9rzgZ2LxwIhAM/NUwUfOAos1m/6u54B+DoR5t/uGFdJGmp48k3F4QIc
      |-----END CERTIFICATE-----""".stripMargin

  private val caKey =
    """-----BEGIN PRIVATE KEY-----
      |MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgT2ZLi0Z3iL19L/ok
      |bNuyubXK1PycGed70Vs6IctZd7OhRANCAAQPOIxm1gUnnsex/7vI+EP0U571ScRd
      |fDuwxIOy2Ax5osNtO/ddsn6ULHfGz7CdBW+qIFIlckH+VYIweSxoiCUK
      |-----END PRIVATE KEY-----""".stripMargin

  // client certificate issued by the root: CN=client.oto.tools, O=Otoroshi, extended key usage clientAuth, serial 77
  private val clientPem =
    """-----BEGIN CERTIFICATE-----
      |MIIBvDCCAWOgAwIBAgIBTTAKBggqhkjOPQQDAjAqMRUwEwYDVQQDDAxUZXN0IFJv
      |b3QgQ0ExETAPBgNVBAoMCE90b3Jvc2hpMCAXDTI2MTAxNTEzMTMzN1oYDzIxMjYw
      |OTIxMTMxMzM3WjAuMRkwFwYDVQQDDBBjbGllbnQub3RvLnRvb2xzMREwDwYDVQQK
      |DAhPdG9yb3NoaTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABLraS0fzurXDtnWb
      |i74gSATmPjiyZVN0Qlf1mFj6eMo5Jltyfe190RAi4RCOfLL96k9QRwSw47uFVNpF
      |efkPYKajdDByMBMGA1UdJQQMMAoGCCsGAQUFBwMCMBsGA1UdEQQUMBKCEGNsaWVu
      |dC5vdG8udG9vbHMwHQYDVR0OBBYEFBGA0brjigxzV+DNUrbH7OMAbcMuMB8GA1Ud
      |IwQYMBaAFIl0HOfDzSlY0n0C2MslLI+Rm26pMAoGCCqGSM49BAMCA0cAMEQCIHfn
      |j5VXriWui7B4+H68MAI4eeWH31vdUndREqqoLdCRAiAnWhNSiA+2I3/zOMTabIDy
      |aTto+GtsjgfQZP1wWkzdPg==
      |-----END CERTIFICATE-----""".stripMargin

  private val clientKey =
    """-----BEGIN PRIVATE KEY-----
      |MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgF8ff9JSDJgL6XWry
      |T4jRr+To6zNRZi0SRAiNbKFKLKGhRANCAAS62ktH87q1w7Z1m4u+IEgE5j44smVT
      |dEJX9ZhY+njKOSZbcn3tfdEQIuEQjnyy/epPUEcEsOO7hVTaRXn5D2Cm
      |-----END PRIVATE KEY-----""".stripMargin

  private val leafNotBefore = 1792070037000L
  private val leafNotAfter  = 4945670037000L

  private val from = new DateTime(1700000000000L)
  private val to   = new DateTime(1800000000000L)

  private val format = Cert._fmt

  private val minimal = Cert(
    id = "cert-1",
    name = "cert 1",
    description = "",
    chain = "",
    privateKey = "",
    caRef = None,
    revoked = false,
    from = from,
    to = to
  )

  private val full = Cert(
    id = "cert-full",
    name = "www.oto.tools",
    description = "the leaf certificate",
    chain = leafPem,
    privateKey = leafEncryptedKey,
    caRef = Some("ca-1"),
    domain = "www.oto.tools",
    selfSigned = true,
    ca = true,
    valid = true,
    exposed = true,
    revoked = true,
    autoRenew = true,
    letsEncrypt = true,
    client = true,
    keypair = true,
    subject = "OU=Tests, O=Otoroshi, CN=www.oto.tools",
    from = from,
    to = to,
    sans = Seq("www.oto.tools", "*.oto.tools"),
    entityMetadata = Map("revocationReason" -> "KEY_COMPROMISE", "owner" -> "ops"),
    tags = Seq("prod", "edge"),
    password = Some("secret123"),
    location = customLocation
  )

  private val leaf = Cert(
    id = "leaf",
    name = "leaf",
    description = "",
    chain = leafPem,
    privateKey = leafKey,
    caRef = None,
    revoked = false
  )

  /** the smallest json the format reads */
  private val minimalJson = Json.obj("id" -> "cert-1", "domain" -> "www.oto.tools", "chain" -> "")

  "Cert format" should {

    "write a minimal certificate" in {
      format.writes(minimal) mustBe Json.obj(
        "_loc"        -> defaultLocationJson,
        "id"          -> "cert-1",
        "domain"      -> "--",
        "name"        -> "cert 1",
        "description" -> "",
        "chain"       -> "",
        "caRef"       -> JsNull,
        "privateKey"  -> "",
        "selfSigned"  -> false,
        "ca"          -> false,
        "valid"       -> false,
        "exposed"     -> false,
        "revoked"     -> false,
        "autoRenew"   -> false,
        "letsEncrypt" -> false,
        "subject"     -> "--",
        "from"        -> 1700000000000L,
        "to"          -> 1800000000000L,
        "client"      -> false,
        "keypair"     -> false,
        "sans"        -> Json.arr(),
        "certType"    -> "certificate",
        "password"    -> JsNull,
        "metadata"    -> Json.obj(),
        "tags"        -> Json.arr()
      )
    }

    "write every field of a full certificate" in {
      val json = written(format, full)
      json mustBe Json.obj(
        "_loc"        -> customLocationJson,
        "id"          -> "cert-full",
        "domain"      -> "www.oto.tools",
        "name"        -> "www.oto.tools",
        "description" -> "the leaf certificate",
        "chain"       -> leafPem,
        "caRef"       -> "ca-1",
        "privateKey"  -> leafEncryptedKey,
        "selfSigned"  -> true,
        "ca"          -> true,
        "valid"       -> true,
        "exposed"     -> true,
        "revoked"     -> true,
        "autoRenew"   -> true,
        "letsEncrypt" -> true,
        "subject"     -> "OU=Tests, O=Otoroshi, CN=www.oto.tools",
        "from"        -> 1700000000000L,
        "to"          -> 1800000000000L,
        "client"      -> true,
        "keypair"     -> true,
        "sans"        -> Json.arr("www.oto.tools", "*.oto.tools"),
        "certType"    -> "client",
        "password"    -> "secret123",
        "metadata"    -> Json.obj("revocationReason" -> "KEY_COMPROMISE", "owner" -> "ops"),
        "tags"        -> Json.arr("prod", "edge")
      )
    }

    "write the entity metadata under the metadata key" in {
      val json = written(format, full)
      (json \ "metadata").as[Map[String, String]] mustBe full.entityMetadata
      json.keys must not contain "entityMetadata"
    }

    "round trip a minimal certificate" in {
      roundTrip(format, minimal)
    }

    "round trip a certificate where every field is set" in {
      roundTrip(format, full)
    }

    "round trip a certificate built from a pem chain and its key" in {
      roundTrip(format, leaf.copy(from = from, to = to).enrich())
    }

    "keep each trust and exposure flag on its own through a round trip" in {
      val flags: Seq[(String, Cert => Cert, Cert => Boolean)] = Seq(
        ("ca", _.copy(ca = true), _.ca),
        ("client", _.copy(client = true), _.client),
        ("keypair", _.copy(keypair = true), _.keypair),
        ("exposed", _.copy(exposed = true), _.exposed),
        ("revoked", _.copy(revoked = true), _.revoked),
        ("selfSigned", _.copy(selfSigned = true), _.selfSigned),
        ("valid", _.copy(valid = true), _.valid),
        ("autoRenew", _.copy(autoRenew = true), _.autoRenew),
        ("letsEncrypt", _.copy(letsEncrypt = true), _.letsEncrypt)
      )
      flags.foreach { case (name, set, get) =>
        withClue(s"$name: ") {
          val back = roundTrip(format, set(minimal))
          get(back) mustBe true
          flags.filterNot(_._1 == name).foreach { case (other, _, otherGet) =>
            withClue(s"$other leaked: ")(otherGet(back) mustBe false)
          }
        }
      }
    }

    "keep the order of the subject alternative names and of the tags" in {
      val back = roundTrip(format, minimal.copy(sans = Seq("z.com", "a.com"), tags = Seq("z", "a", "m")))
      back.sans mustBe Seq("z.com", "a.com")
      back.tags mustBe Seq("z", "a", "m")
    }

    "round trip a name, a description and a password with quotes and unicode" in {
      roundTrip(format, minimal.copy(name = "l'équipe \"ops\"", description = "multi\nline", password = Some("pâss\"word")))
    }

    "round trip a password with surrounding spaces as is" in {
      roundTrip(format, minimal.copy(password = Some(" secret "))).password mustBe Some(" secret ")
    }

    "round trip a certificate in a custom location" in {
      val back = roundTrip(format, minimal.copy(location = customLocation))
      back.location mustBe customLocation
      (written(format, back) \ "_loc").as[JsObject] mustBe customLocationJson
    }

    "read a certificate without _loc in the default location" in {
      readOk(format, minimalJson).location mustBe EntityLocation()
    }

    "read a minimal json with the defaults" in {
      val before = DateTime.now()
      val back   = readOk(format, minimalJson)
      val after  = DateTime.now()
      back.id mustBe "cert-1"
      back.domain mustBe "www.oto.tools"
      back.chain mustBe ""
      back.privateKey mustBe ""
      back.caRef mustBe None
      back.password mustBe None
      back.selfSigned mustBe false
      back.ca mustBe false
      back.valid mustBe false
      back.exposed mustBe false
      back.revoked mustBe false
      back.autoRenew mustBe false
      back.letsEncrypt mustBe false
      back.client mustBe false
      back.keypair mustBe false
      back.subject mustBe "--"
      back.sans mustBe empty
      back.entityMetadata mustBe empty
      back.tags mustBe empty
      back.from.isBefore(before) mustBe false
      back.from.isAfter(after) mustBe false
      back.to.isBefore(before) mustBe false
      back.to.isAfter(after) mustBe false
    }

    "name a certificate without name after its domain" in {
      readOk(format, minimalJson).name mustBe "www.oto.tools"
    }

    "describe a certificate without description after its domain" in {
      readOk(format, minimalJson).description mustBe "Certificate for www.oto.tools"
    }

    "prefer the given name and description over the domain" in {
      val back = readOk(format, minimalJson ++ Json.obj("name" -> "my cert", "description" -> "mine"))
      back.name mustBe "my cert"
      back.description mustBe "mine"
    }

    "read a name and a description that are not strings as if they were missing" in {
      val back = readOk(format, minimalJson ++ Json.obj("name" -> 1, "description" -> true))
      back.name mustBe "www.oto.tools"
      back.description mustBe "Certificate for www.oto.tools"
    }

    "read the dates as epoch millis" in {
      val back = readOk(format, minimalJson ++ Json.obj("from" -> 1700000000000L, "to" -> 1800000000000L))
      back.from.getMillis mustBe 1700000000000L
      back.to.getMillis mustBe 1800000000000L
    }

    "read dates given as iso strings as now" in {
      val before = DateTime.now()
      val back   = readOk(format, minimalJson ++ Json.obj("from" -> "2023-11-14T22:13:20Z", "to" -> "2027-01-15T08:00:00Z"))
      back.from.isBefore(before) mustBe false
      back.to.isBefore(before) mustBe false
    }

    "read an empty password as no password" in {
      readOk(format, minimalJson ++ Json.obj("password" -> "")).password mustBe None
    }

    "read a blank password as no password" in {
      readOk(format, minimalJson ++ Json.obj("password" -> "   ")).password mustBe None
    }

    "write an empty password that reads back as no password" in {
      val json = written(format, minimal.copy(password = Some("")))
      (json \ "password").as[String] mustBe ""
      readOk(format, json).password mustBe None
    }

    "read a json null password and ca reference as absent" in {
      val back = readOk(format, minimalJson ++ Json.obj("password" -> JsNull, "caRef" -> JsNull))
      back.password mustBe None
      back.caRef mustBe None
    }

    "read a password and a ca reference that are not strings as absent" in {
      val back = readOk(format, minimalJson ++ Json.obj("password" -> 42, "caRef" -> Json.obj("id" -> "ca-1")))
      back.password mustBe None
      back.caRef mustBe None
    }

    "read a private key that is not a string as an empty key" in {
      readOk(format, minimalJson ++ Json.obj("privateKey" -> 42)).privateKey mustBe ""
    }

    "read flags given as strings as false" in {
      val back = readOk(
        format,
        minimalJson ++ Json.obj("revoked" -> "true", "exposed" -> "true", "ca" -> "true", "client" -> "true")
      )
      back.revoked mustBe false
      back.exposed mustBe false
      back.ca mustBe false
      back.client mustBe false
    }

    "ignore subject alternative names given as a single string" in {
      readOk(format, minimalJson ++ Json.obj("sans" -> "www.oto.tools")).sans mustBe empty
    }

    "ignore metadata holding a non string value" in {
      readOk(format, minimalJson ++ Json.obj("metadata" -> Json.obj("a" -> "b", "n" -> 1))).entityMetadata mustBe empty
    }

    "ignore tags given as a single string" in {
      readOk(format, minimalJson ++ Json.obj("tags" -> "prod")).tags mustBe empty
    }

    "ignore an entityMetadata key" in {
      readOk(format, minimalJson ++ Json.obj("entityMetadata" -> Json.obj("a" -> "b"))).entityMetadata mustBe empty
    }

    "not read the written certificate type back" in {
      val back = readOk(format, minimalJson ++ Json.obj("certType" -> "ca"))
      back.ca mustBe false
      back.certType mustBe "certificate"
    }

    "read the valid flag as given, without checking the pem" in {
      val back = readOk(format, minimalJson ++ Json.obj("valid" -> true))
      back.valid mustBe true
      back.isValid mustBe false
    }

    "not read an empty object" in {
      readError(format, Json.obj())
    }

    "not read a json null" in {
      readError(format, JsNull)
    }

    "not read a json array" in {
      readError(format, Json.arr(minimalJson))
    }

    "not read a certificate without id" in {
      readError(format, minimalJson - "id")
    }

    "not read a certificate without domain" in {
      readError(format, minimalJson - "domain")
    }

    "not read a certificate without chain" in {
      readError(format, minimalJson - "chain")
    }

    "not read an id that is not a string" in {
      readError(format, minimalJson ++ Json.obj("id" -> 42))
    }

    "not read a json null domain" in {
      readError(format, minimalJson ++ Json.obj("domain" -> JsNull))
    }

    "not read a chain that is not a string" in {
      readError(format, minimalJson ++ Json.obj("chain" -> Json.arr(leafPem)))
    }

    "expose its fields through the generic accessors" in {
      hasAccessors(
        full,
        "cert-full",
        "www.oto.tools",
        "the leaf certificate",
        Seq("prod", "edge"),
        Map("revocationReason" -> "KEY_COMPROMISE", "owner" -> "ops")
      )
    }

    "expose through json what the format writes" in {
      entityJsonMatchesFormat(format, full)
      full.toJson mustBe format.writes(full)
      Cert.toJson(full) mustBe format.writes(full)
    }

    "be built from its json" in {
      Cert.fromJsons(full.json) mustBe full
      Cert.fromJsonSafe(full.json) mustBe JsSuccess(full)
    }

    "fail to be built from a json without id" in {
      a[Throwable] must be thrownBy Cert.fromJsons(minimalJson - "id")
      Cert.fromJsonSafe(minimalJson - "id").isError mustBe true
    }
  }

  "Cert type" should {

    "be a plain certificate by default" in {
      minimal.certType mustBe "certificate"
    }

    "be named after its kind" in {
      minimal.copy(client = true).certType mustBe "client"
      minimal.copy(ca = true).certType mustBe "ca"
      minimal.copy(letsEncrypt = true).certType mustBe "letsEncrypt"
      minimal.copy(keypair = true).certType mustBe "keypair"
      minimal.copy(selfSigned = true).certType mustBe "selfSigned"
    }

    "prefer client over ca over let's encrypt over keypair over self signed" in {
      val all = minimal.copy(client = true, ca = true, letsEncrypt = true, keypair = true, selfSigned = true)
      all.certType mustBe "client"
      all.copy(client = false).certType mustBe "ca"
      all.copy(client = false, ca = false).certType mustBe "letsEncrypt"
      all.copy(client = false, ca = false, letsEncrypt = false).certType mustBe "keypair"
      all.copy(client = false, ca = false, letsEncrypt = false, keypair = false).certType mustBe "selfSigned"
    }
  }

  "Cert pem helpers" should {

    "clean a chain from the text and blank lines around the certificates" in {
      leaf.copy(chain = s"subject=CN=www.oto.tools\n\n$leafPem\n\n\n").cleanChain mustBe leafPem
    }

    "clean a chain without certificate into an empty string" in {
      minimal.copy(chain = "not a pem").cleanChain mustBe ""
    }

    "read the certificates of a chain in order" in {
      val chain = leaf.copy(chain = s"$clientPem\n$caPem")
      chain.certificatesChain.map(_.getSerialNumber.intValue).toSeq mustBe Seq(77, 1)
      chain.certificates.map(_.getSerialNumber.intValue) mustBe Seq(77, 1)
      chain.certificatesRaw.size mustBe 2
    }

    "read the first certificate of a chain and its last one as the ca" in {
      val chain = leaf.copy(chain = s"$clientPem\n$caPem")
      chain.certificate.map(_.getSerialNumber.intValue) mustBe Some(77)
      chain.caFromChain.map(_.getSerialNumber.intValue) mustBe Some(1)
    }

    "read nothing from a chain that is not a pem" in {
      val broken = minimal.copy(chain = "not a pem")
      broken.certificate mustBe None
      broken.caFromChain mustBe None
      broken.certificatesChain mustBe empty
      broken.metadata mustBe None
    }

    "describe a self signed leaf certificate from its pem" in {
      val meta = leaf.metadata.get
      (meta \ "domain").as[String] mustBe "www.oto.tools"
      (meta \ "subAltNames").as[Seq[String]] mustBe Seq("www.oto.tools", "*.oto.tools")
      (meta \ "subjectDN").as[String] mustBe "OU=Tests, O=Otoroshi, CN=www.oto.tools"
      (meta \ "issuerDN").as[String] mustBe "OU=Tests, O=Otoroshi, CN=www.oto.tools"
      (meta \ "selfSigned").as[Boolean] mustBe true
      (meta \ "ca").as[Boolean] mustBe false
      (meta \ "client").as[Boolean] mustBe false
      (meta \ "notBefore").as[Long] mustBe leafNotBefore
      (meta \ "notAfter").as[Long] mustBe leafNotAfter
    }

    "describe a client certificate from its pem" in {
      val meta = leaf.copy(chain = clientPem, privateKey = clientKey).metadata.get
      (meta \ "domain").as[String] mustBe "client.oto.tools"
      (meta \ "issuerDN").as[String] mustBe "O=Otoroshi, CN=Test Root CA"
      (meta \ "selfSigned").as[Boolean] mustBe false
      (meta \ "ca").as[Boolean] mustBe false
      (meta \ "client").as[Boolean] mustBe true
    }

    "describe a ca from its pem" in {
      val meta = leaf.copy(chain = caPem, privateKey = caKey).metadata.get
      (meta \ "selfSigned").as[Boolean] mustBe true
      (meta \ "ca").as[Boolean] mustBe true
      (meta \ "client").as[Boolean] mustBe false
    }

    "expose the serial number and the signature of its pem" in {
      leaf.serialNumber mustBe Some("1092")
      leaf.serialNumberLng mustBe Some(java.math.BigInteger.valueOf(4242))
      leaf.signature.get must fullyMatch regex "([0-9A-F]{2}:){31}[0-9A-F]{2}"
      minimal.serialNumber mustBe None
      minimal.signature mustBe None
    }

    "be valid with its private key" in {
      leaf.isValid mustBe true
    }

    "not be valid without private key" in {
      leaf.copy(privateKey = "").isValid mustBe false
    }

    "not be valid without certificate" in {
      leaf.copy(chain = "").isValid mustBe false
    }

    "be valid with an encrypted private key and its password" in {
      leaf.copy(privateKey = leafEncryptedKey, password = Some("secret123")).isValid mustBe true
    }

    "not be valid with an encrypted private key and no password" in {
      leaf.copy(privateKey = leafEncryptedKey, password = None).isValid mustBe false
    }

    "stay valid with an encrypted private key after a round trip" in {
      val back = roundTrip(format, leaf.copy(privateKey = leafEncryptedKey, password = Some("secret123"), from = from, to = to))
      back.isValid mustBe true
    }

    "not be valid with an unencrypted private key and an empty password, unlike after a round trip" in {
      val cert = leaf.copy(password = Some(""), from = from, to = to)
      cert.isValid mustBe false
      readOk(format, format.writes(cert)).isValid mustBe true
    }

    "be valid with a private key that does not match the certificate" in {
      leaf.copy(privateKey = caKey).isValid mustBe true
    }

    "build its key pair from the certificate and the private key" in {
      val pair = leaf.cryptoKeyPair
      pair.getPublic mustBe leaf.certificate.get.getPublicKey
      pair.getPrivate must not be null
    }

    "derive its pem fields when enriched" in {
      val enriched = leaf.enrich()
      enriched.domain mustBe "www.oto.tools"
      enriched.subject mustBe "OU=Tests, O=Otoroshi, CN=www.oto.tools"
      enriched.sans mustBe Seq("www.oto.tools", "*.oto.tools")
      enriched.selfSigned mustBe true
      enriched.ca mustBe false
      enriched.valid mustBe true
      enriched.from.getMillis mustBe leafNotBefore
      enriched.to.getMillis mustBe leafNotAfter
    }

    "keep the fields the pem does not tell when enriched" in {
      val custom   = full.copy(chain = leafPem, privateKey = leafKey, password = None)
      val enriched = custom.enrich()
      enriched.id mustBe custom.id
      enriched.name mustBe custom.name
      enriched.description mustBe custom.description
      enriched.caRef mustBe custom.caRef
      enriched.client mustBe true
      enriched.keypair mustBe true
      enriched.exposed mustBe true
      enriched.revoked mustBe true
      enriched.autoRenew mustBe true
      enriched.letsEncrypt mustBe true
      enriched.entityMetadata mustBe custom.entityMetadata
      enriched.tags mustBe custom.tags
      enriched.location mustBe customLocation
    }

    "drop a ca flag the pem does not carry when enriched" in {
      leaf.copy(ca = true).enrich().ca mustBe false
      leaf.copy(chain = caPem, privateKey = caKey).enrich().ca mustBe true
    }

    "not derive the client flag from the pem when enriched" in {
      leaf.copy(chain = clientPem, privateKey = clientKey).enrich().client mustBe false
    }

    "fail to be enriched without certificate" in {
      a[Throwable] must be thrownBy minimal.enrich()
    }

    "serve the domains of its pem" in {
      leaf.allDomains mustBe Seq("www.oto.tools", "*.oto.tools")
    }

    "serve its domain and sans when it has no pem, without placeholder, blank or duplicated domain" in {
      minimal.copy(domain = "--", sans = Seq("a.com", " ", "b.com", "a.com")).allDomains mustBe Seq("a.com", "b.com")
      minimal.copy(domain = "main.com", sans = Seq("a.com")).allDomains mustBe Seq("main.com", "a.com")
    }

    "match its exact domains and one level under its wildcard" in {
      leaf.matchesDomain("www.oto.tools") mustBe true
      leaf.matchesDomain("api.oto.tools") mustBe true
    }

    "not match two levels under its wildcard, the wildcard parent nor a lookalike" in {
      leaf.matchesDomain("a.b.oto.tools") mustBe false
      leaf.matchesDomain("oto.tools") mustBe false
      leaf.matchesDomain("www.oto.tools.evil.com") mustBe false
      leaf.matchesDomain("api.notoroshi-oto.tools") mustBe false
    }

    "match a san without wildcard only exactly" in {
      minimal.sanMatchesDomain("www.oto.tools", "www.oto.tools") mustBe true
      minimal.sanMatchesDomain("WWW.oto.tools", "www.oto.tools") mustBe false
      minimal.sanMatchesDomain("api.oto.tools", "www.oto.tools") mustBe false
    }

    "not be expired strictly between its dates" in {
      minimal.notExpiredAt(from.plusDays(1)) mustBe true
      minimal.notExpiredAt(from) mustBe false
      minimal.notExpiredAt(to) mustBe false
      minimal.notExpiredAt(to.plusDays(1)) mustBe false
    }

    "tell whether it is expired now" in {
      val now     = DateTime.now()
      val current = minimal.copy(from = now.minusDays(1), to = now.plusDays(1))
      current.notExpired mustBe true
      current.expired mustBe false
      val past    = minimal.copy(from = now.minusDays(2), to = now.minusDays(1))
      past.notExpired mustBe false
      past.expired mustBe true
    }

    "not expire soon when it lives for a long time" in {
      val now = DateTime.now()
      minimal.copy(from = now.minusDays(1), to = now.plusDays(365)).notExpiredSoon mustBe true
    }

    "be revoked or not" in {
      minimal.notRevoked mustBe true
      minimal.copy(revoked = true).notRevoked mustBe false
    }

    "be usable only when valid, not revoked and not expired" in {
      val now    = DateTime.now()
      val usable = leaf.copy(from = now.minusDays(1), to = now.plusDays(1))
      usable.isUsable mustBe true
      usable.copy(revoked = true).isUsable mustBe false
      usable.copy(to = now.minusHours(1)).isUsable mustBe false
      usable.copy(privateKey = "").isUsable mustBe false
    }

    "hash its content from its chain and its private key only" in {
      leaf.contentHash mustBe leaf.copy(id = "other", name = "other", exposed = true).contentHash
      leaf.contentHash must not be leaf.copy(privateKey = caKey).contentHash
      leaf.contentHash must not be leaf.copy(chain = caPem).contentHash
      leaf.contentHash must fullyMatch regex "[0-9a-f]{64}"
    }

    "key its cache entries with its id and its content hash" in {
      leaf.cacheKey mustBe s"leaf###${leaf.contentHash}"
    }

    "bundle its private key and its chain" in {
      leaf.bundle mustBe s"$leafKey\n\n$leafPem\n"
    }

    "be built enriched, not exposed, not revoked and not client from a pem chain and its key" in {
      val cert = Cert("my leaf", leafPem, leafKey)
      cert.id.length mustBe 32
      cert.name mustBe "my leaf"
      cert.description mustBe "my leaf"
      cert.domain mustBe "www.oto.tools"
      cert.valid mustBe true
      cert.exposed mustBe false
      cert.revoked mustBe false
      cert.client mustBe false
      cert.autoRenew mustBe false
      cert.caRef mustBe None
    }
  }
}
