import React, { useMemo } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { computeApiQuality } from './quality/rules';

// Small radial gauge: a conic-gradient ring around the letter grade.
function ScoreGauge({ percent, grade, color }) {
  return (
    <div
      className="api-quality-gauge"
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

function CategoryRow({ category }) {
  return (
    <div className="api-quality-cat">
      <div className="api-quality-cat-head">
        <span className="api-quality-cat-name">{category.label}</span>
        <span className="api-quality-cat-percent" style={{ color: category.color }}>
          {category.percent}%
        </span>
      </div>
      <div className="api-quality-bar">
        <div
          className="api-quality-bar-fill"
          style={{ width: `${category.percent}%`, backgroundColor: category.color }}
        />
      </div>
    </div>
  );
}

export function ApiQualityCard({ api }) {
  const params = useParams();
  const location = useLocation();
  const report = useMemo(() => computeApiQuality(api), [api]);

  const detailsLink = {
    pathname: `/apis/${params.apiId}/quality`,
    search: location.search,
  };

  const topIssues = report.issues.slice(0, 3);

  return (
    <div className="api-quality-card">
      <div className="api-quality-top">
        <ScoreGauge percent={report.percent} grade={report.grade} color={report.color} />
        <div className="api-quality-cats">
          {report.categories.map((c) => (
            <CategoryRow key={c.key} category={c} />
          ))}
        </div>
      </div>

      {report.issues.length === 0 ? (
        <p className="api-quality-empty">
          <i className="fas fa-check-circle me-2" style={{ color: '#2ecc71' }} />
          No quality issues detected. Nice and complete API definition!
        </p>
      ) : (
        <ul className="api-quality-issues">
          {topIssues.map((issue) => (
            <li key={issue.id} className="api-quality-issue">
              <span className={`api-quality-sev api-quality-sev--${issue.severity}`}>
                {issue.severity}
              </span>
              <span className="api-quality-issue-msg">{issue.message}</span>
            </li>
          ))}
        </ul>
      )}

      <Link to={detailsLink} className="api-quality-details-link">
        View {report.issues.length > 0 ? `all ${report.issues.length} issues` : 'details'}
        <i className="fas fa-chevron-right ms-2" />
      </Link>
    </div>
  );
}
