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

  function setupSearchModal() {
    var pathname = window.location.pathname;
    var baseUrl = '';
    if (pathname.startsWith('/otoroshi/manual')) {
      baseUrl = '/otoroshi/manual/';
    } else if (pathname.startsWith('/otoroshi/devmanual/')) {
      baseUrl = '/otoroshi/devmanual';
    }
    $('.title-wrapper').append([
      '<div id="search-block-placeholder" style="width: 100%; display: flex; justify-content: flex-end;padding-right: 0px;">',
      `<button type="button" id="search-zone">
      <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="#000" style="height: 17px; width: 17px">
        <path stroke-linecap="round" stroke-linejoin="round" d="M21 21l-5.197-5.197m0 0A7.5 7.5 0 105.196 5.196a7.5 7.5 0 0010.607 10.607z" />
      </svg>
        Search

      <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="1.5" stroke="#000" style="height: 17px; width: 17px">
        <path stroke-linecap="round" stroke-linejoin="round" d="M6.75 7.5l3 2.25-3 2.25m4.5 0h3m-9 8.25h13.5A2.25 2.25 0 0021 18V6a2.25 2.25 0 00-2.25-2.25H5.25A2.25 2.25 0 003 6v12a2.25 2.25 0 002.25 2.25z" />
      </svg>
      </button>`,
      '</div>'
    ].join(''));
    $('nav.site-nav > div.nav-toc > ul > li > a').last().remove();
    $('body').on('click', '#search-zone', function (e) {
      // $('body').append(`
      //   <div id="search-block-backdrop" style="z-index: 1000000; width: 100vw; height: 100vh; position: fixed; left: 0px; top: 0px; background-color: rgba(0, 0, 0, 0.3);">
      //     <div id="search-block" style="width: 50vw; min-height: 100vh; background-color: white; padding: 10px; overflow-y: scroll; position: fixed; right: 0px; top: 0px;"></div>
      //   </div>
      // `);
      $('body').append(`
        <div id="search-block-backdrop" style="z-index: 1000000; width: 100vw; height: 100vh; position: fixed; left: 0px; top: 0px; background-color: rgba(0, 0, 0, 0.3); display: flex; justify-content: center; align-items: center;">
          <div id="search-block" style="width: 70vw; max-height: 70vh; min-height: 30vh; background-color: white; padding: 20px; overflow-y: scroll; border-radius: 5px; border: 1px solid black;"></div>
        </div>
      `);
      new PagefindUI({
        element: "#search-block",
        translations: {
          placeholder: "Search otoroshi documentation",
        },
        showSubResults: true,
      });
      setTimeout(function () {
        $('.pagefind-ui__search-input').focus();
      }, 300);
    });
    $('body').on('click', '#search-block-backdrop', function (e) {
      if (e.target.id === 'search-block-backdrop') {
        $('#search-block-backdrop').remove();
      }
    });
    if (pathname.startsWith('/otoroshi/manual/search.html') || pathname.startsWith('/search.html') || pathname.startsWith('/otoroshi/devmanual/search.html')) {
      $('h1').remove();
      $('.page-content > .large-3').remove();
      $('.page-content > .large-9').removeClass('large-9').addClass('large-12');
      new PagefindUI({
        element: "#search-block-page",
        translations: {
          placeholder: "Search otoroshi documentation",
        },
        showSubResults: true,
      });
      setTimeout(function () {
        $('.pagefind-ui__search-input').focus();
      }, 300);
    }

    setInterval(function () {
      $('.pagefind-ui__result-link').each(function () {
        var href = $(this).attr('href');
        var prefix = baseUrl;
        if (!href.startsWith(prefix)) {
          var nhref = (prefix + href).replace('//', '/');
          $(this).attr('href', nhref);
        }
      });
      var parts = window.location.pathname.replace('//', '/').split('/');
      $('.pagefind-ui__result-image').each(function () {
        var src = $(this).attr('src');
        if (parts.length === 4 && src.startsWith('../imgs/')) {
          $(this).attr('src', src.substring(1));
        }
      });
    }, 300);
  }

  function setupSearchPage() {
    var pathname = window.location.pathname;
    $('.title-wrapper').append([
      '<div id="search-block-placeholder" style="width: 100%; display: flex; justify-content: flex-end;padding-right: 0px; padding-top: 14px;">',
      '<button type="button" id="search-zone">Search</button>',
      '</div>'
    ].join(''));
    $('nav.site-nav > div.nav-toc > ul > li > a').last().remove();
    $('h1').remove();
    $('.page-content > .large-3').remove();
    $('.page-content > .large-9').removeClass('large-9').addClass('large-12');
    $('body').on('click', '#search-zone', function (e) {
      if (pathname.startsWith('/otoroshi/manual/')) {
        window.location = '/otoroshi/manual/search.html';
      } else if (pathname.startsWith('/otoroshi/devmanual/')) {
        window.location = '/otoroshi/devmanual/search.html';
      } else {
        window.location = '/search.html';
      }
    });
    if (pathname.startsWith('/otoroshi/manual/search.html') || pathname.startsWith('/search.html') || pathname.startsWith('/otoroshi/devmanual/search.html')) {
      var baseUrl = '/';
      if (pathname.startsWith('/otoroshi/manual/')) {
        baseUrl = '/otoroshi/manual/';
      } else if (pathname.startsWith('/otoroshi/devmanual/')) {
        baseUrl = '/otoroshi/devmanual/';
      }
      new PagefindUI({
        element: "#search-block-page",
        translations: {
          placeholder: "Search otoroshi documentation",
        },
        showSubResults: true,
        baseUrl: baseUrl,
      });
      setTimeout(function () {
        $('.pagefind-ui__search-input').focus();
      }, 300);
    }
  }


  function setupSearch() {
    setupSearchModal();
  }

  // function setupSearch() {
  //   elasticlunr.clearStopWords();
  //   var index = elasticlunr();
  //   index.addField('title');
  //   index.addField('content');
  //   index.setRef('url');
  //   var additionalPath = window.location.host === 'maif.github.io' ? (
  //     window.location.pathname.indexOf('devmanual') > -1 ? '/otoroshi/devmanual' : (
  //       window.location.pathname.indexOf('manual.next') > -1 ? '/otoroshi/manual.next' : '/otoroshi/manual'
  //     )
  //   ) : '';
  //   var url = additionalPath + '/content.json';
  //   $.get(url, function (data) {
  //     data.map(page => {
  //       index.addDoc(page);
  //     });
  //     $('.title-wrapper').append([
  //       '<div id="search-block" style="width: 100%; display: flex; justify-content: flex-end;padding-right: 0px; padding-top: 14px;">',
  //       '<input id="search-zone" type="text" placeholder="Search the doc ..." style="width: 300px;"></input>',
  //       '<div id="search-results" style="background-color: #eee; border: 1px solid #eee;z-index: 999; position: absolute; display: flex; flex-direction: column;">',
  //       '</div>',
  //       '</div>'
  //     ].join(''));
  //     $('body').on('click', '#reset-search', function (e) {
  //       $('#search-zone').val('');
  //       $('#search-results').html('');
  //     });
  //     $('body').on('keyup', '#search-zone', function (e) {
  //       var searched = e.target.value;
  //       var search = index.search(searched, { expand: true });
  //       var foundDocs = search.map(f => {
  //         return data.filter(d => d.url === f.ref)[0];
  //       }).filter(d => d.id !== '/entities/index.md' && d.id !== '/includes/initialize.md' && d.id !== '/includes/fetch-and-start.md');
  //       const rect = e.target.getBoundingClientRect();
  //       $('#search-results').css('left', rect.left).css('top', rect.top + rect.height).css('width', rect.width);
  //       // console.log('\n\n----------------------------------------------------')
  //       // console.log(foundDocs.slice(0, 10).map(p => ({ title: p.title, id: p.id })))
  //       // console.log('----------------------------------------------------\n\n')
  //       var foundDocsHtml = foundDocs.length > 0 ? foundDocs.slice(0, 10).map(d => {
  //         return '<a style="height: 50px; background-color: #fbfbfb; padding: 10px;" href="' + additionalPath + d.url + '">' + d.title + '</a>';
  //       }).join('') : '<span style="height: 50px; background-color: #fbfbfb; padding: 10px;>No results</span>';
  //       $('#search-results').html('<h3 style="padding: 10px; margin-bottom: 0px;display: flex; justify-content: space-between;">Search results ' +
  //         '<button type="button" id="reset-search" style="font-size: 14px;border: 1px solid black;padding: 5px;border-radius: 4px;font-weight: lighter;mmargin-left: 20px;">clear search</button></h3>' + foundDocsHtml);
  //     });
  //   });
  // }

  setupSearch();

  function improveSidebar() {
    if (document.getElementsByClassName("active")[1]) {
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

    [...document.querySelectorAll('.nav-toc > ul > li >.page')].forEach(r => {
      if ([...r.parentElement.querySelectorAll('ul')].length > 0) {
        r.innerHTML = `<div style="display: flex; justify-content: space-between; align-items: center">${r.textContent} ${r.classList.contains('active') ? `<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="currentColor" style="width: 14px; height: 14px">
      <path stroke-linecap="round" stroke-linejoin="round" d="M19.5 8.25l-7.5 7.5-7.5-7.5" />
      </svg>` :
          `<svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke-width="2" stroke="#000" style="width: 14px; height: 14px">
      <path stroke-linecap="round" stroke-linejoin="round" d="M8.25 4.5l7.5 7.5-7.5 7.5" />
    </svg>`}
    </div>`
      } else {

      }
    })
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

        let content = codeContainer.textContent;
        if (content.startsWith('copy')) {
          content = content.slice(4);
        }

        navigator.clipboard.writeText(content);

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
      document.getElementById("instructions-toggle-button").style.backgroundColor = "#f9b000"
      document.getElementById("instructions-toggle-button").style.color = "#fff"
      element.parentNode.classList.add("instructions-closed");
      if (document.getElementById("instructions-toggle-confirm"))
        document.getElementById("instructions-toggle-confirm").style.display = "none";
    }
    else {
      if (document.getElementById("instructions-toggle-confirm"))
        document.getElementById("instructions-toggle-confirm").style.display = "flex";
    }

    element.addEventListener('click', function (event) {
      if (element.parentNode.classList.contains("instructions-closed")) {
        element.parentNode.classList.remove("instructions-closed")
        if (!instructionsDone) {
          document.getElementById("instructions-toggle-button").innerText = "close"
          if (document.getElementById("instructions-toggle-confirm"))
            document.getElementById("instructions-toggle-confirm").style.display = "flex"
        }
      } else {
        element.parentNode.classList.add("instructions-closed")
        if (!instructionsDone)
          document.getElementById("instructions-toggle-button").innerText = "Start the installation"
        else {
          document.getElementById("instructions-toggle-button").innerText = "Already done"
          document.getElementById("instructions-toggle-button").style.backgroundColor = "#f9b000"
          document.getElementById("instructions-toggle-button").style.color = "#fff"
        }
        if (document.getElementById("instructions-toggle-confirm"))
          document.getElementById("instructions-toggle-confirm").style.display = "none"
      }
    })

    if (document.getElementById("instructions-toggle-confirm"))
      document.getElementById("instructions-toggle-confirm").addEventListener('click', event => {
        instructionsDone = true

        localStorage.setItem("instructions", true);
        element.parentNode.classList.add("instructions-closed")
        document.getElementById("instructions-toggle-button").innerText = "Already done"
        document.getElementById("instructions-toggle-button").style.backgroundColor = "#f9b000"
        document.getElementById("instructions-toggle-button").style.color = "#fff"
        document.getElementById("instructions-toggle-confirm").style.display = "none"
      });
  }

  let toggles = [...document.getElementsByClassName("simple-block-toggle")]

  toggles.forEach(element => {
    element.parentNode.classList.add("instructions-closed")
    element.getElementsByClassName("simple-block-button")[0].innerText = "Read"

    element.addEventListener('click', function (event) {
      if (element.parentNode.classList.contains("instructions-closed")) {
        element.parentNode.classList.remove("instructions-closed")
        element.getElementsByClassName("simple-block-button")[0].innerText = "Close"
      } else {
        element.parentNode.classList.add("instructions-closed")
        element.getElementsByClassName("simple-block-button")[0].innerText = "Read"
      }
    })
  })
});