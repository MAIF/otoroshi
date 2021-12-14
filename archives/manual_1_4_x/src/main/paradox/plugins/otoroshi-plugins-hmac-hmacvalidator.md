
@@@ div { .plugin .plugin-hidden .plugin-kind-validator }

# HMAC access validator

<img class="plugin-logo plugin-hidden" src=""></img>

## Infos

* plugin type: `validator`
* configuration root: `HMACAccessValidator`

## Description

This plugin can be used to check if a HMAC signature is present and valid in Authorization header.



## Default configuration

```json
{
  "HMACAccessValidator" : {
    "secret" : ""
  }
}
```



## Documentation


 The HMAC signature needs to be set on the `Authorization` or `Proxy-Authorization` header.
 The format of this header should be : `hmac algorithm="<ALGORITHM>", headers="<HEADER>", signature="<SIGNATURE>"`
 As example, a simple nodeJS call with the expected header
 ```js
 const crypto = require('crypto');
 const fetch = require('node-fetch');

 const date = new Date()
 const secret = "my-secret" // equal to the api key secret by default

 const algo = "sha512"
 const signature = crypto.createHmac(algo, secret)
    .update(date.getTime().toString())
    .digest('base64');

 fetch('http://myservice.oto.tools:9999/api/test', {
    headers: {
        "Otoroshi-Client-Id": "my-id",
        "Otoroshi-Client-Secret": "my-secret",
        "Date": date.getTime().toString(),
        "Authorization": `hmac algorithm="hmac-${algo}", headers="Date", signature="${signature}"`,
        "Accept": "application/json"
    }
 })
    .then(r => r.json())
    .then(console.log)
 ```
 In this example, we have an Otoroshi service deployed on http://myservice.oto.tools:9999/api/test, protected by api keys.
 The secret used is the secret of the api key (by default, but you can change it and define a secret on the plugin configuration).
 We send the base64 encoded date of the day, signed by the secret, in the Authorization header. We specify the headers signed and the type of algorithm used.
 You can sign more than one header but you have to list them in the headers fields (each one separate by a space, example : headers="Date KeyId").
 The algorithm used can be HMAC-SHA1, HMAC-SHA256, HMAC-SHA384 or HMAC-SHA512.




@@@

