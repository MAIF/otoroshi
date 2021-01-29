# Manage admin users

## Create admin user after the first run

Click on the `Create an admin user` warning popup, or go to `settings (cog icon) / Admins`.
You will see the list of registered admin users.
Click on `Register admin.`

Now, enter informations (label, login and password) about the new admin you want to create.

Click on `Register Admin`.
Now, you can discard the generated admin, confirm, then logout, login with the admin user you have just created and the danger popup will go away

## Edit Admins rights
New `admins` are created in default organization with an access to all teams. 
To update organization or team, just click on the edit user button of the `admin` and modify `Rights`.
`Rights` is composing by 2 properties, `tenant` and `team`. Both can have multiple string values composed like"team/organization:right" (ex: "default:rw")
Organization or team value can be `*`to match all organizations or teams.
Rights can be:
- r for Read only
- w for Write only
- rw from Read/Write

## Create admin user with U2F device login

Go to `settings (cog icon) / Admins`, click on `Register Admin`.

Enter informations about the new admin you want to create.

Click on `Register Admin with WebAuthn`.

Otoroshi will  ask you to plug your FIDO U2F device and touch it to complete registration.

@@@ div { .centered-img }
<img src="https://images-na.ssl-images-amazon.com/images/I/61hwQNWpSEL._SY542_.jpg" width="200" />
@@@

@@@ warning
To be able to use FIDO U2F devices, Otoroshi must be served over https
@@@

## Discard admin user

Go to `settings (cog icon) / Admins`, at the bottom of the page, you will see a list of admin users that you can discard. Just click on the `Discard User` button on the right side of the row and confirm that you actually want to discard an admin user.

## Admin sessions management

Go to `settings (cog icon) / Admins sessions`, you will see a list of active admin user sessions

You can either discard single sessions one by one using the `Discard Session` on each targeted row of the list or discard all active sessions using the `Discard all sessions` button at the top of the page.
