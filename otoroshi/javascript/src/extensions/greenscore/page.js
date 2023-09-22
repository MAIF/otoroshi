import React, { useState } from 'react';
import moment from 'moment';
import ReactSelect from 'react-select'

import { Switch, Route } from 'react-router-dom';

import { GREEN_SCORE_GRADES, MAX_GREEN_SCORE_NOTE } from './util';

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

function DatePickerSelector({ icon, onClick }) {
  return <div style={{
    boxShadow: '0 0 0 1px var(--bg-color_level3,transparent)',
    borderRadius: 8, height: 32, width: 32, cursor: 'pointer'
  }}
    onClick={onClick}
    className='justify-content-center d-flex align-items-center'>
    <i className={icon} />
  </div>
}

function ModeWrapper({ mode, value, children }) {
  if (value === mode || mode === "all")
    return children

  return null
}

function FilterSelector({ mode, onChange, ...props }) {
  const [open, setOpen] = useState(false);
  const [state, setState] = useState(mode);

  const [groups, setGroups] = useState(props.groups)

  const addToState = value => {
    if (state === value)
      setState()
    else if (state === 'all')
      setState(value === 'static' ? 'dynamic' : 'static');
    else {
      const values = [...new Set([state, value])].filter(f => f)

      if (values.includes('dynamic') && values.includes('static'))
        setState('all')
      else
        setState(value)
    }
  }

  return <div style={{
    position: 'absolute',
    top: -52,
    bottom: 0,
    left: 0,
    right: 0,
    zIndex: 10,
    background: open ? 'var(--bg-color_level1_opa80)' : 'transparent',
    display: 'flex',
    flexDirection: 'column'
  }}>
    <div className='date-hover'
      onClick={() => setOpen(true)}
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
        borderBottomLeftRadius: open ? '0px' : '8px',
        borderBottomRightRadius: open ? 0 : 8,
        transition: 'border .2s'
      }}>Filters <i className='fas fa-filter ms-1'></i></div>

    {open && <div style={{
      display: 'flex',
      flex: 1,
      justifyContent: 'start'
    }}>
      <div style={{
        zIndex: 12,
        background: 'var(--bg-color_level2)',
        borderRadius: 12,
        opacity: 1,
        borderTopLeftRadius: 0,
        boxShadow: '0 0 0 1px var(--bg-color_level3,transparent)',
        maxWidth: 350,
        minWidth: 350,
      }}
        className='p-3 d-flex flex-column'>
        <h3 style={{ color: 'var(--text)' }}>Modes</h3>
        <div className='d-flex' style={{ gap: '.5rem' }}>
          <div
            onClick={() => addToState('static')}
            className={`d-flex align-items-center justify-content-center p-3 py-2 ${(state && state !== 'dynamic') ? 'date-hover--selected' : ''}`}
            style={{
              flex: 1,
              border: '1px solid var(--color-primary)',
              color: 'var(--text)',
              borderRadius: 8,
              cursor: 'pointer'
            }}>STATIC <i className='fas fa-spa ms-2' /></div>
          <div
            onClick={() => addToState('dynamic')}
            className={`d-flex align-items-center justify-content-center p-3 py-2 ${(state && state !== 'static') ? 'date-hover--selected' : ''}`}
            style={{
              flex: 1,
              border: '1px solid var(--color-primary)',
              color: 'var(--text)',
              borderRadius: 8,
              cursor: 'pointer'
            }}>DYNAMIC <i className='fas fa-bolt ms-2' /></div>
        </div>
        <h3 style={{ color: 'var(--text)' }} className='mt-3'>Groups</h3>

        <NgSelectRenderer
          value={groups}
          ngOptions={{
            spread: true
          }}
          isMulti
          name="colors"
          onChange={setGroups}
          options={props.groups.map(g => ({ label: g.name, value: g.id }))}
          className="basic-multi-select"
          classNamePrefix="select" />

        <div className='mt-auto d-flex pt-3' style={{ gap: 8 }}>
          <button type="button" className='btn p-2' onClick={() => setOpen(false)} style={{
            flex: 1,
            borderRadius: 8,
            border: '2px solid var(--bg-color_level3)',
            color: 'var(--text)'
          }}>Cancel</button>
          <button type="button" className='btn p-2'
            onClick={() => onChange(state, groups)}
            style={{
              flex: 1,
              borderRadius: 8,
              background: 'var(--color-primary)',
              color: 'var(--color-white)'
            }}>Apply</button>
        </div>
      </div>
    </div>}
  </div>
}

