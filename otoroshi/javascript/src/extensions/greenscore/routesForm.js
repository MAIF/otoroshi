import React, { useEffect, useState } from 'react';
import { NgBooleanRenderer, NgNumberRenderer, NgSelectRenderer } from '../../components/nginputs';
import { FeedbackButton } from '../../pages/RouteDesigner/FeedbackButton';

export default class GreenScoreRoutesForm extends React.Component {
  state = {
    editRoute: undefined,
  };

  addRoute = (routeId) => {
    this.props.rootOnChange({
      ...this.props.rootValue,
      routes: [
        ...this.props.rootValue.routes,
        {
          routeId,
          rulesConfig: this.props.rulesTemplate,
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
    this.props.rootOnChange({
      ...this.props.rootValue,
      routes: this.props.rootValue.routes.map((route) => {
        if (route.routeId === this.state.editRoute) {
          return {
            ...route,
            rulesConfig,
          };
        }
        return route;
      }),
    });
  };

  render() {
    const { routeEntities } = this.props;
    const { routes } = this.props.rootValue;

    const { editRoute } = this.state;

    return (
      <div>
        {editRoute && (
          <RulesWizard
            onRulesChange={this.onRulesChange}
            onWizardClose={this.onWizardClose}
            rulesConfig={routes.find((r) => r.routeId === editRoute).rulesConfig}
          />
        )}

        <RoutesSelector
          routeEntities={routeEntities.filter(
            (route) => !routes.find((r) => route.id === r.routeId)
          )}
          addRoute={this.addRoute}
        />

        <RoutesTable
          routes={routes}
          editRoute={this.editRoute}
          routeEntities={routeEntities}
          deleteRoute={this.deleteRoute}
        />
      </div>
    );
  }
}

const RoutesTable = ({ routes, editRoute, deleteRoute, routeEntities }) => {
  return (
    <>
      <div className="d-flex align-items-center m-3">
        <div style={{ flex: 1 }}>
          <label>Route name</label>
        </div>
        <span>Action</span>
      </div>
      {routes.length === 0 && (
        <p className="text-center" style={{ fontWeight: 'bold' }}>
          No routes added
        </p>
      )}
      {routes.map(({ routeId, rulesConfig }) => {
        return (
          <div key={routeId} className="d-flex align-items-center m-3 mt-0">
            <div style={{ flex: 1 }}>
              <label>{routeEntities.find((r) => r.id === routeId)?.name}</label>
            </div>
            <button type="button" className="btn btn-primary" onClick={() => editRoute(routeId)}>
              <i className="fa fa-hammer" />
            </button>
            <button
              type="button"
              className="btn btn-danger ms-1"
              onClick={() => {
                window
                  .newConfirm('Delete this route from the configuration ?', {
                    title: 'Validation required',
                  })
                  .then((ok) => {
                    if (ok) deleteRoute(routeId);
                  });
              }}>
              <i className="fa fa-trash" />
            </button>
          </div>
        );
      })}
    </>
  );
};

const RulesWizard = ({ onWizardClose, rulesConfig, onRulesChange }) => {
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
          <GreenScoreForm rulesConfig={rulesConfig} onChange={onRulesChange} />
          <div className="d-flex mt-auto ms-auto justify-content-between align-items-center">
            <FeedbackButton
              style={{
                backgroundColor: 'var(--color-primary)',
                borderColor: 'var(--color-primary)',
                padding: '12px 48px',
              }}
              onPress={() => Promise.resolve()}
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

const RoutesSelector = ({ routeEntities, addRoute }) => {
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
            options={routeEntities}
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

const GreenScoreForm = ({ rulesConfig, ...rest }) => {
  const { sections, thresholds } = rulesConfig;

  console.log(rulesConfig);

  const onRulesChange = (checked, currentSectionIdx, currentRuleIdx) => {
    rest.onChange({
      ...rulesConfig,
      sections: sections.map((section, sectionIdx) => {
        if (currentSectionIdx !== sectionIdx) return section;

        return {
          ...section,
          rules: section.rules.map((rule, ruleIdx) => {
            if (ruleIdx !== currentRuleIdx) return rule;

            return {
              ...rule,
              enabled: checked,
            };
          }),
        };
      }),
    });
  };

  const onBoundsChange = (thresholds) => {
    rest.onChange({
      ...rulesConfig,
      thresholds,
    });
  };

  return (
    <div>
      <div className="p-3">
        <h4>Thresholds</h4>
        <p>
          These threshold are used to assess the route. These checks are applied on the received and
          sent requests.
        </p>

        <BoundsInput
          title="Number of plugins on the route"
          bounds={thresholds.plugins}
          onChange={(plugins) => onBoundsChange({ ...thresholds, plugins })}
        />
        <BoundsInput
          title="Data size sent by the downstream service to not exceeed"
          bounds={thresholds.dataOut}
          onChange={(dataOut) => onBoundsChange({ ...thresholds, dataOut })}
        />
        <BoundsInput
          title="Header size sent by the downstream service to not exceeed"
          bounds={thresholds.headersOut}
          onChange={(headersOut) => onBoundsChange({ ...thresholds, headersOut })}
        />
      </div>
      {sections.map(({ id, rules }, currentSectionIdx) => {
        return (
          <div key={id} className="p-3">
            <h4 className="mb-3" style={{ textTransform: 'capitalize' }}>
              {id}
            </h4>
            {rules.map(({ id, description, enabled, advice }, currentRuleIdx) => {
              return (
                <div
                  key={id}
                  className="d-flex align-items-center"
                  style={{
                    cursor: 'pointer',
                  }}
                  onClick={(e) => {
                    e.stopPropagation();
                    onRulesChange(!enabled, currentSectionIdx, currentRuleIdx);
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

function BoundsInput({ title, bounds, ...props }) {
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
                unit: 'bytes',
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
