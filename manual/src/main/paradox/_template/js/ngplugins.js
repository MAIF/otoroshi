document.addEventListener("DOMContentLoaded", function() {
  const plugins = [...document.getElementsByClassName("ng-plugin")];
  plugins.forEach((plugin, i) => {
    const fullContent = [...plugin.children];
    const title = fullContent[0];
    const description = fullContent[7];
    const smallContent = [ title, description ];
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
    setTimeout(function() {
      console.log('click on', plugin)
      const elem = document.getElementById(plugin)
      elem.click();
      elem.scrollIntoView();
    }, 300)
  }
});