function DatePicker({ date, onChange, options, onDateSelectorChange, onClose, opened }) {
  const [selectedDate, setSelectedDate] = useState(date);

  const dates = (options || []).map(option => {
    const date = new Date(option);
    return {
      value: date,
      datetime: option,
      month: date.getUTCMonth() + 1,
      year: date.getUTCFullYear()
    }
  })

  const months = [...new Set((options || []).map(item => {
    const d = new Date(item);
    return {
      value: `01/${d.getUTCMonth() + 1}/${d.getUTCFullYear()}`,
      month: d.getUTCMonth() + 1,
      year: d.getUTCFullYear()
    }
  }))]
    .filter((v, i, a) => a.findIndex(v2 => (v2.month === v.month && v2.year === v.year)) === i);

  const [currentMonthAndYear, setCurrentMonthAndYear] = useState(months.findIndex(m => m.month === (new Date(date).getUTCMonth() + 1)));

  const format = date => moment(date, "DD/MM/YYYY").format("MMMM YYYY");

  const goToStart = () => setCurrentMonthAndYear(0);

  const goToEnd = () => setCurrentMonthAndYear(months.length - 1);

  const previous = () => setCurrentMonthAndYear(currentMonthAndYear - 1 < 0 ? 0 : currentMonthAndYear - 1);

  const next = () => setCurrentMonthAndYear(currentMonthAndYear + 1 > months.length - 1 ? months.length - 1 : currentMonthAndYear + 1);

  return <div style={{
    position: 'absolute',
    top: -52,
    bottom: 0,
    left: 0,
    right: 0,
    zIndex: 10,
  }} className='d-flex flex-column'>
    <div className='date-hover'
      onClick={() => onDateSelectorChange(true)}
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
        transition: 'border .2s'
      }}><i className='fas fa-calendar me-2' />{moment(date).format("DD MMMM YY").toString()}</div>

    {opened && <div style={{
      display: 'flex',
      flex: 1,
      justifyContent: 'start',
      background: 'var(--bg-color_level1_opa80)'
    }}>
      <div style={{
        zIndex: 12,
        background: 'var(--bg-color_level2)',
        borderRadius: 12,
        opacity: 1,
        borderTopLeftRadius: 0,
        boxShadow: '0 0 0 1px var(--bg-color_level3,transparent)',
        maxWidth: 350,
        minWidth: 350,
      }}
        className='p-3 d-flex flex-column'>
        <div className='d-flex align-items-center justify-content-between' style={{ gap: 6 }}>
          {months.length > 1 && <DatePickerSelector icon='fas fa-angles-left' onClick={goToStart} />}
          {months.length > 1 && <DatePickerSelector icon='fas fa-chevron-left' onClick={previous} />}
          <span className='mx-3'
            style={{
              flex: 1,
              textAlign: 'center',
              fontSize: '1.25rem',
              whiteSpace: 'nowrap'
            }}>{format(months[currentMonthAndYear]?.value)}</span>
          {months.length > 1 && <DatePickerSelector icon='fas fa-chevron-right' onClick={next} />}
          {months.length > 1 && <DatePickerSelector icon='fas fa-angles-right' onClick={goToEnd} />}
        </div>

        <div className='d-flex flex-wrap mt-3' style={{ gap: 12 }}>
          {dates
            .filter(date => date.month === months[currentMonthAndYear]?.month && date.year === months[currentMonthAndYear]?.year)
            .map(d => {
              return <div key={d.datetime}
                onClick={() => setSelectedDate(d.datetime)}
                className={`d-flex align-items-center justify-content-center p-3 py-2 date-hover ${selectedDate === d.datetime ? 'date-hover--selected' : ''}`}
                style={{
                  border: '1px solid var(--color-primary)',
                  color: 'var(--text)',
                  borderRadius: 8,
                  cursor: 'pointer'
                }}>{moment(d.value).format("dddd DD")}</div>
            })
          }
        </div>

        <div className='mt-auto d-flex' style={{ gap: 8, }}>
          <button type="button" className='btn p-2' onClick={onClose} style={{
            flex: 1,
            borderRadius: 8,
            border: '2px solid var(--bg-color_level3)',
            color: 'var(--text)'
          }}>Cancel</button>
          <button type="button" className='btn p-2'
            onClick={() => onChange(selectedDate)}
            style={{
              flex: 1,
              borderRadius: 8,
              background: 'var(--color-primary)',
              color: 'var(--color-white)'
            }}>Apply</button>
        </div>
      </div>
    </div>
    }
  </div >
}

