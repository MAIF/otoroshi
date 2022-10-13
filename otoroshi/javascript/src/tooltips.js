export function createTooltip(message, position, noBootstrap) {
  return {
    'data-toggle': 'tooltip',
    'data-placement': position || 'top',
    title: message,
    ref: (r) => {
      // if (r)
      //   new bootstrap.Tooltip(r, {
      //     container: 'body',
      //   });
      // if (!noBootstrap) {
      //   setTimeout(() => $(r).tooltip({ container: 'body' }));
      // }
    },
  };
}
