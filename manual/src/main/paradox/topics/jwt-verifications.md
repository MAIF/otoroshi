# JWT Tokens verification

Sometimes, it can be pretty useful to verify Jwt tokens coming from other provider on some services. Otoroshi provides a tool to do that per service. In the Service descriptor page, you can find a `Jwt token Verification` section enable and disable jwt token verification and activate strict mode. In strict mode, requestsv will be rejected if the jwt token is not found.

## Service descriptor local verifications

@@@ div { .centered-img }
<img src="../img/jwt-verif-capture.png" />
@@@

in this section you can 

### Just verify signature and fields value

@@@ div { .centered-img }
<img src="../img/jwt-verif-verify.png" />
@@@

### Re-sign the token

@@@ div { .centered-img }
<img src="../img/jwt-verif-resign.png" />
@@@

### Transform the token

@@@ div { .centered-img }
<img src="../img/jwt-verif-transform.png" />
@@@

## Global verifications

You can also create global jwt verifiers and reference them in your services (from the `Type` selector). To create a global verifier, go to `Settings / Global Jwt Verifiers` and it will display the list of global verifiers.

@@@ div { .centered-img }
<img src="../img/jwt-verif-global-verifiers.png" />
@@@

you can them create, edit or delete verifiers

@@@ div { .centered-img }
<img src="../img/jwt-verif-global-verifier.png" />
@@@

