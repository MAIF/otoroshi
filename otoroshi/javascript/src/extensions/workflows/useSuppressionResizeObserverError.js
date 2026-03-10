import { useEffect } from 'react';

export const useSuppressResizeObserverError = () => {
  useEffect(() => {
    const errorHandler = (e) => {
      if (
        e.message.includes(
          'ResizeObserver loop completed with undelivered notifications' ||
            'ResizeObserver loop limit exceeded'
        )
      ) {
        const resizeObserverErr = document.getElementById('webpack-dev-server-client-overlay');
        if (resizeObserverErr) {
          resizeObserverErr.style.display = 'none';
        }
      }
    };
    window.addEventListener('error', errorHandler);

    return () => {
      window.removeEventListener('error', errorHandler);
    };
  }, []);
};
