import React, { useState } from 'react';
import moment from 'moment';

import { Switch, Route } from 'react-router-dom';

import { GREEN_SCORE_GRADES, MAX_GREEN_SCORE_NOTE, getColor, getLetter } from './util';

import { nextClient } from '../../services/BackOfficeServices';

import RulesRadarchart from './RulesRadarchart';
import { GlobalScore } from './GlobalScore';
import StackedBarChart from './StackedBarChart';
import { DynamicChart } from './DynamicChart';
import CustomTable from './CustomTable';
import { ManagerTitle, Tab } from './TitleManager';
import EditGroup from './EditGroup';
import Wrapper from './Wrapper';
import { NgSelectRenderer } from '../../components/nginputs';
import Section from './Section';
import DynamicScore from './DynamicScore';
import { Link } from 'react-router-dom';

function DatePickerSelector({ icon, onClick }) {
  return (
    <div
      style={{
        boxShadow: '0 0 0 1px var(--bg-color_level3,transparent)',
        borderRadius: 8,
        height: 32,
        width: 32,
        cursor: 'pointer',
      }}
      onClick={onClick}
      className="justify-content-center d-flex align-items-center">
      <i className={icon} />
    </div>
  );
}

function ModeWrapper({ mode, value, children }) {
  if (value === mode || mode === 'all') return children;

  return null;
}

