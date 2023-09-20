import React, { useEffect, useState } from 'react';
import { NgBooleanRenderer, NgNumberRenderer, NgSelectRenderer } from '../../components/nginputs';
import { FeedbackButton } from '../../pages/RouteDesigner/FeedbackButton';
import { firstLetterUppercase } from '../../util';

const DEFAULT_VALUES = {
  excellent: 1,
  sufficient: 10,
  poor: 15
};

export default class GroupRoutes extends React.Component {
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
            states: [],
            thresholds: {
              calls: DEFAULT_VALUES,
              dataIn: DEFAULT_VALUES,
              dataOut: DEFAULT_VALUES,
              overhead: DEFAULT_VALUES,
              duration: DEFAULT_VALUES,
              backendDuration: DEFAULT_VALUES,
              headersIn: DEFAULT_VALUES,
              headersOut: DEFAULT_VALUES
            }
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

    console.log(value)

    return (
      <div>
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

        <RoutesTable
          routes={routes}
          editRoute={this.editRoute}
          allRoutes={allRoutes}
          deleteRoute={this.deleteRoute}
        />
      </div>
    );
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
          <div key={routeId} className="m-3 mt-0" style={{
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
          <label style={{ fontSize: '1.15rem' }}>
            <i
              className="fas fa-times me-3"
              onClick={onWizardClose}
              style={{ cursor: 'pointer' }}
            />
            <span>Check the rules of the route</span>
          </label>
          <GreenScoreForm route={route} onChange={onRulesChange} rulesBySection={rulesBySection} />
          <div className="d-flex mt-auto ms-auto justify-content-between align-items-center">
            <FeedbackButton
              style={{
                backgroundColor: 'var(--color-primary)',
                borderColor: 'var(--color-primary)',
                padding: '12px 48px',
              }}
              onPress={() => {
                saveRules()
                return Promise.resolve()
              }}
              onSuccess={onWizardClose}
              icon={() => <i className="fas fa-paper-plane" />}
              text="Save the rules"
            />
          </div>
        </div>
      </div>
    </div>
  );
};

const RoutesSelector = ({ allRoutes, addRoute }) => {
  const [route, setRoute] = useState(undefined);

  return (
    <div className="row my-2">
      <label className="col-xs-12 col-sm-2 col-form-label">Add to this group</label>
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
          Start to configure
        </button>
      </div>
    </div>
  );
};

const GreenScoreForm = ({ route, ...rest }) => {
  const { states, thresholds } = route.rulesConfig;

  const today = new Date()
  // today.setDate(today.getDate() + 1) // TODO - remove this line
  // today.setMonth(today.getMonth() - 4) // TODO - remove this line
  today.setUTCHours(0, 0, 0, 0);

  const onRulesChange = (enabled, ruleId) => {

    let statesOfCurrentDate = states.find(f => f.date === today.getTime());

    // console.log('states of current date', statesOfCurrentDate)

    if (!statesOfCurrentDate)
      statesOfCurrentDate = {
        states: [],
        date: today.getTime()
      }

    statesOfCurrentDate = {
      states: [
        ...statesOfCurrentDate.states.filter(item => item.id !== ruleId),
        {
          id: ruleId,
          enabled
        }
      ],
      date: today.getTime()
    }

    rest.onChange({
      ...route.rulesConfig,
      states: [
        ...states.filter(f => f.date !== today.getTime()),
        statesOfCurrentDate
      ]
    });
  };

  const onBoundsChange = (thresholds) => {
    rest.onChange({
      ...route.rulesConfig,
      thresholds,
    });
  };


  console.log(thresholds)

  return (
    <div>
      <div className="p-3">
        <h4>Thresholds</h4>
        <p>
          These threshold are used to assess the route. These checks are applied on the received and
          sent requests.
        </p>

        {[
          { key: "overhead", title: "Overhead", unit: "ms" },
          { key: "duration", title: "Duration", unit: "ms" },
          { key: "backendDuration", title: "Backend Duration", unit: "ms" },
          { key: "calls", title: "Calls", unit: "" },
          { key: "dataIn", title: "Data In", unit: "bytes" },
          { key: "dataOut", title: "Data Out", unit: "bytes" },
          { key: "headersOut", title: "Headers Out", unit: "bytes" },
          { key: "headersIn", title: "Headers In", unit: "bytes" },
        ]
          .map(({ key, title, unit }) => <BoundsInput
            key={key}
            title={title}
            unit={unit}
            bounds={thresholds[key]}
            onChange={value => onBoundsChange({ ...thresholds, [key]: value })}
          />)}

      </div>
      {Object.entries(rest.rulesBySection).map(([id, rules]) => {
        const groupId = id;
        return (
          <div key={groupId} className="p-3">
            <h4 className="mb-3" style={{ textTransform: 'capitalize' }}>
              {groupId}
            </h4>
            {(rules || []).map(({ id, description, advice }) => {
              const ruleId = id;

              let statesAtDate = states.find(s => s.date === today.getTime());

              if (!statesAtDate && states.length > 0) {
                statesAtDate = states[states.length - 1]
              }

              const enabled = (statesAtDate ? statesAtDate.states.find(f => f.id === ruleId)?.enabled : false);

              return (
                <div
                  key={ruleId}
                  className="d-flex align-items-center"
                  style={{
                    cursor: 'pointer',
                  }}
                  onClick={(e) => {
                    e.stopPropagation();
                    onRulesChange(!enabled, ruleId);
                  }}>
                  <div style={{ flex: 1 }}>
                    <p className="offset-1 mb-0" style={{ fontWeight: 'bold' }}>
                      {description}
                    </p>
                    <p className="offset-1">{advice}</p>
                  </div>
                  <div style={{ minWidth: 52 }}>
                    <NgBooleanRenderer
                      value={enabled}
                      onChange={() => { }}
                      schema={{}}
                      ngOptions={{
                        spread: true,
                      }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
        );
      })}
    </div>
  );
};

function BoundsInput({ title, bounds, unit, ...props }) {
  const onChange = (key, value) => {
    props.onChange({
      ...bounds,
      [key]: value,
    });
  };

  const { excellent, sufficient, poor } = bounds;

  return (
    <div className="row">
      <p className="mb-1" style={{ fontWeight: 'bold' }}>
        {title}
      </p>

      <div className="d-flex align-items-center mb-3">
        {[
          { value: excellent, subTitle: 'Excellent value', label: 'Excellent', key: 'excellent' },
          {
            value: sufficient,
            subTitle: 'Sufficient value',
            label: 'Sufficient',
            key: 'sufficient',
          },
          { value: poor, subTitle: 'Poor value', label: 'Poor', key: 'poor' },
        ].map(({ value, label, subTitle, key }) => (
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
                placeholder: 'Value to achieve the rank',
                subTitle,
              },
            }}
            ngOptions={{
              spread: true,
            }}
            onChange={(e) => onChange(key, e)}
          />
        ))}
      </div>
    </div>
  );
}
