
# Allowed users only

## Infos

* plugin type: `validator`
* configuration root: `HasAllowedUsersValidator`

## Description

This plugin only let allowed users pass

This plugin can accept the following configuration

```json
{
  "HasAllowedUsersValidator": {
    "usernames": [],   // allowed usernames
    "emails": [],      // allowed user email addresses
    "emailDomains": [], // allowed user email domains
    "metadataMatch": [], // json path expressions to match against user metadata. passes if one match
    "metadataNotMatch": [], // json path expressions to match against user metadata. passes if none match
    "profileMatch": [], // json path expressions to match against user profile. passes if one match
    "profileNotMatch": [], // json path expressions to match against user profile. passes if none match
  }
}
```



## Default configuration

```json
{
  "HasAllowedUsersValidator" : {
    "usernames" : [ ],
    "emails" : [ ],
    "emailDomains" : [ ],
    "metadataMatch" : [ ],
    "metadataNotMatch" : [ ],
    "profileMatch" : [ ],
    "profileNotMatch" : [ ]
  }
}
```





