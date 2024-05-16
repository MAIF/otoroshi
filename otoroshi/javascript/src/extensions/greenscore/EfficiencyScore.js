import React, { useState, useMemo, useEffect } from 'react';

import { NgSelectRenderer } from '../../components/nginputs';
import { EfficiencyChart } from './EfficiencyChart';
import Section from './Section';



export const EfficiencyScore = (props) => {
  const { groups, routes } = props;
  const nbElementPerPage = 5;

  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState("");
  const [filteredRoutes, setfilteredRoutes] = useState(routes)
  const [selectedGroup, setSelectedGroup] = useState()

  const getFilteredGRoutes = () => groups
    .find(g => selectedGroup === g.id)
    .routes
    .filter(r => filteredRoutes.some(fr => fr.id === r.routeId))

  useEffect(() => {
    if (filter) {
      const delayDebounceFn = setTimeout(() => {
        setfilteredRoutes(routes.filter(r => r.name.includes(filter)))
      }, 300);
      return () => clearTimeout(delayDebounceFn);
    } else {
      setfilteredRoutes(routes)
    }
  }, [filter]);

  const totalPageSize = useMemo(() => {
    if (!selectedGroup) {
      return 0
    }
    return Math.ceil(getFilteredGRoutes().length / nbElementPerPage)
  }, [selectedGroup]);

  if (!selectedGroup) {
    return (
      <Section>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
          <p style={{ fontSize: '2rem', marginBottom: '1rem' }}>
            Not enough data to display the dashboard
          </p>
          <NgSelectRenderer
            ngOptions={{
              spread: true,
            }}
            onChange={setSelectedGroup}
            options={groups}
            optionsTransformer={(groups) => groups.map((g) => ({ label: g.name, value: g.id }))}
          />
        </div>
      </Section>
    )
  }

  const group = groups
    .find(g => selectedGroup === g.id);

  return (
    <div className='col-12'>
      <div className='d-flex flex-row'>
        <NgSelectRenderer
          value={selectedGroup}
          ngOptions={{
            spread: true,
          }}
          onChange={setSelectedGroup}
          options={groups}
          optionsTransformer={(groups) => groups.map((g) => ({ label: g.name, value: g.id }))}
        />
        <input className='mx-3 form-control flex-grow-1' type="text" onChange={e => setFilter(e.target.value)} />
      </div>
      {getFilteredGRoutes()
        .slice(page * nbElementPerPage, (page + 1) * nbElementPerPage)
        .map((route => {
          return (
            <EfficiencyChart key={route.routeId} route={route.routeId} group={group.id} configuration={group.efficiency} />
          )
        }))}
      <div className="ReactTable">
        <div className="pagination-bottom">
          <div className="-pagination">
            <div className="-previous">
              <button
                type="button"
                disabled={page === 0}
                className="-btn"
                onClick={() => setPage(page - 1)}
              >
                Previous
              </button>
            </div>
            <div className="-center">
              <span className="-pageInfo">
                Page {page + 1} of {totalPageSize}{' '}
              </span>
            </div>
            <div className="-next">
              <button
                type="button"
                className="-btn"
                disabled={page + 1 === totalPageSize}
                onClick={() => setPage(page + 1)}
              >
                Next
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}