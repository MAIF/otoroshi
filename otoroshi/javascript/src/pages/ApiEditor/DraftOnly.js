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

export function VersionToggle({ isDraft }) {
  const switchVersion = () => {
    const target = isDraft ? 'Published' : 'Draft';
    const queryParams = new URLSearchParams(window.location.search);
    queryParams.set('version', target);
    window.history.replaceState(null, null, '?' + queryParams.toString());
    window.location.reload();
  };

  return (
    <button
      type="button"
      className={`dashboard-version-toggle ${isDraft ? 'dashboard-version-toggle--draft' : 'dashboard-version-toggle--prod'}`}
      onClick={switchVersion}
    >
      <span
        className={`dashboard-version-toggle-indicator ${isDraft ? 'dashboard-version-toggle-indicator--draft' : 'dashboard-version-toggle-indicator--prod'}`}
      >
        {isDraft ? 'DEV' : 'PROD'}
      </span>
      <span className="dashboard-version-toggle-label">
        {isDraft ? 'View Production' : 'Edit in Draft Mode'}
      </span>
      <i className={`fas ${isDraft ? 'fa-arrow-right' : 'fa-arrow-right'}`} />
    </button>
  );
}
