document.addEventListener("DOMContentLoaded", function () {

  if (document.getElementById("plugins-container")) {
    const div = document.getElementById("plugins-container");

    [...document.getElementsByClassName("ng-plugin")]
      .forEach(node => {
        div.appendChild(node)
      });

    const plugins = [...document.getElementsByClassName("ng-plugin")];
    
    plugins.forEach((plugin, i) => {
      const fullContent = [...plugin.children];
      const title = fullContent[0];
      const description = fullContent[6];

      const steps = fullContent[2];
      [...steps.children].forEach(step => step.children[0].classList.add("badge"))

      const smallContent = [title, fullContent[1], steps, description];

      title.children[0].setAttribute('href', '#' + plugin.id);
      plugin.replaceChildren(...smallContent)
      plugin.classList.remove("plugin-hidden");
      plugin.addEventListener("click", e => {
        if (plugin.classList.contains("plugin-open")) {
          plugin.replaceChildren(...smallContent);
          plugin.classList.remove("plugin-open");
        } else {
          plugin.replaceChildren(...fullContent);
          plugin.classList.add("plugin-open");
        }
      });
    });
    if (plugins.length > 0 && window.location.hash && window.location.hash.length > 1) {
      const plugin = window.location.hash.substring(1);
      setTimeout(function () {
        const elem = document.getElementById(plugin)
        elem.click();
        elem.scrollIntoView();
      }, 300)
    }
  }

});