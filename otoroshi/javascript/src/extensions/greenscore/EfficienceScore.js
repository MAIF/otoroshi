import React, { useState, useEffect } from 'react';
import Wrapper from './Wrapper';
import Section from './Section';
import { GlobalScore } from './GlobalScore';
import { NgSelectRenderer } from '../../components/nginputs';
import { EfficienceChart } from './EfficienceChart';


export const EfficienceScore = (props) => {
  const { groups, onGroupsChange, filteredGroups } = props

  const [efficience, setEfficience] = useState()
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    setEfficience()
  }, [filteredGroups])


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

  return (
    <Section
      full={true}
      title="Score by entity"
      subTitle="lorem ipsum"
    >
      {groups.filter(g => filteredGroups.some(fg => fg.value === g.id)).map((fg, idx) => {
        return (
          <div>
            <div>{fg.name}</div>
            {fg.routes.map((route => {
              return (
                <EfficienceChart key={route.routeId} route={route.routeId} />
              )
            }))}
          </div>
        )
      })}
    </Section>
  )
}