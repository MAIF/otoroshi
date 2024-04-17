import React, { useState, useEffect } from 'react';
import { GlobalScore } from './GlobalScore';

export const EfficienceChart = (props) => {
  const [loading, setLoading] = useState(true);
  const [data, setData] = useState([]);
  const [route, setRoute] = useState();

  useEffect(() => {
    setLoading(true)
    Promise.all([
      fetch(`/bo/api/proxy/api/extensions/green-score/efficience/${props.route}`, {
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


  const treshold1 = 200;
  const treshold2 = 500;
  const treshold3 = 750;

  const getStatus = (value) => {
    if (value <= treshold1) {
      return 'low';
    } else if (value <= treshold2) {
      return 'medium-low';
    } else if (value <= treshold3) {
      return 'medium-high';
    } else {
      return 'high';
    }
  }

  const dates = data.map(({ date, hits, avgDuration }) => {
    const dateAsString = new Date(date).toLocaleString('en-EN')
    return ({ hits, date, dateAsString, status: { health: getStatus(hits) } })
  })

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
      <div className='d-flex'>
        <div className='heatmap-container'>
          {dates.map(({ dateAsString, status, hits }, idx) => (<div key={idx} className={`heatpoint ${status.health}`} title={`${dateAsString}: ${hits}`} />))}
        </div>
        <GlobalScore
          loading={loading}
          letter={"E"}
          color={'tomato'}
        />
      </div>
    </div>
  )
}
