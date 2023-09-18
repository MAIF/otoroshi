import React, { useState } from 'react';
import moment from 'moment';

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

  const [currentMonthAndYear, setCurrentMonthAndYear] = useState(0);

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
    routes: [],
    groups: [],
    rulesBySection: undefined,
    scores: [],
    date: undefined,
    dateSelectorOpened: false
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
          date: [...new Set(global.sections_score_by_date.map(section => section.date))][0]
        })
      });

    this.props.setTitle(() => <ManagerTitle />);

    document.getElementById('content-scroll-container').addEventListener('scroll', this.reveal)
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

  getAllNormalizedScore = (values, normalized = true) => {
    const field = normalized ? 'normalized_score' : 'score';
    const scores = [
      values.find(v => v.section === "architecture")?.score[field] || 0,
      values.find(v => v.section === "design")?.score[field] || 0,
      values.find(v => v.section === "usage")?.score[field] || 0,
      values.find(v => v.section === "log")?.score[field] || 0
    ];

    return scores.reduce((a, i) => a + i, 0) / scores.length
  }

  getDynamicScore = (dynamic_score) => {
    const scores = [
      dynamic_score.plugins_instance,
      dynamic_score.produced_data,
      dynamic_score.produced_headers
    ];

    return scores.reduce((a, i) => a + i, 0) / scores.length
  }

  // TODO - delete this
  randomDate(s, e) {
    const start = new Date(s);
    const end = new Date(e)
    return new Date(start.getTime() + Math.random() * (end.getTime() - start.getTime())).getTime();
  }

  render() {
    // if (this.state.scores.length > 0)
    //   console.log(this.state)

    const { scores, global, dateSelectorOpened, groups } = this.state;

    const sectionsAtCurrentDate = scores.length > 0 ? global.sections_score_by_date.filter(section => section.date === this.state.date) : [];
    const valuesAtCurrentDate = scores.length > 0 ? sectionsAtCurrentDate : [];
    const normalizedGlobalScore = scores.length > 0 ? this.getAllNormalizedScore(valuesAtCurrentDate) : 0;
    const normalizedDynamicScore = scores.length > 0 ? this.getDynamicScore(global.dynamic_score) : 0;

    return <div style={{ margin: '0 auto' }} className='container-sm'>
      <Switch>
        <Route exact path='/extensions/green-score'
          component={() => <>
            <div style={{
              minHeight: 250,
              paddingTop: 50
            }}>
              {scores.length === 0 && <div className='d-flex flex-column justify-content-center align-items-center m-0 mb-3'>
                <p style={{ fontSize: '2rem', marginBottom: '1rem' }}>No enough data to display the dashboard</p>
                <Tab title="Start New Group" fillBackground to='/extensions/green-score/groups/new' />
              </div>}
              <div style={{
                display: 'flex', flex: 1, gap: '.5rem', marginBottom: '.5rem', position: 'relative'
              }}>
                {scores.length > 0 && <DatePicker
                  opened={dateSelectorOpened}
                  onDateSelectorChange={dateSelectorOpened => this.setState({ dateSelectorOpened })}
                  date={this.state.date}
                  onChange={date => this.setState({ date, dateSelectorOpened: false })}
                  onClose={() => this.setState({ dateSelectorOpened: false })}
                  options={[
                    ...new Set(global?.sections_score_by_date.map(section => section.date)),
                    // ...Array(20).fill(0).map(() => this.randomDate("01/01/2018", "01/09/2023"))
                  ].sort()} />}
                <GlobalScore
                  letter={String.fromCharCode(65 + (1 - normalizedGlobalScore) * 5)}
                  color={Object.keys(GREEN_SCORE_GRADES)[Math.round((1 - normalizedGlobalScore) * 5)]} />
                <RulesRadarchart
                  values={valuesAtCurrentDate}
                  dynamic_score={global?.dynamic_score || {}} />
                <GlobalScore
                  score={sectionsAtCurrentDate.reduce((acc, section) => acc + section.score.score, 0)}
                  maxScore={MAX_GREEN_SCORE_NOTE * sectionsAtCurrentDate.length}
                  raw />
              </div>
              <div style={{ display: 'flex', flex: 1, gap: '.5rem' }}>
                <GlobalScore
                  letter={String.fromCharCode(65 + (1 - normalizedDynamicScore) * 5)}
                  color={Object.keys(GREEN_SCORE_GRADES)[Math.round((1 - normalizedDynamicScore) * 5)]}
                  dynamic
                  title="Produced data"
                  tag="dynamic" />
                <GlobalScore score={normalizedDynamicScore * 100} raw dynamic title="Net score" tag="dynamic" />
                <DynamicChart values={global?.dynamic_score} />
              </div>
            </div>

            <Wrapper>
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
                dynamic_score: this.getDynamicScore(group.dynamic_score)
              }
            })} />} />

        <Route exact path="/extensions/green-score/groups/:group_id"
          component={EditGroup} />
      </Switch>
    </div>
  }
}