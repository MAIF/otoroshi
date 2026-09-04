package functional

import org.opensaml.core.config.InitializationService
import org.opensaml.core.xml.XMLObject
import org.opensaml.core.xml.config.XMLObjectProviderRegistrySupport
import org.opensaml.saml.saml2.core.*
import org.scalatest.OptionValues
import otoroshi.auth.ValidatorUtils

import javax.xml.namespace.QName

// pure logic over opensaml objects: no otoroshi instance, no network. every case here comes from a
// security review of the saml response validation
class SamlValidatorSpec
    extends org.scalatest.wordspec.AnyWordSpec
    with org.scalatest.matchers.must.Matchers
    with OptionValues {

  InitializationService.initialize()

  val expectedIssuer = "https://idp.oto.tools/metadata"
  val otherIssuer    = "https://attacker.oto.tools/metadata"

  def build[T <: XMLObject](qname: QName): T =
    XMLObjectProviderRegistrySupport.getBuilderFactory
      .getBuilder(qname)
      .buildObject(qname)
      .asInstanceOf[T]

  def issuer(value: String): Issuer = {
    val i = build[Issuer](Issuer.DEFAULT_ELEMENT_NAME)
    i.setValue(value)
    i
  }

  def nameId(value: String): NameID = {
    val n = build[NameID](NameID.DEFAULT_ELEMENT_NAME)
    n.setValue(value)
    n
  }

  def status(code: String): Status = {
    val statusCode = build[StatusCode](StatusCode.DEFAULT_ELEMENT_NAME)
    statusCode.setValue(code)
    val s          = build[Status](Status.DEFAULT_ELEMENT_NAME)
    s.setStatusCode(statusCode)
    s
  }

  def assertion(issuerValue: String, subjectNameId: String): Assertion = {
    val subject = build[Subject](Subject.DEFAULT_ELEMENT_NAME)
    subject.setNameID(nameId(subjectNameId))
    val a       = build[Assertion](Assertion.DEFAULT_ELEMENT_NAME)
    a.setIssuer(issuer(issuerValue))
    a.setSubject(subject)
    a
  }

  def response(
      issuerValue: String,
      statusCode: String = StatusCode.SUCCESS,
      assertions: Seq[Assertion] = Seq.empty
  ): Response = {
    val r = build[Response](Response.DEFAULT_ELEMENT_NAME)
    r.setIssuer(issuer(issuerValue))
    r.setStatus(status(statusCode))
    assertions.foreach(a => r.getAssertions.add(a))
    r
  }

  def logoutRequest(issuerValue: String, subjectNameId: String): LogoutRequest = {
    val lr = build[LogoutRequest](LogoutRequest.DEFAULT_ELEMENT_NAME)
    lr.setIssuer(issuer(issuerValue))
    lr.setNameID(nameId(subjectNameId))
    lr
  }

  "SAML validation" should {

    "reject a response coming from an unexpected issuer" in {
      ValidatorUtils.validateResponse(response(otherIssuer), expectedIssuer).isLeft mustBe true
    }

    "accept a response coming from the expected issuer" in {
      ValidatorUtils.validateResponse(response(expectedIssuer), expectedIssuer).isRight mustBe true
    }

    "reject a response with a non success status" in {
      ValidatorUtils.validateResponse(response(expectedIssuer, StatusCode.REQUESTER), expectedIssuer).isLeft mustBe true
    }

    "reject a logout request coming from an unexpected issuer" in {
      ValidatorUtils.validateLogoutRequest(logoutRequest(otherIssuer, "user"), expectedIssuer, "user").isLeft mustBe true
    }

    "accept a logout request coming from the expected issuer" in {
      ValidatorUtils
        .validateLogoutRequest(logoutRequest(expectedIssuer, "user"), expectedIssuer, "user")
        .isRight mustBe true
    }

    "reject an unsigned response when signature validation is on" in {
      ValidatorUtils.validateSignature(response(expectedIssuer), List.empty, validateSign = true).isLeft mustBe true
    }

    "accept an unsigned response when signature validation is off" in {
      ValidatorUtils.validateSignature(response(expectedIssuer), List.empty, validateSign = false).isRight mustBe true
    }

    "reject a response without any assertion" in {
      ValidatorUtils
        .validateAssertion(response(expectedIssuer), expectedIssuer, List.empty, validateAssertions = false)
        .isLeft mustBe true
    }

    "reject a response with more than one assertion" in {
      val resp = response(
        expectedIssuer,
        assertions = Seq(assertion(expectedIssuer, "user"), assertion(expectedIssuer, "user"))
      )
      ValidatorUtils.validateAssertion(resp, expectedIssuer, List.empty, validateAssertions = false).isLeft mustBe true
    }

    "reject an assertion coming from an unexpected issuer" in {
      val resp = response(expectedIssuer, assertions = Seq(assertion(otherIssuer, "user")))
      ValidatorUtils.validateAssertion(resp, expectedIssuer, List.empty, validateAssertions = false).isLeft mustBe true
    }

    "accept a single assertion from the expected issuer when assertion validation is off" in {
      val resp = response(expectedIssuer, assertions = Seq(assertion(expectedIssuer, "user")))
      ValidatorUtils.validateAssertion(resp, expectedIssuer, List.empty, validateAssertions = false).isRight mustBe true
    }
  }
}
