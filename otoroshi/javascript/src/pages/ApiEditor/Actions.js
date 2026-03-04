import React, { useEffect, useState } from 'react';
import { useHistory } from 'react-router-dom';
import { fetchWrapperNext, nextClient } from '../../services/BackOfficeServices';
import { API_STATE } from './model';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI } from './hooks';
import { VersionBadge } from './DraftOnly';
import { LIFECYCLE_STEPS, STATE_PERMISSIONS } from './constants';

function ActionCard({ icon, title, description, onClick, color }) {
  return (
    <div className="actions-action-card" onClick={onClick}>
      <div
        className="actions-action-card-icon"
        style={{ backgroundColor: `${color || 'rgba(249, 181, 47, 0.15)'}` }}
      >
        <i className={icon} style={color ? { color: '#fff' } : undefined} />
      </div>
      <div className="actions-action-card-body">
        <span className="actions-action-card-title">{title}</span>
        <span className="actions-action-card-description">{description}</span>
      </div>
      <i className="fas fa-chevron-right actions-action-card-arrow" />
    </div>
  );
}

export function publishAPI(draft, api, history) {
  window
    .newConfirm(
      <>
        Upgrading an API from staging to production makes it fully available to clients without
        testing headers.
        <p>Clients will use the API with real data in a reliable environment.</p>
      </>,
      {
        title: 'Production environment',
        yesText: 'Publish',
      }
    )
    .then((ok) => {
      if (ok) {
        const deployment = {
          apiRef: api.id,
          owner: window.__user?.profile?.name || window.__userid,
          at: Date.now(),
          apiDefinition: {
            ...draft.content,
            deployments: [],
          },
          draftId: draft.id
        };
        fetchWrapperNext(
          `/${nextClient.ENTITIES.APIS}/${api.id}/deployments`,
          'POST',
          deployment,
          'apis.otoroshi.io'
        )
          .then(() => {
            const queryParams = new URLSearchParams(window.location.search);
            queryParams.delete('version');
            window.history.replaceState(null, null, '?' + queryParams.toString());
            window.location.reload();

            history.push(`/apis/${api.id}`);
          })
          .then(() => nextClient.forEntityNext(nextClient.ENTITIES.DRAFTS).deleteById(api.id));
      }
    });
}

