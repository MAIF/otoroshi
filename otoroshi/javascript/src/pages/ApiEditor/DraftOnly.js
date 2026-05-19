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

// Switch the editor between the draft working copy and the live production
// config — updates the `version` query param and reloads.
export function switchToVersion(target) {
  const queryParams = new URLSearchParams(window.location.search);
  queryParams.set('version', target);
  window.history.replaceState(null, null, '?' + queryParams.toString());
  window.location.reload();
}

// CTA shown on the read-only production view of a config tab: jumps to the
// draft so the user can actually edit.
export function EditInDraftButton() {
  return (
    <button
      type="button"
      className="btn btn-sm btn-warning d-flex align-items-center"
      onClick={() => switchToVersion('Draft')}
    >
      <i className="fas fa-pen me-2" />
      Edit in draft
    </button>
  );
}

// Sticky, full-width banner at the top of the API editor. Draft and production
// aren't parallel universes here — the draft is the working copy you publish
// to production — so the wording surfaces that workflow rather than a sandbox
// metaphor. Hidden on a staging (never-published) API: nothing to distinguish
// yet.
export function VersionBanner() {
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
      <button
        type="button"
        className="version-banner-action"
        onClick={() => switchToVersion(isDraft ? 'Published' : 'Draft')}
        data-testid="version-banner-switch"
      >
        {isDraft ? 'View production' : 'Edit in draft'}
        <i className="fas fa-arrow-right" />
      </button>
    </div>
  );
}

