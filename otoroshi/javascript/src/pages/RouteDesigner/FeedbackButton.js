import React, { useEffect, useState } from 'react';

export function FeedbackButton({
  type = 'save',
  text,
  icon,
  onPress = () => Promise.resolve(),
  feedbackTimeout = 1500,
  className,
  disabled,
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
      disabled={disabled}
      className={`btn btn-sm ${color} ${className || ''}`}
      onClick={() => {
        if (!uploading && waiting) {
          setUploading(true);
          const timer = Date.now();
          onPress()
            .then(() => {
              const diff = Date.now() - timer;
              if (diff > 150) onResult('success');
              setTimeout(() => {
                onResult('success');
              }, 150 - diff);
            })
            .catch((err) => {
              onResult('failed');
              throw err;
            });
        }
      }}>
      {text}
      <div
        style={{
          width: '20px',
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
    </button>
  );
}
