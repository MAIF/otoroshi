import React, { useEffect, useState } from 'react';
import * as BackOfficeServices from '../../services/BackOfficeServices';
import { nextClient } from '../../services/BackOfficeServices';
import { Table } from '../../components/inputs/Table';
import { v4 as uuid } from 'uuid';
import GreenScoreRoutesForm from './routesForm';
import RulesRadarchart from './RulesRadarchart';
import { GlobalScore } from './GlobalScore';
import moment from 'moment';
import { GREEN_SCORE_GRADES, MAX_GREEN_SCORE_NOTE } from './util';

function DatePickerSelector({ icon, onClick }) {
  return <div style={{
    border: '2px solid var(--bg-color_level3)',
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
    top: 0,
    bottom: 0,
    left: 0,
    right: 0,
    zIndex: 10,
  }} className='d-flex flex-column'>
    <div className='date-hover'
      onClick={() => onDateSelectorChange(true)}
      style={{
        border: '1px solid var(--color-primary)',
        padding: '.5rem 1rem',
        width: 175,
        textAlign: 'center',
        cursor: 'pointer',
        borderTopLeftRadius: 8,
        borderTopRightRadius: 8,
        borderBottomLeftRadius: opened ? '0px' : '8px',
        borderBottomRightRadius: opened ? 0 : 8,
        borderBottom: opened ? 'none' : '1px solid var(--color-primary)',
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
        border: '1px solid var(--color-primary)',
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
            }}>{format(months[currentMonthAndYear].value)}</span>
          {months.length > 1 && <DatePickerSelector icon='fas fa-chevron-right' onClick={next} />}
          {months.length > 1 && <DatePickerSelector icon='fas fa-angles-right' onClick={goToEnd} />}
        </div>

        <div className='d-flex flex-wrap mt-3' style={{ gap: 12 }}>
          {dates
            .filter(date => date.month === months[currentMonthAndYear].month && date.year === months[currentMonthAndYear].year)
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
    rulesBySection: undefined,
    scores: [],
    date: undefined,
    dateSelectorOpened: true
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
      values.find(v => v.section === "log")?.score.normalized_score || 0
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
    if (this.state.scores.length > 0)
      console.log(this.state)

    const { scores, global, dateSelectorOpened } = this.state;

    const sectionsAtCurrentDate = scores.length > 0 ? global.sections_score_by_date.filter(section => section.date === this.state.date) : [];
    const valuesAtCurrentDate = scores.length > 0 ? sectionsAtCurrentDate : [];
    const normalizedGlobalScore = scores.length > 0 ? this.getAllNormalizedScore(valuesAtCurrentDate, global.dynamic_score) : 0;
    const normalizedDynamicScore = scores.length > 0 ? this.getDynamicScore(global.dynamic_score) : 0;

    return <div>
      {scores.length > 0 && <>
        <div style={{ display: 'flex', justifyContent: 'center', gap: '.5rem', minHeight: 480, position: 'relative', paddingTop: 50 }}>
          <DatePicker
            opened={dateSelectorOpened}
            onDateSelectorChange={dateSelectorOpened => this.setState({ dateSelectorOpened })}
            date={this.state.date}
            onChange={date => this.setState({ date, dateSelectorOpened: false })}
            onClose={() => this.setState({ dateSelectorOpened: false })}
            options={[
              ...new Set(global.sections_score_by_date.map(section => section.date)),
              ...Array(20).fill(0).map(() => this.randomDate("01/01/2018", "01/09/2023"))
            ].sort()} />
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

  // const {letter, rank} = getRankAndLetterFromScore(score);

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