export default class GreenScoreConfigsPage extends React.Component {
  state = {
    filteredGroups: [],
    routes: [],
    groups: [],
    rulesBySection: undefined,
    scores: [],
    date: undefined,
    dateSelectorOpened: false,
    loading: true,
    mode: 'all'
  };

  componentDidMount() {
    this.props.setTitle(`Green Score groups`);

    Promise.all([
      nextClient
        .forEntity(nextClient.ENTITIES.ROUTES)
        .findAll(),
      fetch('/bo/api/proxy/api/extensions/green-score/template', {
        credentials: 'include',
        headers: {
          Accept: 'application/json',
        },
      })
        .then((r) => r.json()),
      fetch('/bo/api/proxy/api/extensions/green-score', {
        credentials: 'include',
        headers: {
          Accept: 'application/json',
        },
      })
        .then((r) => r.json())
    ])
      .then(([routes, rulesTemplate, { scores, global, groups }]) => {
        this.setState({
          routes,
          groups,
          rulesBySection: this.rulesTemplateToRulesBySection(rulesTemplate),
          scores,
          global,
          date: [...new Set(global.sections_score_by_date.map(section => section.date))][0],
          loading: scores.length <= 0
        })
      });

    this.props.setTitle(() => <ManagerTitle />);

    document.getElementById('content-scroll-container').addEventListener('scroll', this.reveal)
  }

  calculateForGroups = newGroups => {
    fetch('/bo/api/proxy/api/extensions/green-score', {
      credentials: 'include',
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(newGroups.map(g => g.value))
    })
      .then((r) => r.json())
      .then(console.log)
  }

  reveal() {
    const reveals = [...document.querySelectorAll(".reveal")];
    const windowHeight = window.innerHeight;
    const elementVisible = 120;

    reveals.forEach(reveal => {
      const elementTop = reveal.getBoundingClientRect().top;

      if (elementTop < windowHeight - elementVisible) {
        reveal.classList.add("show");
      }
    })
  }

  rulesTemplateToRulesBySection = rulesTemplate => {
    return rulesTemplate.reduce((acc, rule) => {
      if (acc[rule.section]) {
        return {
          ...acc,
          [rule.section]: [...acc[rule.section], rule]
        }
      } else {
        return {
          ...acc,
          [rule.section]: [rule]
        }
      }
    }, {})
  }

  getAllScalingScore = (values, scaling = true) => {
    const field = scaling ? 'scaling_score' : 'score';
    const scores = [
      values.find(v => v.section === "architecture")?.score[field] || 0,
      values.find(v => v.section === "design")?.score[field] || 0,
      values.find(v => v.section === "usage")?.score[field] || 0,
      values.find(v => v.section === "log")?.score[field] || 0
    ];

    return scores.reduce((a, i) => a + i, 0) / scores.length
  }

  getDynamicScore = (dynamic_values) => {
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

    return scores.reduce((a, i) => a + i, 0) / scores.length
  }

