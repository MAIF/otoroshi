# Import and export

With Otoroshi you can easily save the current state of the proxy and restore it later. Go to `settings (cog icon) / Danger Zone` and scroll to the bottom of the page

## Full export

Click on the full export button

@@@ div { .centered-img }
<img src="../img/full-export-1.png" />
@@@

and your browser will start to download a JSON file containing the internal state of your Otoroshi cluster.

@@@ div { .centered-img }
<img src="../img/full-export-2.png" />
@@@

## Full import

If you want to restore an export, go to `settings (cog icon) / Danger Zone` and scroll to the bottom of the page.  Click on the `Recover from full export file` button

@@@ div { .centered-img }
<img src="../img/full-import-1.png" />
@@@

Choose an existing export file on your system

@@@ div { .centered-img }
<img src="../img/full-import-1-bis.png" />
@@@

then click on the `Flush datastore and import ...` button, confirm and you will be logged out

@@@ div { .centered-img }
<img src="../img/full-import-2.png" />
@@@
