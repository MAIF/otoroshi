import React, { useState, useMemo } from 'react';
import styles from './BuiltInPlugins.module.css';
import pluginsData from '../../plugins.json';

function PluginCard({ plugin }) {
  const [expanded, setExpanded] = useState(false);
  const hasConfig = plugin.default_config && Object.keys(plugin.default_config).length > 0;

  return (
    <div
      className={`${styles.pluginCard} ${expanded ? styles.pluginCardExpanded : ''}`}
      onClick={() => setExpanded(!expanded)}
    >
      <h3 className={styles.pluginName}>{plugin.name}</h3>

      <div className={styles.stepsLabel}>Defined on steps</div>
      <div className={styles.steps}>
        {(plugin.plugin_steps || []).map((step, i) => (
          <span key={i} className={styles.step}>{step}</span>
        ))}
      </div>

      <p className={styles.pluginDescription}>{plugin.description}</p>

      {expanded && (
        <div className={styles.expandedDetails}>
          <div className={styles.detailSection}>
            <div className={styles.detailLabel}>Plugin reference</div>
            <div className={styles.pluginRef}>{plugin.id}</div>
          </div>

          {plugin.plugin_categories && plugin.plugin_categories.length > 0 && (
            <div className={styles.detailSection}>
              <div className={styles.detailLabel}>Categories</div>
              <div className={styles.categories}>
                {plugin.plugin_categories.map((cat, i) => (
                  <span key={i} className={styles.category}>{cat}</span>
                ))}
              </div>
            </div>
          )}

          {hasConfig && (
            <div className={styles.detailSection}>
              <div className={styles.detailLabel}>Default configuration</div>
              <div className={styles.configBlock}>
                {JSON.stringify(plugin.default_config, null, 2)}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export default function BuiltInPlugins() {
  const [search, setSearch] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('');
  const [stepFilter, setStepFilter] = useState('');

  const allCategories = useMemo(() => {
    const cats = new Set();
    pluginsData.forEach(p => (p.plugin_categories || []).forEach(c => cats.add(c)));
    return Array.from(cats).sort();
  }, []);

  const allSteps = useMemo(() => {
    const steps = new Set();
    pluginsData.forEach(p => (p.plugin_steps || []).forEach(s => steps.add(s)));
    return Array.from(steps).sort();
  }, []);

  const filtered = useMemo(() => {
    const q = search.toLowerCase().trim();
    return pluginsData.filter(p => {
      if (q && !p.name.toLowerCase().includes(q) && !p.description.toLowerCase().includes(q) && !p.id.toLowerCase().includes(q)) {
        return false;
      }
      if (categoryFilter && !(p.plugin_categories || []).includes(categoryFilter)) {
        return false;
      }
      if (stepFilter && !(p.plugin_steps || []).includes(stepFilter)) {
        return false;
      }
      return true;
    });
  }, [search, categoryFilter, stepFilter]);

  return (
    <div>
      <div className={styles.toolbar}>
        <input
          className={styles.searchInput}
          type="text"
          placeholder="Search plugins by name, description or reference..."
          value={search}
          onChange={e => setSearch(e.target.value)}
        />
        <select
          className={styles.selectFilter}
          value={categoryFilter}
          onChange={e => setCategoryFilter(e.target.value)}
        >
          <option value="">All categories</option>
          {allCategories.map(c => (
            <option key={c} value={c}>{c}</option>
          ))}
        </select>
        <select
          className={styles.selectFilter}
          value={stepFilter}
          onChange={e => setStepFilter(e.target.value)}
        >
          <option value="">All steps</option>
          {allSteps.map(s => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
        <div className={styles.resultCount}>
          {filtered.length} plugin{filtered.length !== 1 ? 's' : ''}
        </div>
      </div>

      <div className={styles.pluginsGrid}>
        {filtered.length === 0 && (
          <div className={styles.noResults}>No plugins match your filters.</div>
        )}
        {filtered.map((plugin, idx) => (
          <PluginCard key={plugin.id || idx} plugin={plugin} />
        ))}
      </div>
    </div>
  );
}
