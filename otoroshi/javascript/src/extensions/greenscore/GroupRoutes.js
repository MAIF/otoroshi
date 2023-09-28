import React, { useEffect, useState } from 'react';
import { NgBooleanRenderer, NgNumberRenderer, NgSelectRenderer } from '../../components/nginputs';
import { FeedbackButton } from '../../pages/RouteDesigner/FeedbackButton';

export class GroupRoutes extends React.Component {
  state = {
    editRoute: undefined,
    value: this.props.rootValue
  };

  addRoute = (routeId) => {
    this.props.rootOnChange({
      ...this.props.rootValue,
      routes: [
        ...this.props.rootValue.routes,
        {
          routeId,
          rulesConfig: {
            states: []
          }
        },
      ],
    });

    this.editRoute(routeId);
  };

  editRoute = (routeId) =>
    this.setState({
      editRoute: routeId,
    });

  deleteRoute = (routeId) => {
    this.props.rootOnChange({
      ...this.props.rootValue,
      routes: this.props.rootValue.routes.filter((route) => route.routeId !== routeId),
    });
  };

  onWizardClose = () => {
    this.setState({
      editRoute: undefined,
    });
  };

  onRulesChange = (rulesConfig) => {
    this.setState({
      value: {
        ...this.state.value,
        routes: this.state.value.routes.map((route) => {
          if (route.routeId === this.state.editRoute) {
            return {
              ...route,
              rulesConfig,
            };
          }
          return route;
        })
      }
    });
  };

  saveRules = () => {
    this.props.rootOnChange(this.state.value)
  }

  render() {
    if (!(this.props.rootValue && this.props.rootValue.routes))
      return null;

    const { allRoutes = [], rulesBySection = [] } = this.props;

    const { editRoute, value } = this.state;
    const { routes } = value;

    console.log(this.state)

    return <>
      {editRoute && (
        <RulesWizard
          saveRules={this.saveRules}
          onRulesChange={this.onRulesChange}
          onWizardClose={this.onWizardClose}
          route={routes.find((r) => r.routeId === editRoute)}
          rulesBySection={rulesBySection}
        />
      )}

      <RoutesSelector
        allRoutes={allRoutes.filter(
          (route) => !routes.find((r) => route.id === r.routeId)
        )}
        addRoute={this.addRoute}
      />
      <div className='col-sm-10 offset-2 pb-3'
        style={{ background: 'var(--bg-color_level2)', borderRadius: 6 }}>
        <RoutesTable
          routes={routes}
          editRoute={this.editRoute}
          allRoutes={allRoutes}
          deleteRoute={this.deleteRoute}
        />
      </div>
    </>
  }
}

const RoutesTable = ({ routes, editRoute, deleteRoute, allRoutes }) => {
  return (
    <>
      <div className="m-3 mt-4" style={{
        display: 'grid',
        gridTemplateColumns: '1fr 64px',
        padding: '0rem 1rem',
      }}>
        <label style={{ textTransform: 'uppercase', fontWeight: 600, color: 'var(--text)' }}>Route name</label>
        <span style={{ textTransform: 'uppercase', fontWeight: 600, color: 'var(--text)' }}>Action</span>
      </div>
      {routes.length === 0 && (
        <p className="text-center" style={{ fontWeight: 'bold' }}>
          No routes added
        </p>
      )}
      {routes.map(({ routeId }) => {
        return (
          <div key={routeId} className="m-3 mt-0 mb-1" style={{
            display: 'grid',
            gridTemplateColumns: '1fr 64px',
            padding: '0rem 1rem'
          }}>
            <div style={{ flex: 1 }}>
              <label>{allRoutes.find((r) => r.id === routeId)?.name}</label>
            </div>
            <div className='d-flex'>
              <button
                onClick={() => editRoute(routeId)}
                type="button"
                className="btn btn-sm me-1 date-hover"
                style={{
                  border: '1px solid var(--text)'
                }}>
                <i className="fa fa-pencil-alt" style={{ color: 'var(--text)' }} />
              </button>
              <button
                type="button"
                className="btn btn-sm date-hover"
                style={{
                  border: '1px solid var(--text)'
                }}
                onClick={() => {
                  window
                    .newConfirm('Delete this route from the configuration ?', {
                      title: 'Validation required',
                    })
                    .then((ok) => {
                      if (ok) deleteRoute(routeId);
                    });
                }}>
                <i className="fas fa-trash" style={{ color: 'var(--text)' }} />
              </button>
            </div>
          </div>
        );
      })}
    </>
  );
};

