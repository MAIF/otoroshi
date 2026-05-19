import React from 'react';
import { useSignalValue } from 'signals-react-safe';
import { signalVersion } from './VersionSignal';
import { useDraftOfAPI } from './hooks';

export function DraftOnly({ children }) {
  const { isDraft } = useDraftOfAPI();

  return isDraft ? children : null;
}

export function VersionBadge({ size, className }) {
  const version = useSignalValue(signalVersion);
  return (
    <div
      className={className ? className : 'm-0 ms-2'}
      style={{ fontSize: size === 'xs' ? '.75rem' : '1rem' }}
    >
      <span className={`badge bg-xs ${version === 'Published' ? 'bg-danger' : 'bg-warning'}`}>
        {version === 'Published' ? 'PROD' : 'DRAFT'}
      </span>
    </div>
  );
}

export function switchToVersion(target) {
  const queryParams = new URLSearchParams(window.location.search);
  queryParams.set('version', target);
  window.history.replaceState(null, null, '?' + queryParams.toString());
  window.location.reload();
}

export function EditInDraftButton() {
  return (
    <button
      type="button"
      className="version-banner-action"
      onClick={() => switchToVersion('Draft')}
    >
      <i className="fas fa-pen me-2" />
      Edit in draft
    </button>
  );
}

export function VersionBanner({ showAction = true }) {
  const version = useSignalValue(signalVersion);

  if (!version || version === 'staging') return null;

  const isDraft = version === 'Draft';

  return (
    <div className={`version-banner version-banner--${isDraft ? 'draft' : 'prod'}`}>
      <div className="version-banner-info">
        <span className="version-banner-tag">
          <i className={`fas ${isDraft ? 'fa-pen' : 'fa-circle'}`} />
          {isDraft ? 'DRAFT' : 'PRODUCTION'}
        </span>
        <span className="version-banner-text">
          {isDraft
            ? 'your edits go live once published'
            : 'live config — edit through the draft'}
        </span>
      </div>
      {showAction && (
        <button
          type="button"
          className="version-banner-action"
          onClick={() => switchToVersion(isDraft ? 'Published' : 'Draft')}
          data-testid="version-banner-switch"
        >
          {isDraft ? 'View production' : 'Edit in draft'}
          <i className="fas fa-arrow-right" />
        </button>
      )}
    </div>
  );
}

