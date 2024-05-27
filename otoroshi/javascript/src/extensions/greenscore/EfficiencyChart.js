import React, { useEffect, useState, useMemo } from 'react';
import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, XAxis, YAxis, Label } from 'recharts';
import { Popover } from 'antd';
import moment from 'moment';

import { nextClient } from '../../services/BackOfficeServices';
import Loader from '../../components/Loader';
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
  graphs: 'GRAPHS'
}

export const EfficiencyChart = (props) => {
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState([]);
  const [dayData, setDayData] = useState([]);
  const [route, setRoute] = useState();
  const [maxHits, setMaxHits] = useState(1);

  const [mode, setMode] = useState(visualizationMode.heat);
  const [day, setDay] = useState();

  // const maxHits = useMemo(() => Math.max(...data.map(item => item.hits), 1), [data])


  const betterData = (d, _maxHits = maxHits) => {
    // const maxHits = Math.max(...d.map(item => item.hits), 1);
    return d.map(({ date, hits, avgDuration }) => {
      let health = '';
      if (hits === 0) {
        health = 'nul';
      } else if (hits <= _maxHits * 0.05) {
        health = 'low';
      } else if (hits <= _maxHits * 0.5) {
        health = 'medium-low';
      } else if (hits <= _maxHits * 0.95) {
        health = 'medium-high';
      } else {
        health = 'high';
      }

      const dateAsString = moment(date).format('DD/MM')
      return ({ hits, date, dateAsString, status: { health }, avgDuration })
    })
  }

  useEffect(() => {
    setLoading(true)
    nextClient.forEntity(nextClient.ENTITIES.ROUTES).findById(props.route)
      .then((r) => {
        setRoute(r)
      })
      .then(() => fetch(`/bo/api/proxy/api/extensions/green-score/efficiency/${props.group}/${props.route}`, {
        credentials: 'include',
        headers: {
          Accept: ' application/json'
        }
      }))
      .then((r) => r.json())
      .then(d => {
        const _maxHits = Math.max(...d.map(item => item.hits), 1)
        setMaxHits(_maxHits)
        setData(betterData(d, _maxHits))
        setLoading(false)
      })
      .catch(e => {
        setLoading(false)
      })
  }, []);

  const getDataForADay = (day) => {
    setDay(day)
    setLoading(true)
    fetch(`/bo/api/proxy/api/extensions/green-score/efficiency/${props.group}/${props.route}?day=${day}`, {
      credentials: 'include',
      headers: {
        Accept: ' application/json'
      }
    })
      .then((r) => r.json())
      .then(d => {
        setDayData(betterData(d))
        setLoading(false)
      })
      .catch(() => setLoading(false))
  }

  // const maxHits = Math.max(...data.map(item => item.hits), 1);
  const zeData = !!day ? dayData : data;

  const roundedValue = (value, floor = 10000) => {
    if (value === 0) {
      return 1
    } else if (value < floor) {
      return roundedValue(value, floor / 10)
    } else {
      return Math.floor(value / floor) * floor;
    }
  }

  const step = roundedValue(maxHits / 10);
  const hitRanges = Array.from({ length: Math.ceil(maxHits / step) }, (_, i) => ({
    rangeStart: i * step,
    rangeEnd: i * step + (step - 1),
    frequency: 0,
  }));

  zeData.forEach(item => {
    const hits = item.hits;
    const matchingRange = hitRanges.find(range => hits >= range.rangeStart && hits <= range.rangeEnd);
    if (matchingRange) {
      matchingRange.frequency++;
    }
  });

  if (loading) {
    return (
      <Section title={route?.name}>
        <div
          className='p-3 efficiency-loading'
          style={{
            background: 'var(--bg-color_level2)',
            minWidth: '1000px',
            minHeight: '316px'
          }} >
          <Loader loading={loading} />
        </div>
      </Section >
    )
  }

if (!route) {
  return <div>error</div>
}


return (
  <Section title={route.name}>
    <div style={{ display: 'flex', gap: '.5rem' }}>
      <div
        className='p-3 heatmap-section'
        style={{
          flex: 1,
          gap: '.5rem',
          display: 'flex',
          flexDirection: 'column',
          minWidth: '1000px'
        }}
      >
        <div className='d-flex justify-content-between'>
          {!day && <h4>last 7 days efficiency</h4>}
          {!!day && (
            <div>
              <h4>{moment(day).format('ddd D MMM')} efficiency</h4>
              <button className='btn btn-primary' onClick={() => setDay(undefined)}>back</button>
            </div>
          )}
          <div>
            <button
              className='btn btn-primary'
              onClick={() => setMode(mode === visualizationMode.heat ? visualizationMode.graphs : visualizationMode.heat)}>
              <i className='fas fa-bar-chart' />
            </button>
          </div>
        </div>

        {(mode === visualizationMode.heat) && (
          <>
            {!!day && (
              <div className='d-flex flex-row justify-content-around'>
                <div className='heatmap-container--mini'>
                  {data
                    .filter(({ date }) => moment(day).isSame(moment(date), 'day'))
                    .map(({ date, status, hits, avgDuration }, idx) => {
                      const col = (idx + 1);

                      return (
                        <Popover
                          key={idx}
                          placement="bottom"
                          content={
                            <div className="d-flex flex-column">
                              <div>{moment(date).format("DD/MM HH:mm")}</div>
                              <div className={`info`}>{hits > 0 ? `${hits} hits` : 'no hit'}</div>
                              <div className={`info`}>usage time: {humanMillisecond(Math.round(avgDuration * hits))}</div>
                              <div className={`info`}>unusage time: {humanMillisecond(60 * 60 * 1000 - Math.round(avgDuration * hits))}</div>
                            </div>
                          }
                        >
                          <div key={idx}
                            className={`heatpoint ${status.health}`}
                            style={{ gridColumnStart: col + 1, gridColumnEnd: col + 2, gridRowStart: 1, gridRowEnd: 1 }} />
                        </Popover>
                      )
                    })}
                </div>
                <div style={{ width: '35px' }} />
              </div>
            )}
            <div className='d-flex flex-row justify-content-around'>
              <div className='heatmap-container'>
                {zeData.map(({ date, status, hits, avgDuration }, idx) => {
                  const zeDate = new Date(date)
                  const row = !day ? Math.ceil((idx + 1) / 24) : (zeDate.getMinutes() / 10) + 1;
                  const col = !day ? (idx + 1) - 24 * (row - 1) : zeDate.getHours() + 1;
                  const clazz = status.health;


                  return (
                    <Popover
                      key={idx}
                      placement="bottom"
                      content={
                        <div className="d-flex flex-column">
                          <div>{moment(date).format("DD/MM HH:mm")}</div>
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
                  const date = new Date(zeData[24 * (idx - 1)]?.date)
                  return (
                    <div
                      key={idx}
                      className='d-flex align-items-center justify-content-end me-3 heatmap-date'
                      style={{ gridColumnStart: 1, gridColumnEnd: 2, gridRowStart: idx, gridRowEnd: idx + 1, cursor: 'pointer' }}
                      onClick={() => getDataForADay(date.valueOf())}>
                      {moment(date).format('ddd DD/MM')}
                    </div>
                  )
                }))}
                {!day && [1, 7, 13, 19, 24].map((idx => {
                  return (
                    <div key={idx}
                      className='heatmap-hour'
                      style={{ gridColumnStart: idx + 1, gridColumnEnd: idx + 4, gridRowStart: 8, gridRowEnd: 9 }}>
                      {new Date(zeData[(idx - 1)]?.date).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</div>
                  )
                }))}
                {!!day && [0, 6, 12, 18, 23].map((idx => {
                  return (
                    <div key={idx}
                      className='heatmap-hour'
                      style={{ gridColumnStart: idx + 2, gridColumnEnd: idx + 5, gridRowStart: 8, gridRowEnd: 9 }}>
                      {new Date(zeData[(idx * 6)]?.date).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}</div>
                  )
                }))}
              </div>
              <div className='heatmap-legend d-flex flex-column-reverse gap-1 justify-content-end align-items-baseline'>
                <div>low</div>
                <Popover content={`no hit`}><div className={`heatpoint nul`} /></Popover>
                <Popover content={`from 1 to ${~~(maxHits * 0.05)} hits`}><div className={`heatpoint low`} /></Popover>
                <Popover content={`from ${~~(maxHits * 0.05) + 1} to ${~~(maxHits * 0.5)} hits`}><div className={`heatpoint medium-low`} /></Popover>
                <Popover content={`from ${~~(maxHits * 0.5) + 1} to ${~~(maxHits * 0.95)} hits`}><div className={`heatpoint medium-high`} /></Popover>
                <Popover content={`from ${~~(maxHits * 0.95) + 1} to ${maxHits} hits`}><div className={`heatpoint high`} /></Popover>
                <div>High</div>
              </div>
            </div>
          </>
        )}

        {mode === visualizationMode.graphs && <div style={{ maxHeight: 420, flex: 1, display: 'flex' }}>
          <ResponsiveContainer width="45%" height={300}>
            <LineChart
              margin={{
                bottom: 10,
                left: 20,
                right: 20,
              }}
              data={zeData}
            >
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="dateAsString" interval="preserveStartEnd" label={{ value: 'Date', position: 'insideBottomRight', offset: -20, fill: "var(--text)" }} />
              <YAxis label={{ value: 'Hits', angle: -90, position: 'insideLeft', fill: "var(--text)" }} />
              <Legend />
              <Line type="monotone" dataKey="hits" stroke="var(--color-primary)" dot={false} />
            </LineChart>
          </ResponsiveContainer>
          <ResponsiveContainer width="45%" height={300}>
            <LineChart margin={{
              bottom: 10,
              left: 20,
              right: 20,
            }} data={hitRanges}>
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="rangeStart" interval="preserveStartEnd" label={{ value: 'Hit range', position: 'insideBottomRight', offset: -20, fill: "var(--text)" }} />
              <YAxis label={{ value: 'Frequency', angle: -90, position: 'insideLeft', fill: "var(--text)" }} />
              <Legend />
              <Line type="monotone" dataKey="frequency" stroke="var(--color-primary)" dot={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>}
      </div>
    </div>
  </Section>
)
}
