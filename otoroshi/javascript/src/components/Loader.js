import React, { useEffect, useState } from 'react';

export default function Loader({ loading, children, loadingChildren, minLoaderTime = 150 }) {
  const [internalLoading, setInternalLoading] = useState(true);
  const [startingTime, setStartingTime] = useState(undefined);

  useEffect(() => {
    let timeout;
    if (loading) {
      setInternalLoading(true);
      setStartingTime(Date.now());
    } else if (internalLoading) {
      const delay = minLoaderTime - (Date.now() - startingTime);
      if (delay <= 0) setInternalLoading(false);
      else
        timeout = setTimeout(() => {
          setInternalLoading(false);
        }, delay);
    }

    return () => {
      if (timeout) clearTimeout(timeout);
    };
  }, [loading]);

  if (internalLoading)
    return (
      <>
        <div className="d-flex justify-content-center">
          <i className="fas fa-cog fa-spin" style={{ fontSize: '30px' }} />
        </div>
        {loadingChildren}
      </>
    );

  return children ? children : null;
}
