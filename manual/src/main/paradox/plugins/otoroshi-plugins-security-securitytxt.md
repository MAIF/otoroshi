
# Security Txt

## Infos

* plugin type: `transformer`
* configuration root: `SecurityTxt`

## Description

This plugin exposes a special route `/.well-known/security.txt` as proposed at [https://securitytxt.org/](https://securitytxt.org/).

This plugin can accept the following configuration

```json
{
  "SecurityTxt": {
    "Contact": "contact@foo.bar", // mandatory, a link or e-mail address for people to contact you about security issues
    "Encryption": "http://url-to-public-key", // optional, a link to a key which security researchers should use to securely talk to you
    "Acknowledgments": "http://url", // optional, a link to a web page where you say thank you to security researchers who have helped you
    "Preferred-Languages": "en, fr, es", // optional
    "Policy": "http://url", // optional, a link to a policy detailing what security researchers should do when searching for or reporting security issues
    "Hiring": "http://url", // optional, a link to any security-related job openings in your organisation
  }
}
```



## Default configuration

```json
{
  "SecurityTxt" : {
    "Contact" : "contact@foo.bar",
    "Encryption" : "https://...",
    "Acknowledgments" : "https://...",
    "Preferred-Languages" : "en, fr",
    "Policy" : "https://...",
    "Hiring" : "https://..."
  }
}
```




