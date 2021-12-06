# Sessions management

## Admins

All logged users to an Otoroshi instance are administrators. An user session is created for each sucessfull connection to the UI. 

These sessions are listed in the `Admin users sessions` (available at this location of your instance */bo/dashboard/sessions/admin*).

An admin user session is composed of: 

* `name`: the name of the connected user
* `email`: the unique email
* `Created at`: the creation date of the user session
* `Expires at`: date until the user session is drop
* `Profile`: user profile, at JSON format, containing name, email and others linked metadatas
* `Rights`: list of rules to authorize the connected user on each tenant and teams.
* `Discard session`: action to kill a session. On click, a modal will appear with the session ID

In the `Admin users sessions` page, you have two more actions:

* `Discard all sessions`: kills all current sessions (including the session of the owner of this action)
* `Discard old sessions`: kill all outdated sessions

## Private apps

All logged users to a protected application has an private user session.

These sessions are listed in the `Private apps users sessions` (available at this location of your instance */bo/dashboard/sessions/private*).

An private user session is composed of: 

* `name`: the name of the connected user
* `email`: the unique email
* `Created at`: the creation date of the user session
* `Expires at`: date until the user session is drop
* `Profile`: user profile, at JSON format, containing name, email and others linked metadatas
* `Meta.`: list of metadatas added by the authentication module.
* `Tokens`: list of tokens received from the identity provider used. In the case of a memory authentication, this part will keep empty.
* `Discard session`: action to kill a session. On click, a modal will appear with the session ID
