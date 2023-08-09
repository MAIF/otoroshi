import React, { useState } from 'react';
import { NgBooleanRenderer, NgNumberRenderer, NgSelectRenderer } from '../../components/nginputs'
import { FeedbackButton } from '../../pages/RouteDesigner/FeedbackButton';
import { calculateGreenScore } from './util';

export default class GreenScoreRoutesForm extends React.Component {

    state = {
        editRoute: undefined,
    }

    addRoute = routeId => {
        this.props.rootOnChange({
            ...this.props.rootValue,
            routes: [
                ...this.props.rootValue.routes,
                {
                    routeId,
                    rulesConfig: this.props.rulesTemplate
                }
            ]
        });

        this.editRoute(routeId);
    }

    editRoute = routeId => this.setState({
        editRoute: routeId
    })

    onWizardClose = () => {
        this.setState({
            editRoute: undefined
        })
    }

    onRulesChange = rulesConfig => {
        this.props.rootOnChange({
            ...this.props.rootValue,
            routes: this.props.rootValue.routes.map(route => {
                if (route.routeId === this.state.editRoute) {
                    return {
                        ...route,
                        rulesConfig
                    }
                }
                return route
            })
        })
    }

    render() {
        const { routeEntities } = this.props;
        const { routes } = this.props.rootValue;

        const { editRoute } = this.state;

        return <div>
            {editRoute && <RulesWizard
                onRulesChange={this.onRulesChange}
                onWizardClose={this.onWizardClose}
                rulesConfig={routes.find(r => r.routeId === editRoute).rulesConfig} />}

            <RoutesSelector
                routeEntities={routeEntities.filter(route => !routes.find(r => route.id === r.routeId))}
                addRoute={this.addRoute} />

            <RoutesTable routes={routes} editRoute={this.editRoute} routeEntities={routeEntities} />
        </div>
    }
}

const RoutesTable = ({ routes, editRoute, routeEntities }) => {
    return <>
        <div className='d-flex align-items-center m-3'>
            <div style={{ flex: 1 }}>
                <label>Route name</label>
            </div>
            <span>Action</span>
        </div>
        {routes.length === 0 && <p className='text-center' style={{ fontWeight: 'bold' }}>No routes added</p>}
        {
            routes.map(({ routeId, rulesConfig }) => {
                const rankInformations = calculateGreenScore(rulesConfig)
                return <div key={routeId} className='d-flex align-items-center m-3 mt-0'>
                    <div style={{ flex: 1 }}>
                        <label>{routeEntities.find(r => r.id === routeId)?.name}</label>

                        <span><i className="fa fa-leaf ms-2" style={{ color: rankInformations.rank }} /></span>
                    </div>
                    <button type="button" className='btn btn-primary' onClick={() => editRoute(routeId)}>
                        <i className='fa fa-cog' />
                    </button>
                </div>
            })
        }
    </>
}

const RulesWizard = ({ onWizardClose, rulesConfig, onRulesChange }) => {
    return <div className="wizard">
        <div className="wizard-container">
            <div className="d-flex" style={{ flexDirection: 'column', padding: '2.5rem', flex: 1 }}>
                <label style={{ fontSize: '1.15rem' }}>
                    <i className="fas fa-times me-3" onClick={onWizardClose} style={{ cursor: 'pointer' }} />
                    <span>Check the rules of the route</span>
                </label>
                <GreenScoreForm
                    rulesConfig={rulesConfig}
                    onChange={onRulesChange}
                />
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
}

const RoutesSelector = ({ routeEntities, addRoute }) => {
    const [route, setRoute] = useState(undefined);

    return <div className='row my-2'>
        <label className='col-xs-12 col-sm-2 col-form-label'>Add to this group</label>
        <div className='d-flex align-items-center col-sm-10'>
            <div style={{ flex: 1 }}>
                <NgSelectRenderer
                    value={route}
                    placeholder="Select a route"
                    label={' '}
                    ngOptions={{
                        spread: true
                    }}
                    onChange={setRoute}
                    margin={0}
                    style={{ flex: 1 }}
                    options={routeEntities}
                    optionsTransformer={(arr) =>
                        arr.map((item) => ({ label: item.name, value: item.id }))
                    }
                />
            </div>
            <button
                type='button'
                className='btn btn-primaryColor mx-2'
                disabled={!route}
                onClick={() => {
                    addRoute(route)
                    setRoute(route)
                }}>
                Start to configure
            </button>
        </div>
    </div>
}

const GreenScoreForm = ({ rulesConfig, ...rest }) => {
    const { sections } = rulesConfig;

    const thresholds = rulesConfig.thresholds || {
        plugins: 0,
        dataOut: 0,
        headersOut: 0
    };

    console.log(rulesConfig, thresholds, rest)

    const onRulesChange = (checked, currentSectionIdx, currentRuleIdx) => {
        rest.onChange({
            ...rulesConfig,
            sections: sections.map((section, sectionIdx) => {
                if (currentSectionIdx !== sectionIdx)
                    return section

                return {
                    ...section,
                    rules: section.rules.map((rule, ruleIdx) => {
                        if (ruleIdx !== currentRuleIdx)
                            return rule;

                        return {
                            ...rule,
                            enabled: checked
                        }
                    })
                }
            })
        })
    }

    const onDynamicSectionChange = (key, newValue) => {
        rest.onChange({
            ...rulesConfig,
            thresholds: {
                ...rulesConfig.thresholds,
                [key]: newValue
            }
        })
    }

    return <div>
        <div className='p-3'>
            <h4>Thresholds</h4>
            <p>These threshold are used to assess the route. These checks are applied on the received and sent requests.</p>
            <NgNumberRenderer
                value={thresholds.plugins}
                label="Plugins (number)"
                schema={{}}
                onChange={e => onDynamicSectionChange("plugins", e)} />

            <NgNumberRenderer
                value={thresholds.dataOut}
                label="Data out (bytes)"
                schema={{}}
                onChange={e => onDynamicSectionChange("dataOut", e)} />

            <NgNumberRenderer
                value={thresholds.headersOut}
                label="Headers out (bytes)"
                schema={{}}
                onChange={e => onDynamicSectionChange("headersOut", e)} />
        </div>
        {sections.map(({ id, rules }, currentSectionIdx) => {
            return <div key={id} className='p-3'>
                <h4 className='mb-3' style={{ textTransform: 'capitalize' }}>{id}</h4>
                {rules.map(({ id, description, enabled, advice }, currentRuleIdx) => {
                    return <div key={id}
                        className='d-flex align-items-center'
                        style={{
                            cursor: 'pointer'
                        }}
                        onClick={e => {
                            e.stopPropagation();
                            onRulesChange(!enabled, currentSectionIdx, currentRuleIdx)
                        }}>
                        <div style={{ flex: 1 }}>
                            <p className='offset-1 mb-0' style={{ fontWeight: 'bold' }}>{description}</p>
                            <p className='offset-1'>{advice}</p>
                        </div>
                        <div style={{ minWidth: 52 }}>
                            <NgBooleanRenderer
                                value={enabled}
                                onChange={checked => onChange(checked, currentSectionIdx, currentRuleIdx)}
                                schema={{}}
                                ngOptions={{
                                    spread: true
                                }}
                            />
                        </div>
                    </div>
                })}
            </div>
        })}
    </div>
}