const RulesWizard = ({ onWizardClose, route, onRulesChange, rulesBySection, saveRules }) => {
  useEffect(() => {
    const listener = document.addEventListener(
      'keydown',
      (e) => {
        if (e.key === 'Escape') {
          onWizardClose();
        }
      },
      false
    );

    return () => document.removeEventListener('keydown', listener);
  }, []);

  return (
    <div className="wizard">
      <div className="wizard-container">
        <div className="d-flex" style={{ flexDirection: 'column', padding: '2.5rem', flex: 1 }}>
          <div className='d-flex justify-content-between align-items-center'>
            <h3>Rules</h3>
            <div className='d-flex ms-auto'>
              <FeedbackButton
                style={{
                  backgroundColor: 'var(--color-primary)',
                  borderColor: 'var(--color-primary)'
                }}
                className="ms-2"
                onPress={() => {
                  saveRules()
                  return Promise.resolve()
                }}
                onSuccess={onWizardClose}
                text="Save"
              />
            </div>
          </div>
          <GreenScoreForm route={route} onChange={onRulesChange} rulesBySection={rulesBySection} />
          <div className="d-flex mt-auto ms-auto justify-content-between align-items-center">
            <FeedbackButton
              className="me-2"
              onSuccess={onWizardClose}
              icon={() => <i className="fas fa-chevron-left" />}
              text="Back"
            />
            <FeedbackButton
              style={{
                backgroundColor: 'var(--color-primary)',
                borderColor: 'var(--color-primary)'
              }}
              onPress={() => {
                saveRules()
                return Promise.resolve()
              }}
              onSuccess={onWizardClose}
              text="Save"
            />
          </div>
        </div>
      </div>
    </div >
  );
};

const RoutesSelector = ({ allRoutes, addRoute }) => {
  const [route, setRoute] = useState(undefined);

  return (
    <div className="row my-2">
      <label className="col-xs-12 col-sm-2 col-form-label">Link route to the group</label>
      <div className="d-flex align-items-center col-sm-10">
        <div style={{ flex: 1 }}>
          <NgSelectRenderer
            value={route}
            placeholder="Select a route"
            label={' '}
            ngOptions={{
              spread: true,
            }}
            onChange={setRoute}
            margin={0}
            style={{ flex: 1 }}
            options={allRoutes}
            optionsTransformer={(arr) => arr.map((item) => ({ label: item.name, value: item.id }))}
          />
        </div>
        <button
          type="button"
          className="btn btn-primaryColor mx-2"
          disabled={!route}
          onClick={() => {
            addRoute(route);
            setRoute(route);
          }}>
          Link
        </button>
      </div>
    </div>
  );
};

function GreenScoreForm({ route, ...rest }) {
  const { states } = route.rulesConfig;

  const [statesWithSection, setStatesWithSection] = useState([])

  const today = new Date()
  today.setDate(today.getDate() - 0) // TODO - remove this line
  // today.setMonth(today.getMonth() - 4) // TODO - remove this line
  today.setUTCHours(0, 0, 0, 0);

  const getSectionOfRule = ruleId => Object.values(rest.rulesBySection)
    .flatMap(r => r)
    .find(r => r.id === ruleId).section;


  useEffect(() => {
    setStatesWithSection(states.map(date => {
      return {
        ...date,
        states: date.states.map(rule => ({
          ...rule,
          section: getSectionOfRule(rule.id)
        }))
      }
    }))
  }, [states])

  const onRulesChange = (enabled, ruleId, allRulesOfTheSection) => {
    let statesOfCurrentDate = statesWithSection.find(f => f.date === today.getTime());

    if (!statesOfCurrentDate) {
      statesOfCurrentDate = {
        date: today.getTime(),
        states: statesWithSection.length > 0 ? statesWithSection[statesWithSection.length - 1].states : []
      }
    }

    const section = Object.values(rest.rulesBySection)
      .flatMap(r => r)
      .find(r => r.id === ruleId).section;

    statesOfCurrentDate = {
      date: today.getTime(),
      states: statesOfCurrentDate.states.filter(item => {
        if (allRulesOfTheSection) {
          return item.section !== section
        } else {
          return item.id !== ruleId
        }
      })
    }

    if (allRulesOfTheSection) {
      statesOfCurrentDate = {
        date: today.getTime(),
        states: [
          ...statesOfCurrentDate.states,
          ...Object.values(rest.rulesBySection)
            .flatMap(r => r)
            .filter(r => r.section === section)
            .map(r => ({
              id: r.id,
              section,
              enabled
            }))
        ],
      }
    } else {
      statesOfCurrentDate = {
        date: today.getTime(),
        states: [
          ...statesOfCurrentDate.states,
          {
            id: ruleId,
            section,
            enabled
          }
        ]
      }
    }

    rest.onChange({
      ...route.rulesConfig,
      states: [
        ...states.filter(f => f.date !== today.getTime()),
        statesOfCurrentDate
      ]
    });
  };

  return <RulesTables rest={rest} states={statesWithSection} onRulesChange={onRulesChange} />
};