  // TODO - delete this
  randomDate(s, e) {
    const start = new Date(s);
    const end = new Date(e)
    return new Date(start.getTime() + Math.random() * (end.getTime() - start.getTime())).getTime();
  }

  mergeThresholds = (a1, a2) => {
    return {
      overhead: a1.overhead + a2.rulesConfig.thresholds.overhead.poor,
      duration: a1.duration + a2.rulesConfig.thresholds.duration.poor,
      backendDuration: a1.backendDuration + a2.rulesConfig.thresholds.backendDuration.poor,
      calls: a1.calls + a2.rulesConfig.thresholds.calls.poor,
      dataIn: a1.dataIn + a2.rulesConfig.thresholds.dataIn.poor,
      dataOut: a1.dataOut + a2.rulesConfig.thresholds.dataOut.poor,
      headersOut: a1.headersOut + a2.rulesConfig.thresholds.headersOut.poor,
      headersIn: a1.headersIn + a2.rulesConfig.thresholds.headersIn.poor
    }
  }

  meanThresholds = (a1, length) => {
    return {
      overhead: Math.round(a1.overhead / length),
      duration: Math.round(a1.duration / length),
      backendDuration: Math.round(a1.backendDuration / length),
      calls: Math.round(a1.calls / length),
      dataIn: Math.round(a1.dataIn / length),
      dataOut: Math.round(a1.dataOut / length),
      headersOut: Math.round(a1.headersOut / length),
      headersIn: Math.round(a1.headersIn / length)
    }
  }

  getThresholds = () => {
    const DEFAULT = {
      overhead: 0, duration: 0, backendDuration: 0, calls: 0, dataIn: 0, dataOut: 0, headersOut: 0, headersIn: 0
    };

    if (this.state.scores.length > 0)
      return this.meanThresholds(
        this.state.groups.reduce((acc, group) => {
          const sum = this.meanThresholds(
            group.routes.reduce((acc, route) => this.mergeThresholds(acc, route), DEFAULT),
            group.routes?.length || 1
          );

          return {
            overhead: acc.overhead + sum.overhead,
            duration: acc.duration + sum.duration,
            backendDuration: acc.backendDuration + sum.backendDuration,
            calls: acc.calls + sum.calls,
            dataIn: acc.dataIn + sum.dataIn,
            dataOut: acc.dataOut + sum.dataOut,
            headersOut: acc.headersOut + sum.headersOut,
            headersIn: acc.headersIn + sum.headersIn
          }
        }, DEFAULT),
        this.state.groups.length)

    else {
      return DEFAULT
    }
  }

  scaling = (value, max) => {
    if (value < max) {
      return value / max
    }
    return max
  }

  getCounters = () => {
    if (this.state.global?.dynamic_values)
      return Object.values(this.state.global.dynamic_values.counters).reduce((acc, c) => {
        return {
          excellent: acc.excellent + c.excellent,
          sufficient: acc.sufficient + c.sufficient,
          poor: acc.poor + c.poor,
        }
      }, {
        excellent: 0, sufficient: 0, poor: 0
      });

    return {}
  }

  getCountersLength = () => {
    if (this.state.global)
      return Object.values(this.state.global.dynamic_values.counters)
        .reduce((acc, c) => acc + c.excellent + c.sufficient + c.poor, 0);
    else
      return 0
  }

  onFiltersChange = (newMode, newGroups) => {
    this.setState({
      mode: newMode,
      filteredGroups: newGroups
    }, () => {
      this.calculateForGroups(newGroups)
    })
  }

