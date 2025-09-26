import React, { useEffect, useState } from 'react';
import { firstLetterUppercase } from '../util';
import { NgAnyRenderer, NgCodeRenderer, NgSelectRenderer } from './nginputs';

const roundNsTo = (ns) => Number.parseFloat(round(ns) / 1000000).toFixed(0);
const round = (num) => Math.round((num + Number.EPSILON) * 100000) / 100000;

export const ReportView = ({
  report,
  search = '',
  setSearch,
  unit = 'ns',
  setUnit,
  sort = 'flow',
  setSort,
  flow = 'all',
  setFlow,
  isWorkflowView,
  onClick,
  error
}) => {
  const [selectedStep, setSelectedStep] = useState(-1);
  const [selectedPlugin, setSelectedPlugin] = useState(-1);
  const [steps, setSteps] = useState([]);
  const [informations, setInformations] = useState({});

  useEffect(() => {
    if (error)
      setSelectedPlugin('error')
  }, [error])

  useEffect(() => {
    const { steps, duration_ns, ...informations } = report;
    setSteps(report.steps);
    setInformations(informations);
  }, [report]);

  const isOnFlow = (step) => {
    if (flow === 'all') return true;
    else if (flow === 'internal')
      return (step.task || !step.ctx?.plugins) && step.task !== 'call-backend';
    // user flow
    else return step.ctx?.plugins?.length > 0 || step.task === 'call-backend';
  };

  const isMatchingSearchWords = (step) =>
    search.length <= 0
      ? true
      : step.task.includes(search) ||
      [...(step?.ctx?.plugins || [])].find((plugin) =>
        search.length <= 0 ? true : plugin.name.includes(search)
      );

  const isPluginNameMatchingSearch = (plugin) =>
    search.length <= 0 ? true : plugin.name.includes(search);

  const sortByFlow = (a, b) => (sort === 'flow' ? 0 : a.duration_ns < b.duration_ns ? 1 : -1);

  const durationInPercentage = (pluginDuration, totalDuration) => {
    return Number.parseFloat((pluginDuration / totalDuration) * 100).toFixed(3);
  }

  const reportDuration = () => {
    if (flow === 'all')
      return unit === 'ms'
        ? roundNsTo(report.duration_ns)
        : unit === 'ns'
          ? report.duration_ns
          : 100;
    else {
      const value = [...steps]
        .filter(isOnFlow)
        .filter(isMatchingSearchWords)
        .reduce((acc, step) => {
          const userPluginsFlow =
            step.ctx && step.ctx.plugins
              ? [...(step.ctx?.plugins || [])]
                .filter(isPluginNameMatchingSearch)
                .reduce((subAcc, step) => subAcc + step.duration_ns, 0)
              : 0;

          if (flow === 'user')
            return acc + (step.task === 'call-backend' ? step.duration_ns : 0) + userPluginsFlow;
          else return acc + step.duration_ns - userPluginsFlow;
        }, 0);

      if (unit === '%') return durationInPercentage(value, report.duration_ns);
      else if (unit === 'ns') return value;
      else return roundNsTo(value);
    }
  }

  return (
    <div className="d-flex border" style={{ flex: 1 }}>
      <div className="main-view px-2 border-r pt-2" style={{ flex: 0.5, minWidth: '300px' }}>
        <NgSelectRenderer
          margin="mb-1"
          value={unit}
          label="Unit"
          onChange={setUnit}
          options={['ns', 'ms', '%'].map((item) => ({ label: item, value: item }))}
        />

        {!isWorkflowView && (
          <NgSelectRenderer
            margin="mb-1"
            value={flow}
            label="Flow"
            onChange={setFlow}
            options={['internal', 'user', 'all'].map((item) => ({
              label: firstLetterUppercase(item),
              value: item,
            }))}
          />
        )}

        {!isWorkflowView && (
          <NgSelectRenderer
            margin="mb-1"
            value={sort}
            label="Sort"
            onChange={setSort}
            options={['flow', 'duration'].map((item) => ({ label: `by ${item}`, value: item }))}
          />
        )}

        {!isWorkflowView && (
          <input
            name="step"
            type="text"
            className="form-control my-1"
            value={search}
            placeholder="Search a step"
            onChange={(e) => setSearch(e.target.value)}
          />
        )}

        <div className="d-flex flex-column">
          <div
            onClick={() => {
              setSelectedStep(-1);
              setSelectedPlugin(-1);

              if (onClick)
                onClick(-1)
            }}
            className={`d-flex-between report-step ${selectedStep === -1 && selectedPlugin === -1 ? 'btn-primaryColor' : 'btn-quiet'}`}
          >
            <span>Report</span>
            <span>
              {reportDuration()} {unit}
            </span>
          </div>

          {error && <div style={{ width: '100%' }} onClick={() => {
            setSelectedPlugin('error')
          }}>
            <div className={`d-flex-between report-step ${selectedPlugin === 'error' ? 'btn-danger' : 'btn-quiet'}`}>
              <div className="d-flex align-items-center">
                <i className="fas fa-warning me-1" />
                <span>{firstLetterUppercase('Error')}</span>
              </div>
            </div>
          </div>}

          {[...steps]
            .filter(isOnFlow)
            .filter(isMatchingSearchWords)
            .sort(sortByFlow)
            .map((step) => {
              const name = step.task.replace(/-/g, ' ');
              const percentage = durationInPercentage(step.duration_ns, report.duration_ns);
              const displaySubList = step.ctx?.plugins?.length > 0 && flow !== 'internal';

              return (
                <div key={step.task} style={{ width: '100%' }}>
                  <div
                    onClick={() => {
                      setSelectedPlugin(-1);
                      setSelectedStep(step.task);

                      if (onClick)
                        onClick(step)
                    }}
                    className={`d-flex-between report-step ${step.task === selectedStep && selectedPlugin === -1 ? 'btn-primaryColor' : 'btn-quiet'}`}
                  >
                    <div className="d-flex align-items-center">
                      {displaySubList && (
                        <i
                          className={`fas fa-chevron-${step.open || flow === 'user' ? 'down' : 'right'
                            } me-1`}
                          onClick={() => {
                            setSteps(
                              steps.map((s) =>
                                s.task === step.task ? { ...s, open: !step.open } : s
                              )
                            )
                          }}
                        />
                      )}
                      <span>{firstLetterUppercase(name)}</span>
                    </div>
                    {(flow !== 'user' || step.task === 'call-backend') && (
                      <span style={{ maxWidth: '100px', textAlign: 'right' }}>
                        {unit === 'ms'
                          ? roundNsTo(step.duration_ns)
                          : unit === 'ns'
                            ? step.duration_ns
                            : percentage}{' '}
                        {unit}
                      </span>
                    )}
                  </div>
                  {(step.open || flow === 'user') &&
                    flow !== 'internal' &&
                    [...(step?.ctx?.plugins || [])]
                      .filter(isPluginNameMatchingSearch)
                      .sort(sortByFlow)
                      .map((plugin) => {
                        const pluginName = plugin.name.replace(/-/g, ' ').split('.').pop();
                        const pluginPercentage = durationInPercentage(
                          plugin.duration_ns,
                          report.duration_ns
                        );

                        return (
                          <div
                            key={plugin.name}
                            style={{
                              width: 'calc(100% - 12px)',
                              marginLeft: '12px',
                            }}
                            onClick={() => {
                              setSelectedStep(step.task);
                              setSelectedPlugin(plugin.name);

                              if (onClick)
                                onClick(step)
                            }}
                            className={`d-flex-between report-step ${step.task === selectedStep && plugin.name === selectedPlugin ? 'btn-primary' : 'btn-quiet'}`}
                          >
                            <span>{firstLetterUppercase(pluginName)}</span>
                            <span style={{ maxWidth: '100px', textAlign: 'right' }}>
                              {unit === 'ms' ? roundNsTo(plugin.duration_ns) : unit === 'ns' ? plugin.duration_ns : pluginPercentage}{' '}
                              {unit}
                            </span>
                          </div>
                        );
                      })}
                </div>
              );
            })}
        </div>
      </div>
      <div style={{ flex: 1, minHeight: 0 }}>
        <NgAnyRenderer
          ngOptions={{ spread: true }}
          onChange={() => { }}
          language='json'
          value={JSON.stringify(
            selectedPlugin === 'error' ? error :
              selectedPlugin === -1 ? selectedStep === -1
                ? informations
                : steps.find((t) => t.task === selectedStep)
                : steps
                  .find((t) => t.task === selectedStep)
                  ?.ctx?.plugins.find((f) => f.name === selectedPlugin),
            null,
            4
          )}
        />
      </div>
    </div>
  );
};
