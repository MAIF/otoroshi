import React, { useState, useMemo } from 'react';

import { NgSelectRenderer } from '../../components/nginputs';
import { EfficiencyChart } from './EfficiencyChart';
import Section from './Section';



export const EfficiencyScore = (props) => {
  const { groups, onGroupsChange, filteredGroups } = props
  const nbElementPerPage = 5


  const [page, setPage] = useState(0);

  const totalPageSize = useMemo(() => Math.ceil(groups.length / nbElementPerPage), [groups])

  if (!props.filteredGroups || !props.filteredGroups.length) {
    return (
      <Section>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
          <p style={{ fontSize: '2rem', marginBottom: '1rem' }}>
            Not enough data to display the dashboard
          </p>
          <NgSelectRenderer
            value={filteredGroups}
            ngOptions={{
              spread: true,
            }}
            isMulti
            onChange={onGroupsChange}
            options={groups}
            optionsTransformer={(groups) => groups.map((g) => ({ label: g.name, value: g.id }))}
          />
        </div>
      </Section>
    )
  }

  //todo: paginate to get just 5 first routes
  //todo: add a search input to filter route


  const group = groups
    .find(g => filteredGroups.some(fg => fg.value === g.id))

  return (
    <div>
      <div className='d-flex flex-row'>
        <NgSelectRenderer
          value={[group.value]}
          ngOptions={{
            spread: true,
          }}
          onChange={onGroupsChange}
          isMulti
          options={groups}
          optionsTransformer={(groups) => groups.map((g) => ({ label: g.name, value: g.id }))}
        />
      </div>
      {group.routes.slice(page * nbElementPerPage, (page + 1) * nbElementPerPage)
        .map((route => {
          return (
            <EfficiencyChart key={route.routeId} route={route.routeId} group={group.id} configuration={group.efficiency} />
          )
        }))}
      <div className="ReactTable">
        <div class="pagination-bottom">
          <div class="-pagination">
            <div class="-previous">
              <button
                type="button"
                disabled={page === 0}
                class="-btn"
                onClick={() => setPage(page - 1)}
              >
                Previous
              </button>
            </div>
            <div class="-center">
              <span class="-pageInfo">
                Page {page + 1} of {totalPageSize}{' '}
              </span>
            </div>
            <div class="-next">
              <button
                type="button"
                class="-btn"
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