  render() {
    const { scores, global, dateSelectorOpened, groups, loading, mode, filteredGroups } = this.state;

    console.log(this.state)

    const sectionsAtCurrentDate = scores.length > 0 ? global.sections_score_by_date.filter(section => section.date === this.state.date) : [];
    const valuesAtCurrentDate = scores.length > 0 ? sectionsAtCurrentDate : [];
    const scalingGlobalScore = scores.length > 0 ? this.getAllScalingScore(valuesAtCurrentDate) : 0;
    const scalingDynamicScore = scores.length > 0 ? this.getDynamicScore(global.dynamic_values.scaling) : 0;
    const availableDates = [...new Set(global?.sections_score_by_date.map(section => section.date))];

    const thresholds = this.getThresholds();

    const dynamicCounters = this.getCounters();
    const dynamicCountersLength = this.getCountersLength();

    return <div style={{ margin: '0 auto' }} className='container-sm'>
      <Switch>
        <Route exact path='/extensions/green-score'
          component={() => <>
            <div style={{
              minHeight: 320,
              paddingTop: 50
            }}>
              {scores.length === 0 && <div className='d-flex flex-column justify-content-center align-items-center m-0 mb-3'>
                <p style={{ fontSize: '2rem', marginBottom: '1rem' }}>No enough data to display the dashboard</p>
                <Tab title="Start New Group" fillBackground to='/extensions/green-score/groups/new' />
              </div>}
              <div style={{
                display: 'flex',
                flex: 1,
                gap: '.5rem',
                marginBottom: '.5rem',
                position: 'relative',
                // minHeight: 380
              }}>

                {availableDates.length > 1 && <DatePicker
                  opened={dateSelectorOpened}
                  onDateSelectorChange={dateSelectorOpened => this.setState({ dateSelectorOpened })}
                  date={this.state.date}
                  onChange={date => this.setState({ date, dateSelectorOpened: false })}
                  onClose={() => this.setState({ dateSelectorOpened: false })}
                  options={[
                    ...availableDates,
                    // ...Array(20).fill(0).map(() => this.randomDate("01/01/2018", "01/09/2023"))
                  ].sort()} />}

                <FilterSelector onChange={(mode, filteredGroups) => {
                  this.onFiltersChange(mode, filteredGroups)
                }} mode={mode} groups={groups} />

                <ModeWrapper mode={mode} value="static">
                  <GlobalScore
                    loading={loading}
                    letter={String.fromCharCode(65 + Math.min(Math.floor((1 - scalingGlobalScore) * 5)))}
                    color={Object.keys(GREEN_SCORE_GRADES)[Math.round((1 - scalingGlobalScore) * 5)]} />

                  <RulesRadarchart
                    loading={loading}
                    values={valuesAtCurrentDate}
                    dynamic_values={global?.dynamic_values || {}} />
                  <GlobalScore
                    loading={loading}
                    score={sectionsAtCurrentDate.reduce((acc, section) => acc + section.score.score, 0)}
                    maxScore={MAX_GREEN_SCORE_NOTE * groups.reduce((acc, i) => acc + i.routes.length, 0)}
                    raw />
                </ModeWrapper>
              </div>

              <ModeWrapper mode={mode} value="dynamic">
                <div style={{ display: 'flex', flex: 1, gap: '.5rem' }}>
                  <div style={{ flex: 1, gap: '.5rem', display: 'flex', flexDirection: 'column' }}>
                    <DynamicChart
                      loading={loading}
                      title="Dynamic values"
                      values={Object.entries(global?.dynamic_values.scaling || {})} />
                  </div>
                  <div style={{ gap: '.5rem', display: 'flex', flexDirection: 'column' }}>
                    <GlobalScore
                      loading={loading}
                      letter={String.fromCharCode(65 + (1 - scalingDynamicScore) * 5)}
                      color={Object.keys(GREEN_SCORE_GRADES)[Math.round((1 - scalingDynamicScore) * 5)]}
                      dynamic
                      title="Data"
                      tag="dynamic" />
                    <GlobalScore loading={loading} score={scalingDynamicScore * 100} raw dynamic title="Net score" tag="dynamic" />
                  </div>
                </div>
              </ModeWrapper>

              <ModeWrapper mode={mode} value="dynamic">
                <div style={{ display: 'flex', flex: 1, gap: '.5rem', marginTop: '.5rem' }}>
                  {/* global.dynamic_values.counters */}
                  <GlobalScore
                    className='reveal'
                    loading={loading}
                    score={dynamicCounters.excellent}
                    maxScore={dynamicCountersLength}
                    dynamic
                    raw
                    unit=" "
                    title="Excellent"
                    tag="dynamic" />
                  <GlobalScore
                    className='reveal'
                    loading={loading}
                    score={dynamicCounters.sufficient}
                    dynamic
                    raw
                    unit=" "
                    title="Sufficient"
                    tag="dynamic" />
                  <GlobalScore
                    className='reveal'
                    loading={loading}
                    score={dynamicCounters.poor}
                    dynamic
                    raw
                    unit=" "
                    title="Poor"
                    tag="dynamic" />
                </div>
              </ModeWrapper>
            </div>

            <ModeWrapper mode={mode} value="static">
              <Wrapper loading={loading}>
                <div style={{
                  display: 'flex',
                  margin: '.5rem 0'
                }} className='reveal'>
                  <StackedBarChart values={global?.sections_score_by_date.reduce((acc, item) => {
                    if (acc[item.date]) {
                      return {
                        ...acc,
                        [item.date]: [...acc[item.date], item]
                      }
                    } else {
                      return {
                        ...acc,
                        [item.date]: [item]
                      }
                    }
                  }, {})} />
                </div>
              </Wrapper>

            </ModeWrapper>
            <ModeWrapper mode={mode} value="dynamic">
              <Wrapper loading={loading}>
                {scores.length > 0 ? [
                  [
                    { key: "overhead", title: 'Overhead', unit: 'ms' },
                    { key: "duration", title: 'Duration', unit: 'ms' },
                    { key: "backendDuration", title: 'Backend duration', unit: 'ms' },
                    { key: "calls", title: 'Calls', unit: 's' },
                  ],
                  [
                    { key: "dataIn", title: 'Data in', unit: 'bytes' },
                    { key: "dataOut", title: 'Data out', unit: 'bytes' },
                    { key: "headersOut", title: 'Headers out', unit: 'bytes' },
                    { key: "headersIn", title: 'Headers in', unit: 'bytes' },
                  ]
                ].map((values, j) => <div className='d-flex justify-content-between' style={{ margin: '.5rem 0', flex: 1, gap: '.5rem' }} key={`container${j}`}>
                  {values
                    .map(({ key, title, unit }) => {
                      const scalingValue = Math.abs((this.scaling(global.dynamic_values.raw[key], thresholds[key]) / thresholds[key]) * 5) - 1;
                      return <GlobalScore
                        className='reveal'
                        key={key}
                        loading={loading}
                        color={Object.keys(GREEN_SCORE_GRADES)[Math.round(scalingValue < 0 ? 0 : scalingValue)]}
                        maxScore={thresholds[key]}
                        score={global.dynamic_values.raw[key]}
                        dynamic
                        raw
                        unit={unit}
                        title={title}
                        tag="dynamic" />
                    })}
                </div>) : <div></div>}
              </Wrapper>

            </ModeWrapper>
          </>} />

        <Route exact path='/extensions/green-score/groups'
          component={() => <CustomTable items={groups}
            scores={scores.map(group => {
              const atDate = group.sections_score_by_date.filter(section => section.date === this.state.date);
              const t = [
                atDate.find(v => v.section === "architecture")?.score["score"] || 0,
                atDate.find(v => v.section === "design")?.score["score"] || 0,
                atDate.find(v => v.section === "usage")?.score["score"] || 0,
                atDate.find(v => v.section === "log")?.score["score"] || 0
              ];
              return {
                sectionsAtCurrentDate: group.sections_score_by_date.filter(section => section.date === this.state.date),
                score: t.reduce((a, i) => a + i, 0),
                ...group,
                dynamic_values: this.getDynamicScore(group.dynamic_values.scaling)
              }
            })} />} />

        <Route exact path="/extensions/green-score/groups/:group_id"
          component={EditGroup} />
      </Switch>
    </div >
  }
}