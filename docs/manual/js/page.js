$(function() {
  $('.swagger-frame').each(function() {
    var frame = $(this).append('<iframe class="swagger-frame" src="https://maif.github.io/otoroshi/swagger-ui/index.html" frameborder="0" style="width:100%;height:100vh;"><iframe>');
    var lastHeight = 0;
    setInterval(function() {
      if (frame.contents().innerHeight() !== lastHeight) {
        lastHeight = frame.contents().innerHeight();
        frame.css('height', lastHeight + 'px');
      }
    }, 500);
  });
});