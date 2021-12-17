$(function () {
  $('.swagger-frame').each(function () {

    var url = window.location.pathname.indexOf('devmanual') > -1 ? 'https://maif.github.io/otoroshi/swagger-ui/index-dev.html' : 'https://maif.github.io/otoroshi/swagger-ui/index.html';

    $(this).append('<iframe class="swagger-frame" src="' + url + '" frameborder="0" style="width:100%;height:100vh;"><iframe>');
    var frame = $(this).find('iframe');
    var lastHeight = 0;
    setInterval(function () {
      if (frame.contents().innerHeight() !== lastHeight) {
        lastHeight = frame.contents().innerHeight();
        frame.css('height', lastHeight + 'px');
      }
    }, 500);
  });

  function setupSearch() {
    elasticlunr.clearStopWords();
    var index = elasticlunr();
    index.addField('title');
    index.addField('content');
    index.setRef('url');
    var additionalPath = window.location.host === 'maif.github.io' ? (
      window.location.pathname.indexOf('devmanual') > -1 ? '/otoroshi/devmanual' : (
        window.location.pathname.indexOf('manual.next') > -1 ? '/otoroshi/manual.next' : '/otoroshi/manual'
      )
    ) : '';
    var url = additionalPath + '/content.json';
    $.get(url, function (data) {
      data.map(page => {
        index.addDoc(page);
      });
      $('.title-wrapper').append([
        '<div id="search-block" style="width: 100%; display: flex; justify-content: flex-end;padding-right: 0px; padding-top: 14px;">',
        '<input id="search-zone" type="text" placeholder="Search the doc ..." style="width: 300px;"></input>',
        '<div id="search-results" style="background-color: #eee; border: 1px solid #eee;z-index: 999; position: absolute; display: flex; flex-direction: column;">',
        '</div>',
        '</div>'
      ].join(''));
      $('body').on('click', '#reset-search', function (e) {
        $('#search-zone').val('');
        $('#search-results').html('');
      });
      $('body').on('keyup', '#search-zone', function (e) {
        var searched = e.target.value;
        var search = index.search(searched, { expand: true });
        var foundDocs = search.map(f => {
          return data.filter(d => d.url === f.ref)[0];
        }).filter(d => d.id !== '/entities/index.md' && d.id !== '/includes/initialize.md' && d.id !== '/includes/fetch-and-start.md');
        const rect = e.target.getBoundingClientRect();
        $('#search-results').css('left', rect.left).css('top', rect.top + rect.height).css('width', rect.width);
        // console.log('\n\n----------------------------------------------------')
        // console.log(foundDocs.slice(0, 10).map(p => ({ title: p.title, id: p.id })))
        // console.log('----------------------------------------------------\n\n')
        var foundDocsHtml = foundDocs.length > 0 ? foundDocs.slice(0, 10).map(d => {
          return '<a style="height: 50px; background-color: #fbfbfb; padding: 10px;" href="' + additionalPath + d.url + '">' + d.title + '</a>';
        }).join('') : '<span style="height: 50px; background-color: #fbfbfb; padding: 10px;>No results</span>';
        $('#search-results').html('<h3 style="padding: 10px; margin-bottom: 0px;display: flex; justify-content: space-between;">Search results ' +
          '<button type="button" id="reset-search" style="font-size: 14px;border: 1px solid black;padding: 5px;border-radius: 4px;font-weight: lighter;mmargin-left: 20px;">clear search</button></h3>' + foundDocsHtml);
      });
    });
  }

  setupSearch();

  function improveSidebar() {
    let list = document.getElementsByClassName("active")[1]
      .parentElement
      .getElementsByTagName("ul")[0]

    if (!list) {
      list = document.getElementsByClassName("active")[1]
        .parentElement
        .parentElement
        .parentElement
        .children[0]
        .parentElement
        .getElementsByTagName("ul")[0]
    }

    if (list && list.children)
      for (let i = 0; i < list.children.length; i++) {
        let r = list.children[i]
        r.style.display = "block"
      }
  }

  improveSidebar();

  function improveCodeBlock() {
    const codes = document.getElementsByClassName("prettyprint");

    function pasteButton(codeContainer) {
      const paste = document.createElement("button");
      paste.classList.add("paste-button-container");

      const pasteText = document.createElement("span");
      pasteText.textContent = "Copied!";
      pasteText.classList.add("paste-text")

      const div = document.createElement("div");
      div.classList.add("paste-button");

      paste.addEventListener('click', function (event) {
        codeContainer.focus();
        navigator.clipboard.writeText(codeContainer.textContent)

        codeContainer.appendChild(pasteText);
        setTimeout(() => codeContainer.removeChild(pasteText), 2000)
      })

      paste.appendChild(div);
      codeContainer.appendChild(paste);
    }

    for (let i = 0; i < codes.length; i++) {
      codes[i].style.position = 'relative';
      pasteButton(codes[i])
    }
  }

  improveCodeBlock();

  if (document.getElementById("instructions-toggle")) {

    const element = document.getElementById("instructions-toggle")

    let instructionsDone = false

    if (localStorage.getItem("instructions")) {
      instructionsDone = true
      document.getElementById("instructions-toggle-button").innerText = "Already done"
      document.getElementById("instructions-toggle-button").style.backgroundColor = "#fb9000"
      document.getElementById("instructions-toggle-button").style.color = "#fff"
      element.parentNode.classList.add("instructions-closed");
      document.getElementById("instructions-toggle-confirm").style.display = "none";
    }
    else {
      document.getElementById("instructions-toggle-confirm").style.display = "flex";
    }

    element.addEventListener('click', function (event) {
      if (element.parentNode.classList.contains("instructions-closed")) {
        element.parentNode.classList.remove("instructions-closed")
        if (!instructionsDone) {
          document.getElementById("instructions-toggle-button").innerText = "close"
          document.getElementById("instructions-toggle-confirm").style.display = "flex"
        }
      } else {
        element.parentNode.classList.add("instructions-closed")
        if (!instructionsDone)
          document.getElementById("instructions-toggle-button").innerText = "Start the installation"
        else {
          document.getElementById("instructions-toggle-button").innerText = "Already done"
          document.getElementById("instructions-toggle-button").style.backgroundColor = "#fb9000"
          document.getElementById("instructions-toggle-button").style.color = "#fff"
        }
        document.getElementById("instructions-toggle-confirm").style.display = "none"
      }
    })

    document.getElementById("instructions-toggle-confirm").addEventListener('click', event => {
      instructionsDone = true
      localStorage.setItem("instructions", true);
      element.parentNode.classList.add("instructions-closed")
      document.getElementById("instructions-toggle-button").innerText = "Already done"
      document.getElementById("instructions-toggle-button").style.backgroundColor = "#fb9000"
      document.getElementById("instructions-toggle-button").style.color = "#fff"
      document.getElementById("instructions-toggle-confirm").style.display = "none"
    });
  }
});