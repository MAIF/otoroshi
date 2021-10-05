window.OtoroshiDarkLightMode = window.OtoroshiDarkLightMode || (function() {

    var mode = window.localStorage.getItem("otoroshi-dark-light-mode");

    var buttonSelector = "otoroshi-dark-light-icon";

    function registerClicks() {
        var button = document.getElementById(buttonSelector);
        if (button) {
            button.addEventListener('click', function(evt) {
                if (mode === "dark") {
                    mode = "light";
                } else if (mode === "light") {
                    mode = "dark";
                }
                window.localStorage.setItem("otoroshi-dark-light-mode", mode);
                fetch('/bo/api/ui-mode', {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json'
                    },
                    body: JSON.stringify({ mode: mode === "light" ? "white" : "dark" })
                })
                update();
            })
            button.parentNode.addEventListener('click', function(evt) {
                evt.preventDefault();
                evt.stopPropagation();
            })
        } else {
            setTimeout(function() {
                registerClicks();
            }, 1000);
        }
    }

    function update() {
        var button = document.getElementById(buttonSelector);
        if (button) {
            if (mode === "dark") {
                button.classList.remove("fa-moon");
                button.classList.remove("fa-lightbulb");
                button.classList.add("fa-lightbulb");
                window.document.body.classList.remove("white-mode");
                window.document.body.classList.remove("dark-mode");
                window.document.body.classList.add("dark-mode");
            }
            if (mode === "light") {
                button.classList.remove("fa-moon");
                button.classList.remove("fa-lightbulb");
                button.classList.add("fa-moon");
                window.document.body.classList.remove("white-mode");
                window.document.body.classList.remove("dark-mode");
                window.document.body.classList.add("white-mode");
            }
        } else {
            setTimeout(function() {
                update();
            }, 1000)
        }
    }

    function setup() {
        console.log("setup dark/light mode !")
        if (!mode) {
            mode = "dark";
            window.localStorage.setItem("otoroshi-dark-light-mode", mode);
        }
        update();
        registerClicks();
    }

    window.onload = function() {
        setTimeout(function() {
            setup();
        }, 1000)
    }
})()