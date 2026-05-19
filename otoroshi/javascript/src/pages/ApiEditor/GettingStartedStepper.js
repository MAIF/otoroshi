import React, { useState } from 'react';
import { useHistory, useLocation, useParams } from 'react-router-dom';
import { useDraftOfAPI, historyPush } from './hooks';
import { API_STATE } from './model';
import { signalHighlight } from './HighlightSignal';

function buildSteps({ item, params }) {
  // `to` paths intentionally omit `?version=...` — historyPush appends the
  // current location.search (which already carries the draft version) so we
  // avoid the `?version=Draft?version=Draft` double-encoding trap.
  const apiId = params.apiId;
  return [
    {
      id: 1,
      title: 'Create an endpoint',
      description: 'Define how traffic reaches your API',
      icon: 'fas fa-road',
      completed: item.routes.length > 0,
      to: `/apis/${apiId}/endpoints/new`,
    },
    {
      id: 2,
      title: 'Add a backend',
      description: 'Configure a backend target',
      icon: 'fas fa-microchip',
      completed: item.backends.some((b) => b.name !== 'default_backend'),
      to: `/apis/${apiId}/backends/new`,
    },
    {
      id: 3,
      title: 'Create a plugin chain',
      description: 'Add plugin rules and transformations',
      icon: 'fas fa-project-diagram',
      completed: item.flows.some((f) => f.name !== 'default_plugin_chain'),
      to: `/apis/${apiId}/plugin-chains/new`,
      showOnlyIfPublished: true,
    },
    {
      id: 4,
      title: 'Configure the API Gateway',
      description: 'Set the domain and context path in the API Gateway tab',
      icon: 'fas fa-network-wired',
      completed: !!(item.domain && item.contextPath),
      to: `/apis/${apiId}/api-gateway`,
    },
    {
      id: 5,
      title: 'Enable testing',
      description: 'Set up API testing',
      icon: 'fas fa-vial',
      completed: item.testing.enabled,
      to: `/apis/${apiId}/testing`,
    },
    {
      id: 6,
      title: 'Add a plan',
      description: 'Define your API plans',
      icon: 'fas fa-file-alt',
      completed: item.plans?.length > 0,
      to: `/apis/${apiId}/plans/new`,
    },
    {
      id: 7,
      title: 'Deploy your API',
      description: 'Publish to production',
      icon: 'fas fa-rocket',
      completed: item.state === API_STATE.PUBLISHED,
      to: `/apis/${apiId}`,
      // Bounce to the dashboard and pulse the Publish CTA — the user does the
      // actual publish themselves (matches the behaviour of all the other
      // steps which point to the relevant tab).
      highlight: 'publish-this-version',
    },
  ];
}

// Getting-started widget. Floats in the bottom-right corner of
// any /apis/:apiId/* route so progress stays visible while the user navigates
// between tabs.
export function GettingStartedStepper() {
  const params = useParams();
  const history = useHistory();
  const location = useLocation();
  const { item, draft, isDraft } = useDraftOfAPI();

  const apiId = params?.apiId;
  const collapseKey = `otoroshi.gs-stepper.collapsed.${apiId}`;

  const [collapsed, setCollapsed] = useState(
    () => !!apiId && window.localStorage.getItem(collapseKey) === '1'
  );

  if (!apiId || apiId === 'new' || !item) return null;
  if (!isDraft) return null;
  if (item.state === API_STATE.DEPRECATED || item.state === API_STATE.REMOVED) return null;

  const steps = buildSteps({ item, params });
  const completed = steps.filter((s) => s.completed).length;
  const total = steps.length;
  if (completed === total) return null;

  const handleStepClick = (step) => {
    if (step.completed) return;
    if (step.highlight) {
      // Set the spotlight target BEFORE navigating, so the destination
      // component picks it up on mount.
      signalHighlight.value = step.highlight;
    }
    if (step.onClick) {
      step.onClick();
    } else if (step.to) {
      historyPush(history, location, step.to);
    }
  };

  const toggleCollapsed = () => {
    const next = !collapsed;
    setCollapsed(next);
    window.localStorage.setItem(collapseKey, next ? '1' : '0');
  };

  if (collapsed) {
    return (
      <button
        type="button"
        className="gs-stepper gs-stepper--collapsed"
        onClick={toggleCollapsed}
        data-testid="gs-stepper"
        title="Getting started"
      >
        <i className="fas fa-list-check" />
        <span className="gs-stepper-collapsed-count">
          {completed}/{total}
        </span>
      </button>
    );
  }

  return (
    <div className="gs-stepper" data-testid="gs-stepper" role="region" aria-label="Getting started">
      <header className="gs-stepper-header">
        <div className="gs-stepper-title">
          <i className="fas fa-rocket me-2" />
          <strong>Getting started</strong>
        </div>
        <div className="gs-stepper-actions">
          <span className="gs-stepper-count">
            {completed}/{total}
          </span>
          <button
            type="button"
            className="gs-stepper-iconbtn"
            onClick={toggleCollapsed}
            title="Hide"
            data-testid="gs-stepper-collapse"
          >
            <i className="fas fa-times" />
          </button>
        </div>
      </header>

      <div className="gs-stepper-progress">
        <div
          className="gs-stepper-progress-bar"
          style={{ width: `${(completed / total) * 100}%` }}
        />
      </div>

      <ol className="gs-stepper-steps">
        {steps.map((step) => (
          <li
            key={step.id}
            className={`gs-stepper-step ${step.completed ? 'gs-stepper-step--done' : ''}`}
            data-testid={`gs-step-${step.id}`}
          >
            <button
              type="button"
              className="gs-stepper-step-button"
              onClick={() => handleStepClick(step)}
              disabled={step.completed}
            >
              <span className="gs-stepper-step-checkbox" aria-hidden="true">
                <i className={step.completed ? 'fas fa-check-circle' : 'far fa-circle'} />
              </span>
              <span className="gs-stepper-step-body">
                <span className="gs-stepper-step-title">{step.title}</span>
                <span className="gs-stepper-step-description">{step.description}</span>
              </span>
            </button>
          </li>
        ))}
      </ol>
    </div>
  );
}
