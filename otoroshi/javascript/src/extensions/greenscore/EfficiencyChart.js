import React, { useEffect, useState } from 'react';
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, XAxis, YAxis } from 'recharts';
import { Popover } from 'antd';
import moment from 'moment';

import { nextClient } from '../../services/BackOfficeServices';
import { GlobalScore } from './GlobalScore';
import Section from './Section'
import { humanMillisecond } from '../../util'
//todo: refactor to use same fucntion than green score ???

export const MAX_GREEN_SCORE_NOTE = 168;

export const GREEN_SCORE_GRADES = {
  '#2ecc71': (rank) => rank >= MAX_GREEN_SCORE_NOTE - Math.ceil(MAX_GREEN_SCORE_NOTE * 0.1),
  '#27ae60': (rank) => rank < MAX_GREEN_SCORE_NOTE - Math.ceil(MAX_GREEN_SCORE_NOTE * 0.1) && rank >= Math.ceil(MAX_GREEN_SCORE_NOTE * 0.5),
  '#f1c40f': (rank) => rank < Math.ceil(MAX_GREEN_SCORE_NOTE * 0.9) && rank >= Math.ceil(MAX_GREEN_SCORE_NOTE * 0.5),
  '#d35400': (rank) => rank < Math.ceil(MAX_GREEN_SCORE_NOTE * 0.5) && rank >= Math.ceil(MAX_GREEN_SCORE_NOTE * 0.1),
  '#c0392b': (rank) => rank < Math.ceil(MAX_GREEN_SCORE_NOTE * 0.1),
};

export function getColorFromLetter(letter) {
  return Object.keys(GREEN_SCORE_GRADES)[letter.charCodeAt(0) - 65];
}

export function getLetter(score) {
  const rankIdx = Object.entries(GREEN_SCORE_GRADES).findIndex((grade) => grade[1](score));
  return String.fromCharCode(65 + rankIdx);
}

export function getColor(score) {
  const rankIdx = Object.entries(GREEN_SCORE_GRADES).findIndex((grade) => grade[1](score));
  return rankIdx === -1 ? 'Not evaluated' : Object.keys(GREEN_SCORE_GRADES)[rankIdx];
}

const visualizationMode = {
  heat: 'HEATMAP',
  score: 'SCORE',
  graphs: 'GRAPHS'
}