function FilterSelector({
  mode,
  onChange,
  filteredGroups,
  open,
  close,
  opened,
  enabledFilters,
  ...props
}) {
  const [state, setState] = useState(mode);

  const [groups, setGroups] = useState(filteredGroups);

  const addToState = (value) => {
    if (state === value) setState();
    else if (state === 'all') setState(value === 'static' ? 'dynamic' : 'static');
    else {
      const values = [...new Set([state, value])].filter((f) => f);

      if (values.includes('dynamic') && values.includes('static')) setState('all');
      else setState(value);
    }
  };

  return (
    <div
      style={{
        position: 'absolute',
        top: -32,
        bottom: 0,
        left: 0,
        right: opened ? 0 : 'inherit',
        zIndex: 100,
        background: opened ? 'var(--bg-color_level1_opa80)' : 'transparent',
        display: 'flex',
        flexDirection: 'column',
      }}>
      <div
        className="date-hover"
        onClick={open}
        style={{
          boxShadow: '0 0 0 1px var(--bg-color_level3,transparent)',
          padding: '.5rem 1rem',
          height: 42,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          width: 145,
          textAlign: 'center',
          cursor: 'pointer',
          borderTopLeftRadius: 8,
          borderTopRightRadius: 8,
          borderBottomLeftRadius: opened ? '0px' : '8px',
          borderBottomRightRadius: opened ? 0 : 8,
          transition: 'border .2s',
        }}>
        <div className="d-flex align-items-center">
          Filters <i className="fas fa-filter ms-1"></i>
        </div>

        <span
          style={{
            background: 'var(--bg-color_level2)',
            padding: '.2rem',
            borderRadius: '25%',
            minWidth: 32,
          }}>
          {enabledFilters}
        </span>
      </div>

      {opened && (
        <div
          style={{
            display: 'flex',
            flex: 1,
            justifyContent: 'start',
          }}>
          <div
            style={{
              zIndex: 12,
              background: 'var(--bg-color_level2)',
              borderRadius: 12,
              opacity: 1,
              borderTopLeftRadius: 0,
              boxShadow: '0 0 0 1px var(--bg-color_level3,transparent)',
              maxWidth: 350,
              minWidth: 350,
            }}
            className="p-3 d-flex flex-column">
            <h3 style={{ color: 'var(--text)' }}>Groups</h3>

            <NgSelectRenderer
              value={groups}
              ngOptions={{
                spread: true,
              }}
              isMulti
              onChange={setGroups}
              options={props.groups}
              optionsTransformer={(groups) => groups.map((g) => ({ label: g.name, value: g.id }))}
            />

            <h3 style={{ color: 'var(--text)' }} className="mt-3">
              Modes
            </h3>
            <div className="d-flex" style={{ gap: '.5rem' }}>
              <div
                onClick={() => addToState('static')}
                className={`d-flex align-items-center justify-content-center p-3 py-2 ${
                  state && state !== 'dynamic' ? 'date-hover--selected' : ''
                }`}
                style={{
                  flex: 1,
                  border: '1px solid var(--color-primary)',
                  color: 'var(--text)',
                  borderRadius: 8,
                  cursor: 'pointer',
                }}>
                STATIC
              </div>
              <div
                onClick={() => addToState('dynamic')}
                className={`d-flex align-items-center justify-content-center p-3 py-2 ${
                  state && state !== 'static' ? 'date-hover--selected' : ''
                }`}
                style={{
                  flex: 1,
                  border: '1px solid var(--color-primary)',
                  color: 'var(--text)',
                  borderRadius: 8,
                  cursor: 'pointer',
                }}>
                DYNAMIC <i className="fas fa-bolt ms-2" />
              </div>
            </div>

            <div className="mt-auto d-flex pt-3" style={{ gap: 8 }}>
              <button
                type="button"
                className="btn p-2"
                onClick={close}
                style={{
                  flex: 1,
                  borderRadius: 8,
                  border: '2px solid var(--bg-color_level3)',
                  color: 'var(--text)',
                }}>
                Cancel
              </button>
              <button
                type="button"
                className="btn p-2"
                onClick={() => onChange(state, groups)}
                style={{
                  flex: 1,
                  borderRadius: 8,
                  background: 'var(--color-primary)',
                  color: 'var(--color-white)',
                }}>
                Apply
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function DatePicker({ date, onChange, options, open, onClose, opened }) {
  const [selectedDate, setSelectedDate] = useState(date);

  const dates = (options || []).map((option) => {
    const date = new Date(option);
    return {
      value: date,
      datetime: option,
      month: date.getUTCMonth() + 1,
      year: date.getUTCFullYear(),
    };
  });

  const months = [
    ...new Set(
      (options || []).map((item) => {
        const d = new Date(item);
        return {
          value: `01/${d.getUTCMonth() + 1}/${d.getUTCFullYear()}`,
          month: d.getUTCMonth() + 1,
          year: d.getUTCFullYear(),
        };
      })
    ),
  ].filter((v, i, a) => a.findIndex((v2) => v2.month === v.month && v2.year === v.year) === i);

  const [currentMonthAndYear, setCurrentMonthAndYear] = useState(
    months.findIndex((m) => m.month === new Date(date).getUTCMonth() + 1)
  );

  const format = (date) => moment(date, 'DD/MM/YYYY').format('MMMM YYYY');

  const goToStart = () => setCurrentMonthAndYear(0);

  const goToEnd = () => setCurrentMonthAndYear(months.length - 1);

  const previous = () =>
    setCurrentMonthAndYear(currentMonthAndYear - 1 < 0 ? 0 : currentMonthAndYear - 1);

  const next = () =>
    setCurrentMonthAndYear(
      currentMonthAndYear + 1 > months.length - 1 ? months.length - 1 : currentMonthAndYear + 1
    );

  return (
    <div
      style={{
        position: 'absolute',
        top: -32,
        bottom: 0,
        left: opened ? 0 : 152,
        right: 0,
        zIndex: 10,
      }}
      className="d-flex flex-column">
      <div
        className="date-hover"
        onClick={open}
        style={{
          boxShadow: '0 0 0 1px var(--bg-color_level3,transparent)',
          padding: '.5rem 1rem',
          height: 42,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: 175,
          textAlign: 'center',
          cursor: 'pointer',
          borderTopLeftRadius: 8,
          borderTopRightRadius: 8,
          borderBottomLeftRadius: opened ? '0px' : '8px',
          borderBottomRightRadius: opened ? 0 : 8,
          transition: 'border .2s',
          marginLeft: opened ? 152 : 0,
        }}>
        <i className="fas fa-calendar me-2" />
        {moment(date).format('DD MMMM YY').toString()}
      </div>

      {opened && (
        <div
          style={{
            display: 'flex',
            flex: 1,
            justifyContent: 'start',
            background: 'var(--bg-color_level1_opa80)',
            marginLeft: 152,
          }}>
          <div
            style={{
              zIndex: 12,
              background: 'var(--bg-color_level2)',
              borderRadius: 12,
              opacity: 1,
              borderTopLeftRadius: 0,
              boxShadow: '0 0 0 1px var(--bg-color_level3,transparent)',
              maxWidth: 350,
              minWidth: 350,
            }}
            className="p-3 d-flex flex-column">
            <div className="d-flex align-items-center justify-content-between" style={{ gap: 6 }}>
              {months.length > 1 && (
                <DatePickerSelector icon="fas fa-angles-left" onClick={goToStart} />
              )}
              {months.length > 1 && (
                <DatePickerSelector icon="fas fa-chevron-left" onClick={previous} />
              )}
              <span
                className="mx-3"
                style={{
                  flex: 1,
                  textAlign: 'center',
                  fontSize: '1.25rem',
                  whiteSpace: 'nowrap',
                }}>
                {format(months[currentMonthAndYear]?.value)}
              </span>
              {months.length > 1 && (
                <DatePickerSelector icon="fas fa-chevron-right" onClick={next} />
              )}
              {months.length > 1 && (
                <DatePickerSelector icon="fas fa-angles-right" onClick={goToEnd} />
              )}
            </div>

            <div className="d-flex flex-wrap mt-3" style={{ gap: 2 }}>
              {dates
                .filter(
                  (date) =>
                    date.month === months[currentMonthAndYear]?.month &&
                    date.year === months[currentMonthAndYear]?.year
                )
                .map((d) => {
                  return (
                    <div
                      key={d.datetime}
                      onClick={() => setSelectedDate(d.datetime)}
                      className={`d-flex align-items-center justify-content-center p-3 py-2 date-hover ${
                        selectedDate === d.datetime ? 'date-hover--selected' : ''
                      }`}
                      style={{
                        border: '1px solid var(--color-primary)',
                        color: 'var(--text)',
                        borderRadius: 4,
                        cursor: 'pointer',
                      }}>
                      {moment(d.value).format('dddd DD')}
                    </div>
                  );
                })}
            </div>

            <div className="mt-auto d-flex" style={{ gap: 8 }}>
              <button
                type="button"
                className="btn p-2"
                onClick={onClose}
                style={{
                  flex: 1,
                  borderRadius: 8,
                  border: '2px solid var(--bg-color_level3)',
                  color: 'var(--text)',
                }}>
                Cancel
              </button>
              <button
                type="button"
                className="btn p-2"
                onClick={() => onChange(selectedDate)}
                style={{
                  flex: 1,
                  borderRadius: 8,
                  background: 'var(--color-primary)',
                  color: 'var(--color-white)',
                }}>
                Apply
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

export default class GreenScoreConfigsPage extends React.Component {
  state = {
    filteredGroups: [],
    filterStatusView: undefined,
    routes: [],
    groups: [],
    rulesBySection: undefined,
    scores: [],
    date: undefined,
    loading: true,
    mode: 'all',
  };

  componentDidMount() {
    this.props.setTitle(`Green Score groups`);

    Promise.all([
      nextClient.forEntity(nextClient.ENTITIES.ROUTES).findAll(),
      fetch('/bo/api/proxy/api/extensions/green-score/template', {
        credentials: 'include',
        headers: {
          Accept: 'application/json',
        },
      }).then((r) => r.json()),
      fetch('/bo/api/proxy/api/extensions/green-score', {
        credentials: 'include',
        headers: {
          Accept: 'application/json',
        },
      }).then((r) => r.json()),
    ]).then(([routes, rulesTemplate, { groups, scores }]) => {
      this.setState({
        routes,
        groups,
        rulesBySection: this.rulesTemplateToRulesBySection(rulesTemplate),
        scores: scores.score_by_route,
        dynamicValues: scores.dynamic_values,
        dynamicValuesByRoutes: scores.dynamic_values_by_routes,
        date: [...new Set(scores.score_by_route.map((section) => section.date))]
          .sort()
          .reverse()[0],
        loading: scores.score_by_route.length <= 0,
      });
    });

    this.props.setTitle(() => <ManagerTitle />);

    document.getElementById('content-scroll-container').addEventListener('scroll', this.reveal);

    setTimeout(this.reveal, 1500);
  }

  calculateForGroups = (newGroups) => {
    fetch('/bo/api/proxy/api/extensions/green-score', {
      credentials: 'include',
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(newGroups.map((g) => g.value)),
    })
      .then((r) => r.json())
      .then(({ scores }) => {
        this.setState({
          scores: scores.score_by_route,
          dynamicValues: scores.dynamic_values,
          dynamicValuesByRoutes: scores.dynamic_values_by_routes,
          date: [...new Set(scores.score_by_route.map((section) => section.date))]
            .sort()
            .reverse()[0],
          loading: scores.score_by_route.length <= 0,
          filterStatusView: 'undefined',
        });
      });
  };

  reveal() {
    const reveals = [...document.querySelectorAll('.reveal')];
    const windowHeight = window.innerHeight;
    const elementVisible = 80;

    reveals.forEach((reveal) => {
      const elementTop = reveal.getBoundingClientRect().top;

      if (elementTop < windowHeight - elementVisible) {
        reveal.classList.add('show');
      }
    });
  }

  rulesTemplateToRulesBySection = (rulesTemplate) => {
    return rulesTemplate.reduce((acc, rule) => {
      if (acc[rule.section]) {
        return {
          ...acc,
          [rule.section]: [...acc[rule.section], rule],
        };
      } else {
        return {
          ...acc,
          [rule.section]: [rule],
        };
      }
    }, {});
  };

  getDynamicScore = (dynamic_values, factor = 1) => {
    const scores = [
      dynamic_values.backendDuration,
      dynamic_values.calls,
      dynamic_values.dataIn,
      dynamic_values.dataOut,
      dynamic_values.headersIn,
      dynamic_values.headersOut,
      dynamic_values.overhead,
      dynamic_values.duration,
    ];

    return scores.reduce((a, i) => a + i, 0) / (scores.length * factor);
  };

  meanThresholds = (a1, length) => {
    return {
      overhead: a1.overhead / length,
      duration: a1.duration / length,
      backendDuration: a1.backendDuration / length,
      calls: a1.calls / length,
      dataIn: a1.dataIn / length,
      dataOut: a1.dataOut / length,
      headersOut: a1.headersOut / length,
      headersIn: a1.headersIn / length,
    };
  };

  getThresholds = () => {
    const DEFAULT = {
      overhead: 0,
      duration: 0,
      backendDuration: 0,
      calls: 0,
      dataIn: 0,
      dataOut: 0,
      headersOut: 0,
      headersIn: 0,
    };

    if (this.state.scores.length > 0)
      return this.meanThresholds(
        this.state.groups.reduce((acc, group) => {
          const sum = group.thresholds;

          return {
            overhead: acc.overhead + sum.overhead.poor,
            duration: acc.duration + sum.duration.poor,
            backendDuration: acc.backendDuration + sum.backendDuration.poor,
            calls: acc.calls + sum.calls.poor,
            dataIn: acc.dataIn + sum.dataIn.poor,
            dataOut: acc.dataOut + sum.dataOut.poor,
            headersOut: acc.headersOut + sum.headersOut.poor,
            headersIn: acc.headersIn + sum.headersIn.poor,
          };
        }, DEFAULT),
        this.state.groups.length
      );
    else {
      return DEFAULT;
    }
  };

  scaling = (value, max) => {
    if (value < max) {
      return value / max;
    }
    return max;
  };

  getCounters = () => {
    if (this.state.dynamicValues)
      return Object.entries(this.state.dynamicValues.counters).reduce(
        (acc, c) => {
          if (c[1].excellent)
            return {
              ...acc,
              excellent: [...acc.excellent, c[0]],
            };
          if (c[1].sufficient)
            return {
              ...acc,
              sufficient: [...acc.sufficient, c[0]],
            };
          else
            return {
              ...acc,
              poor: [...acc.poor, c[0]],
            };
        },
        {
          excellent: [],
          sufficient: [],
          poor: [],
        }
      );

    return {};
  };

  getCountersLength = (dynamicCounters) => {
    if (dynamicCounters)
      return (
        dynamicCounters.excellent?.length +
        dynamicCounters.sufficient?.length +
        dynamicCounters.poor?.length
      );
    else return 0;
  };

  onFiltersChange = (newMode, newGroups) => {
    this.setState(
      {
        mode: newMode,
        filteredGroups: newGroups,
      },
      () => {
        this.calculateForGroups(newGroups);
      }
    );
  };

  groupRoutesByGroupId = (routes) => {
    return routes.reduce((groups, route) => {
      if (groups[route.group_id]) {
        return {
          ...groups,
          [route.group_id]: [...groups[route.group_id], route],
        };
      } else {
        return {
          ...groups,
          [route.group_id]: [route],
        };
      }
    }, {});
  };

  render() {
    const {
      scores,
      filterStatusView,
      groups,
      loading,
      mode,
      filteredGroups,
      dynamicValues,
    } = this.state;

    const availableDates = [...new Set(scores.map((section) => section.date))];
    const closestDate = availableDates.reduce(
      (prev, curr) =>
        Math.abs(curr - this.state.date) < Math.abs(prev - this.state.date) ? curr : prev,
      availableDates[availableDates.length - 1]
    );

    const sectionsAtCurrentDate =
      scores.length > 0
        ? scores.filter((section) => section.date === closestDate).flatMap((r) => r.routes)
        : [];
    const valuesAtCurrentDate =
      scores.length > 0 ? sectionsAtCurrentDate.flatMap((r) => r.sections) : [];
    const scalingDynamicScore =
      scores.length > 0 ? this.getDynamicScore(dynamicValues?.scaling) : 0;

    const thresholds = this.getThresholds();

    const dynamicCounters = this.getCounters();
    const dynamicCountersLength = this.getCountersLength(dynamicCounters);

    return (
      <div style={{ margin: '0 auto' }} className="container-sm">
        <Switch>
          <Route
            exact
            path="/extensions/green-score"
            component={() => (
              <>
                <div
                  style={{
                    minHeight: 320,
                    paddingTop: 50,
                  }}>
                  {scores.length === 0 && (
                    <div className="d-flex flex-column justify-content-center align-items-center m-0 mb-3">
                      <p style={{ fontSize: '2rem', marginBottom: '1rem' }}>
                        Not enough data to display the dashboard
                      </p>
                      <Link
                        to="/extensions/green-score/groups/new"
                        className="btn btn-sm d-flex align-items-center"
                        style={{
                          borderRadius: 6,
                          backgroundColor: 'var(--color-primary)',
                          boxShadow: `0 0 0 1px var(--color-primary,transparent)`,
                          color: 'var(--text)',
                        }}>
                        Start new group
                      </Link>
                    </div>
                  )}
                  <div
                    style={{
                      display: 'flex',
                      flex: 1,
                      gap: '.5rem',
                      marginBottom: '.5rem',
                      position: 'relative',
                    }}>
                    {availableDates.length > 1 && (
                      <DatePicker
                        opened={filterStatusView === 'date'}
                        open={() => this.setState({ filterStatusView: 'date' })}
                        date={this.state.date}
                        onChange={(date) => this.setState({ date, filterStatusView: undefined })}
                        onClose={() => this.setState({ filterStatusView: undefined })}
                        options={availableDates.sort()}
                      />
                    )}

                    {groups.length > 0 && (
                      <FilterSelector
                        enabledFilters={
                          (mode !== 'all' ? 1 : 0) +
                          (this.state.date ? 1 : 0) +
                          (filteredGroups.length > 0 ? 1 : 0)
                        }
                        opened={filterStatusView === 'filter'}
                        close={() => this.setState({ filterStatusView: undefined })}
                        open={() => this.setState({ filterStatusView: 'filter' })}
                        onChange={(mode, filteredGroups) => {
                          this.onFiltersChange(mode, filteredGroups);
                        }}
                        mode={mode}
                        filteredGroups={filteredGroups}
                        groups={groups}
                      />
                    )}

                    <ModeWrapper mode={mode} value="static">
                      <Section
                        full={false}
                        title="Static score"
                        subTitle="Follow the progression of your values, grouped in four score : architecture, design, usage and log">
                        <div className="d-flex" style={{ gap: '.5rem' }}>
                          <GlobalScore
                            loading={loading}
                            letter={getLetter(
                              valuesAtCurrentDate.reduce((acc, v) => v.score.score + acc, 0) /
                                (valuesAtCurrentDate.length / 4)
                            )}
                            color={getColor(
                              valuesAtCurrentDate.reduce((acc, v) => v.score.score + acc, 0) /
                                (valuesAtCurrentDate.length / 4)
                            )}
                          />

                          <Wrapper loading={loading}>
                            <StackedBarChart
                              values={scores.reduce((acc, item) => {
                                if (acc[item.date]) {
                                  return {
                                    ...acc,
                                    [item.date]: [
                                      ...acc[item.date],
                                      ...item.routes.flatMap((r) => r.sections),
                                    ],
                                  };
                                } else {
                                  return {
                                    ...acc,
                                    [item.date]: item.routes.flatMap((r) => r.sections),
                                  };
                                }
                              }, {})}
                            />
                          </Wrapper>

                          <GlobalScore
                            loading={loading}
                            score={valuesAtCurrentDate.reduce(
                              (acc, section) => acc + section.score.score,
                              0
                            )}
                            maxScore={MAX_GREEN_SCORE_NOTE * (valuesAtCurrentDate.length / 4)}
                            raw
                          />
                        </div>
                      </Section>
                    </ModeWrapper>
                  </div>

                  <ModeWrapper mode={mode} value="dynamic">
                    <Section
                      title="Dynamic or static ... all in one place"
                      subTitle="Dynamic and static values are computed and normalized between 0 and 1 for easy comparison.">
                      <RulesRadarchart
                        loading={loading}
                        values={valuesAtCurrentDate}
                        dynamic_values={dynamicValues || {}}
                      />
                    </Section>
                  </ModeWrapper>

                  <ModeWrapper mode={mode} value="dynamic">
                    <Section
                      title="Dynamic KPI"
                      subTitle="The values come from observing traffic on your groups. These values are calculated automatically and live.">
                      <div style={{ display: 'flex', gap: '.5rem' }}>
                        <div
                          style={{
                            flex: 1,
                            gap: '.5rem',
                            display: 'flex',
                            flexDirection: 'column',
                          }}>
                          <DynamicChart
                            loading={loading}
                            title="Dynamic values"
                            values={Object.entries(dynamicValues?.scaling || {})}
                          />
                        </div>
                        <div style={{ gap: '.5rem', display: 'flex', flexDirection: 'column' }}>
                          <GlobalScore
                            loading={loading}
                            letter={String.fromCharCode(65 + (1 - scalingDynamicScore) * 5)}
                            color={
                              Object.keys(GREEN_SCORE_GRADES)[
                                Math.round((1 - scalingDynamicScore) * 5)
                              ]
                            }
                            dynamic
                            title="Dynamic score"
                            tag="dynamic"
                          />
                          <GlobalScore
                            loading={loading}
                            score={scalingDynamicScore * 100}
                            raw
                            dynamic
                            title="Percentage"
                            tag="dynamic"
                          />
                        </div>
                      </div>
                    </Section>
                  </ModeWrapper>

                  <ModeWrapper mode={mode} value="dynamic">
                    <Section
                      title="Dynamic score"
                      subTitle="Your group thresholds are set with three values: excellent, sufficient and poor. Each of the dynamic counters (overhead, backend duration, etc) is placed in one of these values, based ont its last recorded value.">
                      <div style={{ display: 'flex', flex: 1, gap: '.5rem', marginTop: '.5rem' }}>
                        <DynamicScore
                          loading={loading}
                          values={dynamicCounters.excellent}
                          maxScore={dynamicCountersLength}
                          title="Excellent"
                        />
                        <DynamicScore
                          loading={loading}
                          values={dynamicCounters.sufficient}
                          title="Sufficient"
                        />
                        <DynamicScore
                          loading={loading}
                          values={dynamicCounters.poor}
                          title="Poor"
                        />
                      </div>
                    </Section>
                  </ModeWrapper>
                </div>

                <ModeWrapper mode={mode} value="dynamic">
                  <Section
                    title=""
                    subTitle="Thresholds are defined on all groups and are combined into two sections. The first represents the quantity of data exchanged and the second the time spent calculating requests and responses."
                    full={false}>
                    <Wrapper loading={loading}>
                      <div className="d-flex justify-content-around" style={{ gap: '.5rem' }}>
                        {scores.length > 0 &&
                          [
                            [
                              { key: 'overhead', title: 'Overhead', unit: 'ms' },
                              { key: 'duration', title: 'Duration', unit: 'ms' },
                              { key: 'backendDuration', title: 'Backend duration', unit: 'ms' },
                              { key: 'calls', title: 'Calls', unit: 's' },
                            ],
                            [
                              { key: 'dataIn', title: 'Data in', unit: 'bytes' },
                              { key: 'dataOut', title: 'Data out', unit: 'bytes' },
                              { key: 'headersOut', title: 'Headers out', unit: 'bytes' },
                              { key: 'headersIn', title: 'Headers in', unit: 'bytes' },
                            ],
                          ].map((values, j) => (
                            <div
                              className="d-flex flex-column"
                              style={{
                                flex: 1,
                                margin: '0.5rem',
                              }}
                              key={`container${j}`}>
                              <h3 className="text-center my-3 p-3" style={{ color: 'var(--text)' }}>
                                {j === 0 ? 'Time spent calculating' : 'Data exchanged'}
                              </h3>
                              <div
                                style={{
                                  display: 'grid',
                                  gridTemplateColumns: '1fr 1fr',
                                  gridTemplateRows: '1fr 1fr',
                                  gap: '.5rem',
                                  flex: 1,
                                }}>
                                {values.map(({ key, title, unit }) => {
                                  const scalingValue =
                                    Math.abs(
                                      (this.scaling(dynamicValues.raw[key], thresholds[key]) /
                                        thresholds[key]) *
                                        5
                                    ) - 1;
                                  return (
                                    <GlobalScore
                                      className="reveal"
                                      key={key}
                                      loading={loading}
                                      color={
                                        Object.keys(GREEN_SCORE_GRADES)[
                                          Math.round(scalingValue < 0 ? 0 : scalingValue)
                                        ]
                                      }
                                      maxScore={thresholds[key]}
                                      score={dynamicValues.raw[key]}
                                      dynamic
                                      raw
                                      under
                                      unit={unit}
                                      title={title}
                                      tag="dynamic"
                                    />
                                  );
                                })}
                              </div>
                            </div>
                          ))}
                      </div>
                    </Wrapper>
                  </Section>
                </ModeWrapper>
              </>
            )}
          />

          <Route
            exact
            path="/extensions/green-score/groups"
            component={() => (
              <CustomTable
                items={groups}
                routes={this.state.routes}
                dynamicValuesByRoutes={this.state.dynamicValuesByRoutes}
                getDynamicScore={this.getDynamicScore}
                scores={Object.entries(
                  scores.length > 0
                    ? this.groupRoutesByGroupId(scores.sort((a, b) => b.date - a.date)[0].routes)
                    : {}
                ).map((groupInformations) => {
                  const [groupId, routes] = groupInformations;

                  let dynamicValues = this.state.dynamicValuesByRoutes.filter(
                    (f) => f.group_id === groupId
                  );

                  dynamicValues = this.meanThresholds(
                    dynamicValues.reduce(
                      (acc, group) => {
                        const { scaling } = group.dynamic_values;
                        return {
                          overhead: acc.overhead + scaling.overhead,
                          duration: acc.duration + scaling.duration,
                          backendDuration: acc.backendDuration + scaling.backendDuration,
                          calls: acc.calls + scaling.calls,
                          dataIn: acc.dataIn + scaling.dataIn,
                          dataOut: acc.dataOut + scaling.dataOut,
                          headersOut: acc.headersOut + scaling.headersOut,
                          headersIn: acc.headersIn + scaling.headersIn,
                        };
                      },
                      {
                        overhead: 0,
                        duration: 0,
                        backendDuration: 0,
                        calls: 0,
                        dataIn: 0,
                        dataOut: 0,
                        headersOut: 0,
                        headersIn: 0,
                      }
                    ),
                    dynamicValues.length
                  );

                  return {
                    sectionsAtCurrentDate: routes,
                    score:
                      routes.flatMap((r) => r.sections).reduce((acc, v) => v.score.score + acc, 0) /
                      routes.length,
                    ...this.state.groups.find((g) => g.id === groupId),
                    dynamic_values: this.getDynamicScore(dynamicValues),
                  };
                })}
              />
            )}
          />

          <Route exact path="/extensions/green-score/groups/:group_id" component={EditGroup} />
        </Switch>
      </div>
    );
  }
}