export function Actions(props) {
  const history = useHistory();
  const { draft, api, item, updateAPI, updateDraft } = useDraftOfAPI();
  const [previewStep, setPreviewStep] = useState(null);

  useEffect(() => {
    props.setTitle({
      value: 'Actions',
      noThumbtack: true,
      children: <div
        className='m-0 ms-2'
        style={{ fontSize: '1rem' }}
      >
        <span className={`badge bg-xs bg-danger`}>
          PROD
        </span>
      </div>,
    });
  }, []);

  if (!item) return <SimpleLoader />;

  const currentState = item.state;
  const viewedState = previewStep || currentState;
  const isPreview = previewStep && previewStep !== currentState;
  const permissions = STATE_PERMISSIONS[viewedState] || STATE_PERMISSIONS[API_STATE.STAGING];

  const onDuplicateAPI = () => {
    window
      .newConfirm(
        'This will create a copy of this API with all its configuration. The new API will start in staging mode.',
        {
          title: 'Duplicate API',
          yesText: 'Duplicate',
        }
      )
      .then((ok) => {
        if (ok) {
          // TODO: call backend to duplicate the API
          console.log('Duplicate API', api.id);
        }
      });
  };

  const transitionTo = (targetState, confirmMessage, confirmTitle, confirmYes) => {
    window
      .newConfirm(confirmMessage, {
        title: confirmTitle,
        yesText: confirmYes,
      })
      .then((ok) => {
        if (ok) {
          if (targetState === API_STATE.PUBLISHED && currentState === API_STATE.STAGING) {
            publishAPI(draft, api, history);
          } else {
            Promise.all([
              updateDraft({ ...item, state: targetState, }),
              updateAPI({ ...api, state: targetState, })
            ])
              .then(() => window.location.reload());
          }
        }
      });
  };

  const exportJSON = () => {
    const name = item.id
      .replace(/ /g, '-')
      .replace(/\(/g, '')
      .replace(/\)/g, '')
      .toLowerCase();
    const json = JSON.stringify({ ...item, kind: 'apis.otoroshi.io/Api' }, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.id = String(Date.now());
    a.style.display = 'none';
    a.download = `api.${name}-${Date.now()}.json`;
    a.href = url;
    document.body.appendChild(a);
    a.click();
    setTimeout(() => document.body.removeChild(a), 300);
  }

  const exportYAML = () => {
    const name = item.id
      .replace(/ /g, '-')
      .replace(/\(/g, '')
      .replace(/\)/g, '')
      .toLowerCase();

    fetch('/bo/api/json_to_yaml', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        apiVersion: 'proxy.otoroshi.io/v1',
        kind: 'apis.otoroshi.io/Api',
        metadata: {
          name,
        },
        spec: item,
      }),
    })
      .then((r) => r.text())
      .then((yaml) => {
        const blob = new Blob([yaml], { type: 'application/yaml' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.id = String(Date.now());
        a.style.display = 'none';
        a.download = `api-${name}-${Date.now()}.yaml`;
        a.href = url;
        document.body.appendChild(a);
        a.click();
        setTimeout(() => document.body.removeChild(a), 300);
      });
  }

  const stepMeta = LIFECYCLE_STEPS.find((s) => s.key === viewedState);

  const isStaging = item.state === 'staging'

  return (
    <div className="actions-page mt-3">
      {/* Lifecycle section */}
      <div className="actions-section">
        <h3 className="actions-section-title">
          <i className="fas fa-heartbeat me-2" />
          Lifecycle
        </h3>
        <p className="actions-section-description">
          Manage the lifecycle state of your API. Click any step to preview its permissions.
        </p>

        {/* Visual stepper */}
        <div className="actions-lifecycle-stepper">
          {LIFECYCLE_STEPS.map((step, idx) => {
            const isCurrent = step.key === currentState;
            const isViewed = step.key === viewedState;
            const isPast = LIFECYCLE_STEPS.findIndex((s) => s.key === currentState) > idx;
            return (
              <React.Fragment key={step.key}>
                {idx > 0 && (
                  <div
                    className="actions-lifecycle-connector"
                    style={{
                      backgroundColor: isPast ? step.color : 'var(--bg-color_level3)',
                    }}
                  />
                )}
                <div
                  className={`actions-lifecycle-step ${isCurrent ? 'actions-lifecycle-step--active' : ''} ${isPast ? 'actions-lifecycle-step--past' : ''} ${isViewed && !isCurrent ? 'actions-lifecycle-step--previewed' : ''}`}
                  onClick={() => setPreviewStep(step.key === previewStep ? null : step.key)}
                >
                  <div
                    className="actions-lifecycle-step-dot"
                    style={{
                      borderColor: isCurrent || isPast || isViewed ? step.color : 'var(--bg-color_level3)',
                      backgroundColor: isCurrent ? step.color : isViewed ? `${step.color}33` : 'transparent',
                    }}
                  >
                    {(isCurrent || isPast) && (
                      <i
                        className={step.icon}
                        style={{ color: isCurrent ? '#fff' : step.color, fontSize: '.7rem' }}
                      />
                    )}
                    {isViewed && !isCurrent && !isPast && (
                      <i
                        className="fas fa-eye"
                        style={{ color: step.color, fontSize: '.6rem' }}
                      />
                    )}
                  </div>
                  <span
                    className="actions-lifecycle-step-label"
                    style={{
                      color: isViewed ? step.color : undefined,
                      fontWeight: isViewed ? 700 : 400,
                    }}
                  >
                    {step.label}
                    {isCurrent && <span className="actions-lifecycle-current-badge">current</span>}
                  </span>
                </div>
              </React.Fragment>
            );
          })}
        </div>

        {/* Preview indicator */}
        {isPreview && (
          <div className="actions-preview-banner" style={{ borderLeftColor: stepMeta?.color }}>
            <i className="fas fa-eye me-2" style={{ color: stepMeta?.color }} />
            Previewing <strong>{stepMeta?.label}</strong> permissions
          </div>
        )}

        {/* Permissions for viewed state */}
        <div className="actions-permissions">
          {permissions.allowed.length > 0 && (
            <div className="actions-permissions-group">
              <span className="actions-permissions-group-title actions-permissions-group-title--allowed">
                <i className="fas fa-check-circle me-1" />
                Allowed
              </span>
              <ul className="actions-permissions-list">
                {permissions.allowed.map((perm, i) => (
                  <li key={i} className="actions-permission-item actions-permission-item--allowed">
                    <i className={`${perm.icon} actions-permission-icon actions-permission-icon--allowed`} />
                    <span>{perm.text}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          {permissions.denied.length > 0 && (
            <div className="actions-permissions-group">
              <span className="actions-permissions-group-title actions-permissions-group-title--denied">
                <i className="fas fa-ban me-1" />
                Not allowed
              </span>
              <ul className="actions-permissions-list">
                {permissions.denied.map((perm, i) => (
                  <li key={i} className="actions-permission-item actions-permission-item--denied">
                    <i className={`${perm.icon} actions-permission-icon actions-permission-icon--denied`} />
                    <span>{perm.text}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>

        {!isStaging && <>
          {/* Transition action cards */}
          <div className="actions-general-actions">
            {currentState === API_STATE.STAGING && (
              <ActionCard
                icon="fas fa-rocket"
                title="Publish API"
                description="Make your API fully available to clients in production"
                onClick={() =>
                  transitionTo(
                    API_STATE.PUBLISHED,
                    'Publishing makes your API fully available to clients. Clients will be able to subscribe and use it in production.',
                    'Publish API',
                    'Publish'
                  )
                }
              />
            )}

            {currentState === API_STATE.PUBLISHED && (
              <>
                <ActionCard
                  icon="fas fa-exclamation-triangle"
                  title="Deprecate API"
                  description="Prevent new subscriptions while keeping existing traffic"
                  color="hsla(40, 94%, 58%, 1)"
                  onClick={() =>
                    transitionTo(
                      API_STATE.DEPRECATED,
                      'Deprecating the API will prevent new access modes from subscribing. Existing subscriptions will continue to work.',
                      'Deprecate API',
                      'Deprecate'
                    )
                  }
                />
                <ActionCard
                  icon="fas fa-times-circle"
                  title="Close API"
                  description="Shut down the API entirely — all traffic will be rejected"
                  color="hsla(0, 65%, 55%, 1)"
                  onClick={() =>
                    transitionTo(
                      API_STATE.REMOVED,
                      'Closing the API will shut it down entirely. All traffic will be rejected. This action can be reversed.',
                      'Close API',
                      'Close API'
                    )
                  }
                />
              </>
            )}

            {currentState === API_STATE.DEPRECATED && (
              <>
                <ActionCard
                  icon="fas fa-check-circle"
                  title="Re-publish API"
                  description="Make the API available again for new subscriptions"
                  onClick={() =>
                    transitionTo(
                      API_STATE.PUBLISHED,
                      'Re-publishing will make the API available again for new subscriptions.',
                      'Re-publish API',
                      'Re-publish'
                    )
                  }
                />
                <ActionCard
                  icon="fas fa-times-circle"
                  title="Close API"
                  description="Shut down the API entirely — all traffic will be rejected"
                  color="hsla(0, 65%, 55%, 1)"
                  onClick={() =>
                    transitionTo(
                      API_STATE.REMOVED,
                      'Closing the API will shut it down entirely. All traffic will be rejected. This action can be reversed.',
                      'Close API',
                      'Close API'
                    )
                  }
                />
              </>
            )}

            {currentState === API_STATE.REMOVED && (
              <ActionCard
                icon="fas fa-undo"
                title="Reopen API"
                description="Move the API back to staging — you will need to publish it again"
                onClick={() =>
                  transitionTo(
                    API_STATE.STAGING,
                    'This will move the API back to staging. You will need to publish it again to make it available.',
                    'Reopen API',
                    'Move to Staging'
                  )
                }
              />
            )}
          </div>
        </>}
      </div>

      <div className="actions-section">
        <h3 className="actions-section-title">
          <i className="fas fa-cog me-2" />
          General
        </h3>
        <p className="actions-section-description">
          Other operations you can perform on this API.
        </p>

        <div className="actions-general-actions">
          {!isStaging ? <ActionCard
            icon="fas fa-clone"
            title="Duplicate API"
            description="Create a full copy of this API in staging mode"
            onClick={onDuplicateAPI}
          /> : <p className="actions-section-description">
            Actions tabs is not available in staging mode. Please use the publish button from the dashboard.
          </p>}

          <ActionCard
            icon="fas fa-file-export"
            title="Export JSON"
            description="Create a full copy of this API in JSON format"
            onClick={exportJSON}
          />

          <ActionCard
            icon="fas fa-file-export"
            title="Export YAML"
            description="Create a full copy of this API in YAML format"
            onClick={exportYAML}
          />
        </div>
      </div>
    </div>
  );
}
