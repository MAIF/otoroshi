# Scripts

Script are a way to create plugins for otoroshi without deploying them as jar files. With scripts, you just have to store the scala code of your plugins inside the otoroshi datastore and otoroshi will compile and deploy them at startup. You can find all your scripts in the UI at `cog icon / Plugins`. You can find all the documentation about plugins @ref:[here](../plugins/index.md)

@@@ warning
The compilation of your plugins can be pretty long and resources consuming. As the compilation happens during otoroshi boot sequence, your instance will be blocked until all plugins have compiled. This behavior can be disabled. If so, the plugins will not work until they have been compiled. Any service using a plugin that is not compiled yet will fail
@@@

Like any entity, the script has has the following properties

* `id`
* `plugin name`
* `plugin description`
* `tags`
* `metadata`

And you also have

* `type`: the kind of plugin you are building with this script
* `plugin code`: the code for your plugin

## Compile

You can use the compile button to check if the code you write in `plugin code` is valid. It will automatically save your script and try to compile. As mentionned earlier, script compilation is quite resource intensive. It will affect your CPU load and your memory consumption. Don't forget to adjust your VM settings accordingly.
