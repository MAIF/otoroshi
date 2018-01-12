# Managing sessions

With Otoroshi you can manage sessions of connected users and you can discard sessions whenever you want. Session last 24h by default and you can customize them with `app.backoffice.session.exp` and `app.privateapps.session.exp` @ref:[config keys](../firstrun/configfile.md)

## Admin. sessions

To see last current admin session on Otoroshi from the UI, go to `settings (cog icon) / Admins sessions`. Here you can discard individual sessions or all sessions at once.

@@@ div { .centered-img }
<img src="../img/admin-sessions.png" />
@@@

## Private apps. session

To see last current admin session on Otoroshi from the UI, go to `settings (cog icon) / Priv. apps sessions`. Here you can discard individual sessions or all sessions at once.

@@@ div { .centered-img }
<img src="../img/private-sessions.png" />
@@@
