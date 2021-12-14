# Setup Otoroshi

Now that Otoroshi is running, you are ready to log into the Otoroshi admin dashboard and setup your instance. Just go to :

<a href="http://otoroshi.oto.tools:8080" target="_blank">http://otoroshi.oto.tools:8080</a>

and you will see the login page

@@@ div { .centered-img }
<img src="../img/login-page.png" />
@@@

@@@ warning
Use the credentials generated in Otoroshi **logs** during **first run**.
@@@

@@@ div { .centered-img #first-login-example }
<img src="../img/first-login.gif" />
@@@

(of course, you can change this url dependending on the configuration you provided to Otoroshi).

Once logged in, the first screen you'll see should look like :

@@@ div { .centered-img #first-login }
<img src="../img/first-login.png" />
@@@

As you can see, Otoroshi is not really happy about you being logged with a generated admin account.

But we will fix that in the next chapter

@@@ index

* [create admins](./admin.md)
* [configure danger zone](./dangerzone.md)

@@@
