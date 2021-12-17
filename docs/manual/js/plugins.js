document.addEventListener("DOMContentLoaded", function(){
    const pluginNames = [
      "transformer",
      "validator",
      "preroute",
      "sink",
      "job",
      "listener",
      "exporter",
      "request-handler",
      "composite"
    ];

    const plugins = [...document.getElementsByClassName("plugin")];

    if(plugins.length > 0) {
      const filters = document.createElement("div");
      filters.classList.add("filters");

      pluginNames.forEach(name => {
        const pluginsOfKind = plugins.filter(p => p.classList.contains(`plugin-kind-${name}`)).length;

        if(pluginsOfKind > 0) {
          const button = document.createElement('button');
          button.classList.add("filter");

          if(pluginsOfKind > 10)
            button.classList.add("filter-selected")

          button.id = name;
          button.addEventListener('click', e => {
            if(e.target.classList.contains("filter-selected")) {
              e.target.classList.remove("filter-selected");
              plugins.forEach(plugin => {
                if (plugin.classList.contains(`plugin-kind-${name}`))
                  plugin.classList.add("filtered");
              });
            }
            else {
              e.target.classList.add("filter-selected")
              plugins.forEach(plugin => {
                if (plugin.classList.contains(`plugin-kind-${name}`))
                  plugin.classList.remove("filtered");
              });
            }
          })
          button.textContent = `${name} (${pluginsOfKind})`;

          filters.append(button);
        }
      });

      plugins[0].parentNode.insertBefore(filters, plugins[0]);
    }

    plugins.forEach((plugin, i) => {
        [...plugin.children].slice(4).forEach(child => child.classList.add("plugin-hidden"))

        const title = [...plugin.children][0];
        [...title.getElementsByTagName("a")]
          .forEach(link => link.remove())

        pluginNames.forEach(name => {
          if(plugin.classList.contains(`plugin-kind-${name}`)) {
            const badge = document.createElement("span")
            badge.classList.add("plugin-badge");
            badge.textContent = name;
            plugin.appendChild(badge);
          }
        });

        plugin.classList.remove("plugin-hidden");

        plugin.addEventListener("click", e => {          
          const children = e.target.children;

          for(let j=0; j<plugins.length; j++) {
            if(i !== j && !plugins[j].children[4].classList.contains("plugin-hidden")) {
              [...plugins[j].children].slice(4, -1).forEach(child => child.classList.add("plugin-hidden"))
              plugins[j].classList.remove("plugin-open")
            }
          }

          if(children.length >= 4) {
            if(e.target.children[4].classList.contains("plugin-hidden")) {
              [...plugin.children].slice(4).forEach(child => child.classList.remove("plugin-hidden"))
              plugin.classList.add("plugin-open")
              plugin.scrollIntoView({ behavior: 'smooth'})
            }
            else {
              [...plugin.children].slice(4, -1).forEach(child => child.classList.add("plugin-hidden"))
              plugin.classList.remove("plugin-open")
            }
          }
        });
    });

    [...document.getElementsByClassName("nav-next")].forEach(nav => {
      nav.style.width = '100%'
      nav.style.float = 'left'
    })
  });