export const EfficiencyChart = (props) => {
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState([]);
  const [route, setRoute] = useState();

  const [mode, setMode] = useState(visualizationMode.heat)
  const [day, setDay] = useState();

  useEffect(() => {
    setLoading(true)
    nextClient.forEntity(nextClient.ENTITIES.ROUTES).findById(props.route)
      .then((r) => {
        setRoute(r)
      })
      .then(getDataForSevenLastDays)
      .then(() => setLoading(false))
      .catch(e => {
        setLoading(false)
      })
  }, []);

  const getDataForSevenLastDays = () => {
    return fetch(`/bo/api/proxy/api/extensions/green-score/efficiency/${props.group}/${props.route}`, {
      credentials: 'include',
      headers: {
        Accept: ' application/json'
      }
    })
      .then((r) => r.json())
      .then(setData)
  }

  const getDataForADay = (day) => {
    setDay(day)
    return fetch(`/bo/api/proxy/api/extensions/green-score/efficiency/${props.group}/${props.route}?day=${day}`, {
      credentials: 'include',
      headers: {
        Accept: ' application/json'
      }
    })
      .then((r) => r.json())
      .then(d => {
        setData(d)
      })
  }

  const getData = (day) => {
    const fetchData = day ? getDataForADay : getDataForSevenLastDays;

    setDay(day)
    setLoading(true)
    return fetchData(day)
      .then(() => setLoading(false))
      .catch(() => setLoading(false))
  }

  const maxHits = Math.max(...data.map(item => item.hits));

  const dates = data.map(({ date, hits, avgDuration }) => {
    let health = '';
    if (hits === 0) {
      health = 'nul';
    } else if (hits <= maxHits * 0.05) {
      health = 'low';
    } else if (hits <= maxHits * 0.5) {
      health = 'medium-low';
    } else if (hits <= maxHits * 0.95) {
      health = 'medium-high';
    } else {
      health = 'high';
    }

    const dateAsString = new Date(date).toLocaleString()
    return ({ hits, date, dateAsString, status: { health }, note: +(hits > props.configuration.threshold), avgDuration })
  })

  const globalNote = dates.reduce((acc, curr) => acc + curr.note, 0)

  const step = 2000;
  const hitRanges = Array.from({ length: Math.ceil(maxHits / step) }, (_, i) => ({
    rangeStart: i * step,
    rangeEnd: i * step + (step - 1),
    frequency: 0,
  }));

  data.forEach(item => {
    const hits = item.hits;
    const matchingRange = hitRanges.find(range => hits >= range.rangeStart && hits <= range.rangeEnd);
    if (matchingRange) {
      matchingRange.frequency++;
    }
  });

  if (loading) {
    return <div>loading</div>
  }

  if (!route) {
    return <div>error</div>
  }


  console.debug({ day })
  return (
    <Section
      title={route.name}
    >
      <div style={{ display: 'flex', gap: '.5rem' }}>
        <div
          className='p-3'
          style={{
            flex: 1,
            gap: '.5rem',
            display: 'flex',
            flexDirection: 'column',
            background: 'var(--bg-color_level2)',
            minWidth: '1000px'
          }}
        >
          <div className='d-flex justify-content-between'>
            {!day && <h4>last 7 days efficiency</h4>}
            {!!day && <div>
              <h4>{moment(day).format('ddd D MMM')} efficiency</h4>
              <button className='btn btn-primary' onClick={() => getData(undefined)}>back</button>
            </div>}
            <div>
              <button
                className='btn btn-primary'
                onClick={() => setMode(mode === visualizationMode.heat ? visualizationMode.score : visualizationMode.heat)}>
                <i className='fas fa-bullseye' />
              </button>
              <button
                className='btn btn-primary'
                onClick={() => setMode(mode === visualizationMode.heat ? visualizationMode.graphs : visualizationMode.heat)}>
                <i className='fas fa-chart-line' />
              </button>
            </div>
          </div>

          {(mode === visualizationMode.heat || mode === visualizationMode.score) && (
            <>
              <div className='heatmap-container'>
                {dates.map(({ date, dateAsString, status, hits, avgDuration }, idx) => {
                  const zeDate = new Date(date)
                  const row = !day ? Math.ceil((idx + 1) / 24) : (zeDate.getMinutes() / 10) + 1;
                  const col = !day ? (idx + 1) - 24 * (row - 1) : zeDate.getHours() + 1;
                  const clazz = mode === visualizationMode.heat ? status.health : hits > props.configuration.threshold ? 'high' : 'nul';

                  return (
                    <Popover
                      key={idx}
                      placement="bottom"
                      content={
                        <div className="d-flex flex-column">
                          <div>{dateAsString}</div>
                          <div className={`info`}>{hits > 0 ? `${hits} hits` : 'no hit'}</div>
                          <div className={`info`}>usage time: {humanMillisecond(Math.round(avgDuration * hits))}</div>
                          <div className={`info`}>unusage time: {humanMillisecond(60 * 60 * 1000 - Math.round(avgDuration * hits))}</div>
                        </div>
                      }
                    >
                      <div key={idx}
                        className={`heatpoint ${clazz}`}
                        style={{ gridColumnStart: col + 1, gridColumnEnd: col + 2, gridRowStart: row, gridRowEnd: row + 1 }} />
                    </Popover>
                  )
                })}
                {!day && [1, 2, 3, 4, 5, 6, 7].map((idx => {
                  const date = new Date(dates[24 * (idx - 1)].date)
                  return (
                    <div
                      key={idx}
                      className='d-flex align-items-center justify-content-end me-3'
                      style={{ gridColumnStart: 1, gridColumnEnd: 2, gridRowStart: idx, gridRowEnd: idx + 1, cursor: 'pointer' }}
                      onClick={() => getData(date.valueOf())}>
                      {moment(date).format('DD/MM')}
                    </div>
                  )
                }))}
                {[1, 7, 13, 19, 24].map((idx => {
                  return (
                    <div key={idx}
                      className='heatmap-hour'
                      style={{ gridColumnStart: idx + 1, gridColumnEnd: idx + 4, gridRowStart: 8, gridRowEnd: 9 }}>
                      {new Date(dates[(idx - 1)].date).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</div>
                  )
                }))}
              </div>
              <div className='heatmap-legend d-flex gap-1 justify-content-end align-items-baseline'>
                <div>low</div>
                <div className={`heatpoint nul`} />
                <Popover content={`from 0 to ${~~(maxHits * 0.05)} hits`}><div className={`heatpoint low`} /></Popover>
                <Popover content={`from ${~~(maxHits * 0.05) + 1} to ${~~(maxHits * 0.5)} hits`}><div className={`heatpoint medium-low`} /></Popover>
                <Popover content={`from ${~~(maxHits * 0.5) + 1} to ${~~(maxHits * 0.95)} hits`}><div className={`heatpoint medium-high`} /></Popover>
                <Popover content={`from ${~~(maxHits * 0.95) + 1} to ${maxHits} hits`}><div className={`heatpoint high`} /></Popover>
                <div>High</div>
              </div>
            </>
          )}
          {mode === visualizationMode.graphs && <div style={{ maxHeight: 420, flex: 1, display: 'flex' }}>
            <ResponsiveContainer width="45%" height="100%">
              <LineChart
                margin={{
                  top: 75,
                  bottom: 10,
                  left: 20,
                  right: 20,
                }}
                data={dates}
              >
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="dateAsString" />
                <YAxis />
                <Legend />
                <Line type="monotone" dataKey="hits" stroke="#8884d8" dot={false}/>
              </LineChart>
            </ResponsiveContainer>
            <ResponsiveContainer width="45%" height="100%">
              <LineChart data={hitRanges}>
                <CartesianGrid strokeDasharray="3 3" />
                <XAxis dataKey="rangeStart" />
                <YAxis />
                <Legend />
                <Line type="monotone" dataKey="frequency" stroke="#8884d8" dot={false}/>
              </LineChart>
            </ResponsiveContainer>
          </div>}
        </div>
        <div style={{ gap: '.5rem', display: 'flex', flexDirection: 'column' }}>
          <GlobalScore
            loading={loading}
            letter={getLetter(globalNote)}
            color={getColor(globalNote)}
            title="Score"
          />
        </div>
      </div>
    </Section>
  )
}
