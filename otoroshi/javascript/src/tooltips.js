export function createTooltip(message, position, noBootstrap) {
  return {
    'data-toggle': 'tooltip',
    'data-placement': position || 'top',
    title: message,
    ref: r => {
      // if (!noBootstrap) {
      //   setTimeout(() => $(r).tooltip({ container: 'body' }));
      // }
    },
  };
}
