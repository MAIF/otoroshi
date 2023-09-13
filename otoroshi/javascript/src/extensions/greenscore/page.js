import React, { useEffect, useState } from 'react';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { nextClient } from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs/Table';
import { v4 as uuid } from 'uuid';
import GreenScoreRoutesForm from './routesForm';
import RulesRadarchart from './RulesRadarchart';
import { GlobalScore } from './GlobalScore';
import { NgSelectRenderer } from '../../components/nginputs';
import { GREEN_SCORE_GRADES, MAX_GREEN_SCORE_NOTE, getColor, getLetter } from './util';

function DatePicker({ date, onChange, options }) {
  return <div className='mb-3'>
    <NgSelectRenderer
      value={date}
      placeholder="Select a date"
      label={' '}
      ngOptions={{
        spread: true,
      }}
      onChange={onChange}
      options={options}
      optionsTransformer={(arr) => arr.map((item) => ({ label: new Date(item).toDateString(), value: item }))}
    />
  </div>
}

export default class GreenScoreConfigsPage extends React.Component {
  state = {
    routes: [],
    rulesBySection: undefined,
    scores: [],
    date: undefined,
  };

  formSchema = {
    _loc: {
      type: 'location',
    },
    id: {
      type: 'string',
      disabled: true,
      label: 'Id',
      props: {
        placeholder: '---',
      },
    },
    name: {
      type: 'string',
      label: 'Group name',
      props: {
        placeholder: 'My Awesome Green Score group',
      },
    },
    description: {
      type: 'string',
      label: 'Description',
      props: {
        placeholder: 'Description of the Green Score config',
      },
    },
    metadata: {
      type: 'object',
      label: 'Metadata',
    },
    tags: {
      type: 'array',
      label: 'Tags',
    },
    routes: {
      renderer: (props) => {
        return <GreenScoreRoutesForm
          {...props}
          routeEntities={this.state.routes}
          rulesBySection={this.state.rulesBySection}
        />
      }
    },
  };

  columns = [
    {
      title: 'Name',
      filterId: 'name',
      notFilterable: true,
      content: (item) => item.name,
    },
    {
      title: 'Description',
      filterId: 'description',
      notFilterable: true,
      content: (item) => item.description,
    },
    // {
    //   title: 'Green score group',
    //   notFilterable: true,
    //   content: GreenScoreColumm,
    // },
    // {
    //   title: 'Thresholds score',
    //   notFilterable: true,
    //   Cell: ThresholdsScoreColumn,
    // },
  ];

  formFlow = [
    '_loc',
    {
      type: 'group',
      name: 'Informations',
      collapsed: false,
      fields: ['id', 'name', 'description'],
    },
    {
      type: 'group',
      name: 'Routes',
      collapsed: false,
      fields: ['routes'],
    },
    {
      type: 'group',
      name: 'Misc.',
      collapsed: true,
      fields: ['tags', 'metadata'],
    },
  ];

