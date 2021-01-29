# JWT Tokens verification

Sometimes, it can be pretty useful to verify Jwt tokens coming from other provider on some services. Otoroshi provides a tool to do that per service. In the Service descriptor page, you can find a `Jwt token Verification` section dedicated to this topic.

## Service descriptor local verifications
<!-- TODO: UI has been probably updated -->
@@@ div { .centered-img }
<img src="../img/jwt-verif-capture.png" />
@@@

in this section you can select the type of verification you can choose if the verifier is local to the `Service descriptor` or reference a global one.

You can also enabled/disable jwt verification and activate strict mode. In strict mode, requests will be rejected if the jwt token is not found.

### Jwt token location

You can use the `Source` selector to specify where the Jwt token can be found. 

* in a query string param

@@@ div { .centered-img }
<img src="../img/jwt-verif-inquery.png" />
@@@

* in a header

@@@ div { .centered-img }
<img src="../img/jwt-verif-inheader.png" />
@@@

* in a cookie

@@@ div { .centered-img }
<img src="../img/jwt-verif-incookie.png" />
@@@

### Jwt signing

You can use the `Algo.` selector to specify the signing algorithm to use to verifiy the token

@@@ div { .centered-img }
<img src="../img/jwt-verif-signing-1.png" />
@@@

you can choose between

* Hmac + SHA256
* Hmac + SHA384
* Hmac + SHA512
* RSA + SHA256
* RSA + SHA384
* RSA + SHA512
* Elliptic Curve + SHA256
* Elliptic Curve + SHA384
* Elliptic Curve + SHA512

@@@ div { .centered-img }
<img src="../img/jwt-verif-signing-2.png" />
@@@

You can use syntax like `${env.MY_ENV_VAR}` or `${config.my.config.path}` to provide secret/keys values. 


### Just verify signature and fields value

Using the `Verif. strategy` selector, you can choose `Verify jwt token`. This will verify if the token is signed using the settings from `jwt signing` section and the value of the fields provided in `Verify token fields`. Then the token will be send to the target just like that.

@@@ div { .centered-img }
<img src="../img/jwt-verif-verify.png" />
@@@

### Re-sign the token

Using the `Verif. strategy` selector, you can choose `Verify and re-sign jwt token`. This will verify if the token is signed using the settings from `jwt signing` section and the value of the fields provided in `Verify token fields`. Then the token will be re-signed using the settings provided in `Re-sign algo` and will be send to the target.

@@@ div { .centered-img }
<img src="../img/jwt-verif-resign.png" />
@@@

### Transform the token

Using the `Verif. strategy` selector, you can choose `Verify, re-sign and transform jwt token`. This will verify if the token is signed using the settings from `jwt signing` section and the value of the fields provided in `Verify token fields`. Then the token will be re-signed using the settings provided in `Re-sign algo`. You can also change the location of the token using `Token location`, remove fields using `Remove token fields`, set fields value using `Set token fields` and even rename fields using `Rename token fields`.

@@@ div { .centered-img }
<img src="../img/jwt-verif-transform.png" />
@@@

You can also use a mini expression language in `Set token fields`. You just have to add expressions in values like `${expression}`. Supported expressions are the following :

* `${date}` => set the current date
* `${date.format('dd/MM/yyyy')}` => set the current date formatted with the format you want
* `${token.fieldName}` => get the value of the field named `fieldName`
* `${token.fieldName.replace('a', 'b')}` => get the value of the field named `fieldName` and replace `a` with `b`
* `${token.fieldName.replaceAll('[0-9]', '-')}` => get the value of the field named `fieldName` and replace digits with `-`

you can of course use multiple expressions in one field like `my-value-is-${date}-with${token.user}`

## Global verifications

You can  create global jwt verifiers and reference them in your services (from the `Type` selector). When you set the type of verification to `Reference to a global definition`, you can choose an existing global jwt verifier

<!-- FIXME: undefined screen capture -->
@@@ div { .centered-img }
<img src="../img/jwt-verif-global-ref.png" />
@@@

To create a global verifier, go to `Settings (cog icon) / Global Jwt Verifiers` and it will display the list of global verifiers.

@@@ div { .centered-img }
<img src="../img/jwt-verif-global-verifiers.png" />
@@@

you can them create, edit or delete verifiers

@@@ div { .centered-img }
<img src="../img/jwt-verif-global-verifier.png" />
@@@