export function ThresholdsTable({ value, onChange }) {

  const onBoundsChange = (thresholds) => onChange(thresholds);

  return <div className="p-3">
    <p style={{ padding: '1rem 1rem 3rem' }}>
      <i className='fas fa-bullhorn me-3' style={{ color: 'var(--text)', fontSize: '1.25rem' }}></i>
      These thresholds are used to evaluate the route, on production, consumption and time spent processing data.
    </p>

    <div style={{
      display: 'grid',
      gridTemplateColumns: 'repeat(4, 1fr)',
      padding: '0rem 1rem .5rem 1.5rem',
    }}>
      <label style={{ textTransform: 'uppercase', fontWeight: 600, color: 'var(--text)' }}>Name</label>
      <span style={{ textTransform: 'uppercase', fontWeight: 600, color: 'var(--text)' }} className='text-center'>Excellent</span>
      <span style={{ textTransform: 'uppercase', fontWeight: 600, color: 'var(--text)' }} className='text-center'>Sufficient</span>
      <span style={{ textTransform: 'uppercase', fontWeight: 600, color: 'var(--text)' }} className='text-center'>Poor</span>
    </div>

    {[
      { key: 'title', title: 'Duration' },
      { key: "overhead", title: "Overhead", unit: "ms" },
      { key: "duration", title: "Duration", unit: "ms" },
      { key: "backendDuration", title: "Backend Duration", unit: "ms" },
      { key: "calls", title: "Calls", unit: "/s" },
      { key: 'title', title: 'Data' },
      { key: "dataIn", title: "Data In", unit: "bytes" },
      { key: "dataOut", title: "Data Out", unit: "bytes" },
      { key: "headersOut", title: "Headers Out", unit: "bytes" },
      { key: "headersIn", title: "Headers In", unit: "bytes" },
    ]
      .map(({ key, title, unit }, i) => key === "title" ?
        <div key={`${key}${i}`}
          style={{ color: 'var(--text)', fontSize: '1rem', fontWeight: 'bold' }} className='mt-3'>
          {title}
        </div> :
        <div key={`${key}${i}`} style={{
          display: 'grid',
          gridTemplateColumns: 'repeat(4, 1fr)',
          alignItems: 'center',
          padding: '0 1rem 0 1.5rem',
          marginTop: '.25rem',
          gap: '.25rem'
        }}>
          <BoundsInput
            title={title}
            unit={unit}
            bounds={value[key]}
            onChange={newValue => {
              onBoundsChange({ ...value, [key]: newValue })
            }}
          />
        </div>)}
  </div>
}

function RulesTables({ rest, states, onRulesChange }) {
  const ruleIsEabled = ruleId => {

    const state = states
      .sort((a, b) => a.date - b.date)
      .reduce((acc, item) => {
        return {
          ...acc,
          ...item.states.reduce((states, state) => {
            const { id, enabled } = state;
            return {
              ...states,
              [id]: enabled
            }
          }, {})
        }
      }, {});

    return state[ruleId]
  }

  const getSectionStatus = rules => rules.reduce((acc, rule) => acc && ruleIsEabled(rule.id), true)

  return <>
    {Object.entries(rest.rulesBySection).map(([id, rules]) => {
      const groupId = id;
      const sectionStatus = getSectionStatus(rules);

      return (
        <div key={groupId} className="p-3">
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 52px' }}>
            <h4 className="mb-3" style={{ textTransform: 'capitalize' }}>
              {groupId}
            </h4>
            <NgBooleanRenderer
              value={sectionStatus}
              onChange={() => {
                onRulesChange(!sectionStatus, rules[0].id, true)
              }}
              schema={{}}
              ngOptions={{ spread: true }}
            />
          </div>
          {(rules || []).map(({ id, description, advice }) => {
            const enabled = ruleIsEabled(id);

            console.log("enabled", enabled)

            return <div key={id}
              className="align-items-center"
              style={{
                display: 'grid',
                gridTemplateColumns: '1fr 52px',
                cursor: 'pointer'
              }}
              onClick={(e) => {
                e.stopPropagation();
                onRulesChange(!enabled, id);
              }}>
              <div>
                <p className="mb-0" style={{ fontWeight: 'bold' }}>{description}</p>
                <p className="">{advice}</p>
              </div>
              <NgBooleanRenderer
                value={enabled}
                onChange={() => { }}
                schema={{}}
                ngOptions={{ spread: true }}
              />
            </div>
          })}
        </div >
      );
    })}
  </>
}

function BoundsInput({ title, bounds, unit, ...props }) {
  const onChange = (key, value) => {
    props.onChange({
      ...bounds,
      [key]: value,
    });
  };

  const { excellent, sufficient, poor } = bounds;

  return <>
    <span style={{ fontWeight: 'bold' }}>{title}</span>

    {[
      { value: excellent, label: 'Excellent', key: 'excellent' },
      {
        value: sufficient,
        label: 'Sufficient',
        key: 'sufficient',
      },
      { value: poor, label: 'Poor', key: 'poor' },
    ].map(({ value, label, key }) => (
      <NgNumberRenderer
        key={key}
        value={value}
        label={label}
        schema={{
          props: {
            unit,
            style: {
              flex: 1,
            },
            placeholder: 'Value to achieve the rank'
          }
        }}
        ngOptions={{
          spread: true,
        }}
        onChange={(e) => onChange(key, e)}
      />
    ))}
  </>
}