  client = BackOfficeServices.apisClient(
    'green-score.extensions.otoroshi.io',
    'v1',
    'green-scores'
  );

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
      .then(([routes, rulesTemplate, { scores, global }]) => {
        this.setState({
          routes,
          rulesBySection: this.rulesTemplateToRulesBySection(rulesTemplate),
          scores,
          global,
          date: [...new Set(global.sections_score_by_date.map(section => section.date))][0]
        })
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

  // openScore = group => {
  //   this.setState({
  //     groups: this.state.groups.map(g => {
  //       if (g.id === group.id) {
  //         return {
  //           ...g,
  //           opened: !g.opened
  //         }
  //       }
  //       return g;
  //     })
  //   })
  // }

  getAllNormalizedScore = (values, dynamic_score) => {
    const scores = [
      values.find(v => v.section === "architecture")?.score.normalized_score || 0,
      values.find(v => v.section === "design")?.score.normalized_score || 0,
      values.find(v => v.section === "usage")?.score.normalized_score || 0,
      values.find(v => v.section === "log")?.score.normalized_score || 0,
      // dynamic_score.plugins_instance,
      // dynamic_score.produced_data,
      // dynamic_score.produced_headers
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

  render() {
    if (this.state.scores.length > 0)
      console.log(this.state)

    const { scores, global } = this.state;

    const sectionsAtCurrentDate = scores.length > 0 ? global.sections_score_by_date.filter(section => section.date === this.state.date) : [];
    const valuesAtCurrentDate = scores.length > 0 ? sectionsAtCurrentDate : [];
    const normalizedGlobalScore = scores.length > 0 ? this.getAllNormalizedScore(valuesAtCurrentDate, global.dynamic_score) : 0;
    const normalizedDynamicScore = scores.length > 0 ? this.getDynamicScore(global.dynamic_score) : 0;

    return <div className="clearfix container-xl">
      {scores.length > 0 && <>
        <DatePicker
          onChange={date => this.setState({ date })}
          date={this.state.date}
          options={[...new Set(global.sections_score_by_date.map(section => section.date))]} />

        <div style={{ display: 'flex', justifyContent: 'center', gap: '.5rem', minHeight: 480 }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '.5rem' }}>
            <GlobalScore
              letter={String.fromCharCode(65 + (1 - normalizedGlobalScore) * 5)}
              color={Object.keys(GREEN_SCORE_GRADES)[Math.round((1 - normalizedGlobalScore) * 5)]} />
            <GlobalScore
              score={sectionsAtCurrentDate.reduce((acc, section) => acc + section.score.score, 0)}
              maxScore={MAX_GREEN_SCORE_NOTE * sectionsAtCurrentDate.length}
              raw />
          </div>
          <RulesRadarchart
            values={valuesAtCurrentDate}
            dynamic_score={global.dynamic_score} />
          <div style={{ display: 'flex', flexDirection: 'column', gap: '.5rem' }}>
            <GlobalScore
              letter={String.fromCharCode(65 + (1 - normalizedDynamicScore) * 5)}
              color={Object.keys(GREEN_SCORE_GRADES)[Math.round((1 - normalizedDynamicScore) * 5)]}
              dynamic
              title="Produced data"
              tag="dynamic" />
            <GlobalScore score={normalizedDynamicScore * 100} raw dynamic title="Net PU" tag="dynamic" />
          </div>
        </div>
      </>}

      <div className='mt-4'>
        <Table
          v2
          parentProps={this.props}
          selfUrl="extensions/green-score/green-score-configs"
          defaultTitle="All Green Score configs."
          defaultValue={() => ({
            id: 'green-score-config_' + uuid(),
            name: 'My Green Score',
            description: 'An awesome Green Score',
            tags: [],
            metadata: {},
            routes: [],
            config: {},
          })}
          itemName="Green Score config"
          formSchema={this.formSchema}
          formFlow={this.formFlow}
          columns={this.columns}
          stayAfterSave={true}
          fetchItems={this.client.findAll}
          updateItem={this.client.update}
          deleteItem={this.client.delete}
          createItem={this.client.create}
          navigateTo={(item) => {
            window.location = `/bo/dashboard/extensions/green-score/green-score-configs/edit/${item.id}`;
          }}
          itemUrl={(item) =>
            `/bo/dashboard/extensions/green-score/green-score-configs/edit/${item.id}`
          }
          showActions={true}
          showLink={false}
          rowNavigation={true}
          extractKey={(item) => item.id}
          export={true}
          kubernetesKind={'GreenScore'}
        />
        {/* {groups.map((group, i) => {
          return <div key={group.id}
            style={{
              marginBottom: '.2rem',
              background: 'var(--bg-color_level2)',
              padding: '.5rem 1rem',
              borderRadius: '.25rem'
            }} onClick={() => this.openScore(group)}>

            <div style={{
              display: 'flex',
              justifyContent: 'space-between',
              alignItems: 'center'
            }}>
              <span style={{ fontWeight: 'bold', minWidth: '30%' }}>{group.name}</span>

              <GreenScoreColumm {...group} />
              <ThresholdsScoreColumn value={group} index={i} />

              <div style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                background: 'var(--bg-color_level1)',
                borderRadius: '10%',
                width: 32,
                height: 32,
                cursor: 'pointer'
              }} onClick={() => this.openScore(group)}>
                <i className={`fas fa-chevron-${group.opened ? 'up' : 'down'}`} />
              </div>
            </div>
            {group.opened && <div className='mt-3'>
              {group.routes.map(route => {
                return <div key={route.routeId} className='mt-1' style={{ display: 'flex', justifyContent: 'space-between' }}>
                  <span style={{ minWidth: '30%' }}>{routes.find(r => r.id === route.routeId).name}</span>
                  <GreenScoreColumm route={route} />
                  <ThresholdsScoreColumn route={route} value={{
                    id: group.id
                  }} />
                  <div style={{ width: 32 }}></div>
                </div>
              })}
            </div>}
          </div>
        })} */}
      </div>
    </div >
  }
}

const GreenScoreColumm = (props) => {
  // const score =
  //   (props.routes || [props.route]).reduce((acc, route) => calculateGreenScore(route.rulesConfig).score + acc, 0) /
  //   (props.routes || [props.route]).length;

  // const { letter, rank } = getRankAndLetterFromScore(score);

  return (
    <div className="text-center" style={{
      background: 'var(--bg-color_level1)',
      borderRadius: '25%',
      padding: '.25rem .5rem'
    }}>
      {/* {letter} <i className="fa fa-leaf" style={{ color: rank }} /> */}
    </div>
  );
};

const ThresholdsScoreColumn = (props) => {
  const [score, setScore] = useState({ letter: '-', rank: 0 });

  useEffect(() => {
    calculateThresholdsScore(props.value.id, [props.value.routes ? props.value.routes[props.index] : props.route])
      .then(setScore);
  }, []);

  const { letter, rank } = score;

  return (
    <div className="text-center" style={{
      textTransform: 'capitalize',
      background: 'var(--bg-color_level1)',
      borderRadius: '25%',
      padding: '.25rem .5rem'
    }}>
      {letter} <i className="fa fa-leaf" style={{ color: rank }} />
    </div>
  );
}