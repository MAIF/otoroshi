import React, { useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import PageTitle from '../../components/PageTitle';
import SimpleLoader from './SimpleLoader';
import { useDraftOfAPI } from './hooks';
import { computeApiQuality, SEVERITY_ORDER } from './quality/rules';

function GlobalGauge({ percent, grade, color }) {
  return (
    <div
      className="api-quality-gauge api-quality-gauge--lg"
      style={{
        background: `conic-gradient(${color} ${percent * 3.6}deg, var(--bg-color_level3, #2a2a2a) 0deg)`,
      }}
    >
      <div className="api-quality-gauge-inner">
        <span className="api-quality-gauge-grade" style={{ color }}>
          {grade}
        </span>
        <span className="api-quality-gauge-percent">{percent}%</span>
      </div>
    </div>
  );
}

function SeverityTab({ severity, count, active, onClick }) {
  const disabled = count === 0;
  return (
    <button
      type="button"
      className={`api-quality-tab ${active ? 'api-quality-tab--active' : ''}`}
      disabled={disabled}
      onClick={() => !disabled && onClick(severity)}
    >
      <span className={`api-quality-sev api-quality-sev--${severity}`}>{severity}</span>
      <span className="api-quality-tab-count">{count}</span>
    </button>
  );
}

export function Quality(props) {
  const params = useParams();
  const location = useLocation();
  const { item } = useDraftOfAPI();
  const [severityFilter, setSeverityFilter] = useState(null);
  const [categoryFilter, setCategoryFilter] = useState(null);

  useEffect(() => {
    props.setTitle?.(undefined);
  }, []);

  const report = useMemo(() => (item ? computeApiQuality(item) : null), [item]);

  if (!item || !report) return <SimpleLoader />;

  const filtered = report.issues
    .filter((i) => (severityFilter ? i.severity === severityFilter : true))
    .filter((i) => (categoryFilter ? i.category === categoryFilter : true))
    .sort((a, b) => SEVERITY_ORDER.indexOf(a.severity) - SEVERITY_ORDER.indexOf(b.severity));

  return (
    <div className="page">
      <PageTitle title="API Quality" />

      <div className="api-quality-page">
        <div className="api-quality-summary">
          <GlobalGauge percent={report.percent} grade={report.grade} color={report.color} />
          <div className="api-quality-cats api-quality-cats--page">
            {report.categories.map((c) => (
              <button
                key={c.key}
                type="button"
                className={`api-quality-cat api-quality-cat--clickable ${
                  categoryFilter === c.key ? 'api-quality-cat--active' : ''
                }`}
                onClick={() =>
                  setCategoryFilter((prev) => (prev === c.key ? null : c.key))
                }
              >
                <div className="api-quality-cat-head">
                  <span className="api-quality-cat-name">{c.label}</span>
                  <span className="api-quality-cat-percent" style={{ color: c.color }}>
                    {c.grade} · {c.percent}%
                  </span>
                </div>
                <div className="api-quality-bar">
                  <div
                    className="api-quality-bar-fill"
                    style={{ width: `${c.percent}%`, backgroundColor: c.color }}
                  />
                </div>
              </button>
            ))}
          </div>
        </div>

        <div className="api-quality-tabs">
          <button
            type="button"
            className={`api-quality-tab ${!severityFilter ? 'api-quality-tab--active' : ''}`}
            onClick={() => setSeverityFilter(null)}
          >
            All
            <span className="api-quality-tab-count">{report.issues.length}</span>
          </button>
          {SEVERITY_ORDER.map((sev) => (
            <SeverityTab
              key={sev}
              severity={sev}
              count={report.counts[sev] || 0}
              active={severityFilter === sev}
              onClick={setSeverityFilter}
            />
          ))}
        </div>

        {filtered.length === 0 ? (
          <p className="api-quality-empty">
            <i className="fas fa-check-circle me-2" style={{ color: '#2ecc71' }} />
            No issue matching the current filter.
          </p>
        ) : (
          <div className="api-quality-table">
            <div className="api-quality-trow api-quality-trow--head">
              <div>Severity</div>
              <div>Category</div>
              <div>Issue</div>
              <div>Recommendation</div>
              <div></div>
            </div>
            {filtered.map((issue) => (
              <div className="api-quality-trow" key={issue.id}>
                <div>
                  <span className={`api-quality-sev api-quality-sev--${issue.severity}`}>
                    {issue.severity}
                  </span>
                </div>
                <div>{issue.categoryLabel}</div>
                <div>{issue.message}</div>
                <div className="api-quality-fix">{issue.fix}</div>
                <div>
                  <Link
                    to={{
                      pathname: `/apis/${params.apiId}/${issue.to}`,
                      search: location.search,
                    }}
                    className="btn btn-sm btn-primaryColor"
                    title="Go fix it"
                  >
                    Fix <i className="fas fa-arrow-right ms-1" />
                  </Link>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
