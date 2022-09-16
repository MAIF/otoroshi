import React from 'react'
import { SelectInput, TextInput } from '../../components/inputs';
import { getExternalEurekaServers } from '../../services/BackOfficeServices';
import { FeedbackButton } from './FeedbackButton';

export class ExternalEurekaTargetForm extends React.Component {
  state = {
    serviceUrlDefaultZone: "",
    eurekaApp: undefined,
    eurekaApps: [],
    route: this.props.route
  }

  componentDidMount() {
    const config = this.props.route.plugins.find(p => p.plugin === "cp:otoroshi.next.plugins.ExternalEurekaTarget").config || {}

    if (config.service_url_default_zone)
      this.setState({
        serviceUrlDefaultZone: config.service_url_default_zone,
        eurekaApp: config.eureka_app
      }, this.fetchFromUrl)
  }

  updateRoute = () => {
    this.props.update({
      eureka_app: this.state.eurekaApp,
      service_url_default_zone: this.state.serviceUrlDefaultZone
    })
  }

  fetchFromUrl = () => {
    return getExternalEurekaServers(this.state.serviceUrlDefaultZone)
      .then(apps => {
        this.setState({
          eurekaApps: apps.applications.application
        })
      })
  }

  render() {
    return <div className="target_information pt-3 mt-3">
      <div className='mb-2'>
        <TextInput
          value={this.state.serviceUrlDefaultZone}
          label="URL"
          onChange={serviceUrlDefaultZone => {
            this.setState({ serviceUrlDefaultZone })
          }}
        />
        <FeedbackButton
          className="mt-3 ms-auto d-flex"
          icon={() => <i className="fas fa-paper-plane" />}
          onPress={this.fetchFromUrl}
          text="Fetch apps"
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
          value: item.name,
          label: item.name
        }))}
      />}
    </div>
  }
}
