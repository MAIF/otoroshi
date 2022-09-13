import React from 'react'
import { SelectInput } from '../../components/inputs';
import { findAllEurekaServers, getEurekaApps } from '../../services/BackOfficeServices';

export class EurekaTargetForm extends React.Component {
  state = {
    eurekaServer: undefined,
    eurekaApp: undefined,
    eurekaServers: [],
    eurekaApps: [],
    route: this.props.route
  }

  componentDidMount() {
    findAllEurekaServers()
      .then(servers => {
        const p = this.props.route.plugins.find(p => p.plugin === "cp:otoroshi.next.plugins.EurekaTarget").config || {}
        const inOtoroshiCoreEurekaServer = p.eureka_server
        const inOtoroshiCoreEurekaApp = p.eureka_app

        if (inOtoroshiCoreEurekaServer)
          this.fetchEurekaApps(inOtoroshiCoreEurekaServer)

        this.setState({
          eurekaServers: servers,
          eurekaServer: inOtoroshiCoreEurekaServer,
          eurekaApp: inOtoroshiCoreEurekaApp
        })
      })
  }

  updateRoute = () => {
    this.props.update({
      eureka_app: this.state.eurekaApp,
      eureka_server: this.state.eurekaServer
    })
  }

  fetchEurekaApps = (v) => {
    getEurekaApps(v)
      .then(apps => this.setState({
        eurekaApps: apps.reduce((acc, c) => {
          if (!acc.find(instance => instance.app === c.application.name))
            return [
              ...acc,
              { ...c.application.instance }
            ]
          return acc
        }, [])
      }))
  }

  render() {
    console.log(this.state)
    return <div className="target_information pt-3 mt-3">
      <div className='mb-2'>
        <SelectInput
          label="Server"
          value={this.state.eurekaServer}
          onChange={(v) => {
            this.setState({
              eurekaServer: v
            }, this.updateRoute)
            this.fetchEurekaApps(v)
          }}
          possibleValues={this.state.eurekaServers.map(item => ({
            value: item.id,
            label: item.name
          }))}
        />
      </div>
      {this.state.eurekaApps.length > 0 && <SelectInput
        label="Eureka app"
        value={this.state.eurekaApp}
        onChange={(v) => {
          this.setState({
            eurekaApp: v
          }, this.updateRoute)
        }}
        possibleValues={this.state.eurekaApps.map(item => ({
          value: item.app,
          label: item.app
        }))}
      />}
    </div>
  }
}