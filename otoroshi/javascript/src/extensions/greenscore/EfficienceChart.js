import React, { useState, useEffect } from 'react';
import { AreaChart, Area, LineChart, Line, XAxis, YAxis, Legend, CartesianGrid, Tooltip, ResponsiveContainer } from 'recharts';

import { GlobalScore } from './GlobalScore';

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

export const EfficienceChart = (props) => {
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState([]);
  const [route, setRoute] = useState();

  const [mode, setMode] = useState(visualizationMode.heat)

  useEffect(() => {
    setLoading(true)
    Promise.all([
      fetch(`/bo/api/proxy/api/extensions/green-score/efficiency/${props.group}/${props.route}`, {
        credentials: 'include',
        headers: {
          Accept: ' application/json'
        }
      }).then((r) => r.json()),
      fetch(`/bo/api/proxy//api/routes/${props.route}`, {
        credentials: 'include',
        headers: {
          Accept: ' application/json'
        }
      }).then((r) => r.json()),
    ])
      .then(([d, r]) => {
        setData(d)
        setRoute(r)
        setLoading(false)
      })
      .catch(e => {
        setLoading(false)
      })
  }, []);

  const maxHits = Math.max(...data.map(item => item.hits));

  const dates = data.map(({ date, hits, avgDuration }) => {
    let health = '';
    if (hits === 0) {
      health = 'nul';
    } else if (hits <= maxHits * 0.1) {
      health = 'low';
    } else if (hits <= maxHits * 0.5) {
      health = 'medium-low';
    } else if (hits <= maxHits * 0.9) {
      health = 'medium-high';
    } else {
      health = 'high';
    }

    const dateAsString = new Date(date).toLocaleString('en-EN')
    return ({ hits, date, dateAsString, status: { health }, note: +(hits > props.configuration.threshold) })
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



  return (
    <div
      style={{
        flex: 1,
        // minHeight: 520,
        maxHeight: 320,
        background: 'var(--bg-color_level2)',
        borderRadius: '.2rem',
        padding: '1rem 2rem 62px',
      }}
    >
      <div>{route.name}</div>
      <button className='btn btn-primary' onClick={() => setMode(mode === visualizationMode.heat ? visualizationMode.graphs : visualizationMode.heat)}><i className='fas fa-redo' /></button>
      <div className='d-flex flex-column'>
        {mode === visualizationMode.heat && (
          <div className='heatmap-container'>
            {dates.map(({ dateAsString, status, hits }, idx) => (<div key={idx} className={`heatpoint ${status.health}`} title={`${dateAsString}: ${hits}`} />))}
          </div>
        )}
        {mode === visualizationMode.graphs && (<div>
          <ResponsiveContainer width={820} height={200}>
            <LineChart
              width={500}
              height={300}
              data={dates}
              margin={{
                top: 5,
                right: 30,
                left: 20,
                bottom: 5,
              }}
            >
              <CartesianGrid strokeDasharray="3 3" />
              <XAxis dataKey="dateAsString" />
              <YAxis />
              <Legend />
              <Line type="monotone" dataKey="hits" stroke="#8884d8" />
            </LineChart>
          </ResponsiveContainer>

          <LineChart width={800} height={400} data={hitRanges}>
            <CartesianGrid strokeDasharray="3 3" />
            <XAxis dataKey="rangeStart" />
            <YAxis />
            <Legend />
            <Line type="monotone" dataKey="frequency" stroke="#8884d8" />
          </LineChart>
        </div>)}
        <GlobalScore
          loading={loading}
          letter={getLetter(globalNote)}
          color={getColor(globalNote)}
        />
      </div>
    </div>
  )
}
