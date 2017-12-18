$(function() {
  $('.swagger-frame').each(function() {
    var frame = $(this);
    var lastHeight = 0;
    setInterval(function() {
      if (frame.contents().innerHeight() != lastHeight) {
        lastHeight = frame.contents().innerHeight();
        frame.css('height', lastHeight + 'px');
      }
    }, 1000);
  });
});