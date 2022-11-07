import React, { useEffect, useState } from 'react';

export function FeedbackButton({
  type = 'save',
  text,
  icon,
  onPress = () => Promise.resolve(),
  onSuccess,
  feedbackTimeout = 1500,
  className,
  disabled,
  style = {},
}) {
  const [uploading, setUploading] = useState(false);
  const [result, onResult] = useState('waiting');
  const [color, setColor] = useState(`btn-${type}`);

  useEffect(() => {
    let timeout;

    if (result !== 'waiting') {
      setUploading(false);
      timeout = setTimeout(() => {
        onResult('waiting');
      }, feedbackTimeout);
    }

    return () => {
      if (timeout) clearTimeout(timeout);
    };
  }, [result]);

  const failed = result === 'failed';
  const successed = result === 'success';
  const waiting = result === 'waiting';
  const loading = waiting && uploading;

  const Icon = icon;

  useEffect(() => {
    setColor(getColor());
  }, [result, uploading]);

  const getColor = () => {
    if (successed) return 'btn-save';
    else if (failed) return 'btn-danger';
    else if (loading) return 'btn-secondary';

    return `btn-${type}`;
  };

  return (
    <button
      id={text}
      type="button"
      disabled={disabled}
      className={`btn ${color} ${className || ''}`}
      style={{
        ...style,
      }}
      onClick={() => {
        if (!uploading && waiting) {
          setUploading(true);
          const timer = Date.now();
          onPress()
            .then(() => {
              const diff = Date.now() - timer;
              if (diff > 150) {
                if (onSuccess)
                  setTimeout(onSuccess, 250)
                onResult('success');
              } else {
                setTimeout(() => {
                  onResult('success');
                  setTimeout(onSuccess, 250)
                }, 150 - diff);
              }
            })
            .catch((err) => {
              onResult('failed');
              throw err;
            });
        }
      }}>
      <div
        className="me-1"
        style={{
          width: '16px',
          display: 'inline-block',
        }}>
        {waiting && !uploading && <Icon />}

        {loading && (
          <i
            className="fas fa-spinner fa-spin fa-sm"
            style={{
              opacity: loading ? 1 : 0,
              transition: 'opacity 2s',
            }}
          />
        )}

        {successed && (
          <i
            className="fas fa-check"
            style={{
              opacity: successed ? 1 : 0,
              transition: 'opacity 2s',
            }}
          />
        )}

        {failed && (
          <i
            className="fas fa-times"
            style={{
              opacity: failed ? 1 : 0,
              transition: 'opacity 2s',
            }}
          />
        )}
      </div>
      {text}
    </button>
  );
}
