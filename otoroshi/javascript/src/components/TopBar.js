import React, { Component } from 'react';
import PropTypes from 'prop-types';
import Select, { Async } from 'react-select';
import _ from 'lodash';
import fuzzy from 'fuzzy';
import { DefaultAdminPopover } from '../components/inputs';

import * as BackOfficeServices from '../services/BackOfficeServices';

function extractEnv(value = '') {
  const parts = value.split(' ');
  const env = _.last(parts.filter((i) => i.startsWith(':')));
  const finalValue = parts.filter((i) => !i.startsWith(':')).join(' ');
  if (env) {
    return [env.replace(':', ''), finalValue];
  } else {
    return [null, value];
  }
}

// http://yokai.com/otoroshi/
export class TopBar extends Component {
  state = {
    env: {
      clusterRole: 'off',
    },
    isActive: false
  };
  
  handleToggle = () => {
    this.setState({ isActive: !this.state.isActive });
  };

  searchServicesOptions = (query) => {
    return fetch(`/bo/api/search/services`, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ query: '' }),
    })
      .then((r) => r.json())
      .then((results) => {
        const options = results.map((v) => ({
          type: v.type,
          label: v.name,
          value: v.serviceId,
          env: v.env,
          action: () => {
            if (v.type === 'http') {
              this.gotoService({ env: v.env, value: v.serviceId });
            } else if (v.type === 'tcp') {
              this.gotoTcpService({ env: v.env, value: v.serviceId });
            }
          },
        }));
        options.sort((a, b) => a.label.localeCompare(b.label));
        options.push({
          action: () => (window.location.href = '/bo/dashboard/admins'),
          env: <span className="fas fa-user" />,
          label: 'Admins',
          value: 'Admins',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/alerts'),
          env: <span className="fas fa-list" />,
          label: 'Alerts Log',
          value: 'Alerts-Log',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/exporters'),
          env: <span className="fas fa-paper-plane" />,
          label: 'Exporters',
          value: 'exporters',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/audit'),
          env: <span className="fas fa-list" />,
          label: 'Audit Log',
          value: 'Audit-Log',
        });
        options.push({
          label: 'CleverCloud Apps',
          value: 'CleverCloud-Apps',
          env: <i className="fas fa-list-alt" />,
          action: () => (window.location.href = '/bo/dashboard/clever'),
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/dangerzone'),
          env: <span className="fas fa-exclamation-triangle" />,
          label: 'Danger Zone',
          value: 'Danger-Zone',
        });
        options.push({
          label: 'Documentation',
          value: 'Documentation',
          env: <i className="fas fa-book" />,
          action: () => (window.location.href = '/docs/index.html'),
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/stats'),
          env: <span className="fas fa-signal" />,
          label: 'Global Analytics',
          value: 'Global-Analytics',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/events'),
          env: <span className="fas fa-list" />,
          label: 'Global Events',
          value: 'Global-Events',
        });
        options.push({
          label: 'Groups',
          value: 'Groups',
          env: <i className="fas fa-folder-open" />,
          action: () => (window.location.href = '/bo/dashboard/groups'),
        });
        options.push({
          label: 'Organizations',
          value: 'Organizations',
          env: <i className="fas fa-folder-open" />,
          action: () => (window.location.href = '/bo/dashboard/organizations'),
        });
        options.push({
          label: 'Teams',
          value: 'Teams',
          env: <i className="fas fa-folder-open" />,
          action: () => (window.location.href = '/bo/dashboard/teams'),
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/loggers'),
          env: <span className="fas fa-book" />,
          label: 'Loggers level',
          value: 'Loggers-level',
        });
        options.push({
          label: 'Services',
          value: 'Services',
          env: <i className="fas fa-cubes" />,
          action: () => (window.location.href = '/bo/dashboard/services'),
        });
        options.push({
          label: 'Tcp Services',
          value: 'tcp-services',
          env: <i className="fas fa-cubes" />,
          action: () => (window.location.href = '/bo/dashboard/tcp/services'),
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/map'),
          env: <span className="fas fa-globe" />,
          label: 'Services map',
          value: 'Services-map',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/sessions/admin'),
          env: <span className="fas fa-user" />,
          label: 'Admin. sessions',
          value: 'Admin-sessions',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/sessions/private'),
          env: <span className="fas fa-lock" />,
          label: 'Priv. apps sessions',
          value: 'Priv-apps-sessions',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/top10'),
          env: <span className="fas fa-fire" />,
          label: 'Top 10 services',
          value: 'Top-10-services',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/jwt-verifiers'),
          env: <span className="fas fa-key" />,
          label: 'Global Jwt Verifiers',
          value: 'Jwt-Verifiers',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/auth-configs'),
          env: <span className="fas fa-lock" />,
          label: 'Global auth. configs',
          value: 'auth-configs',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/validation-authorities'),
          env: <span className="fas fa-gavel" />,
          label: 'Validation authorities',
          value: 'validation-authorities',
        });
        options.push({
          action: () => (window.location.href = '/bo/dashboard/certificates'),
          env: <span className="fas fa-certificate" />,
          label: 'SSL Certificates',
          value: 'certificates',
        });
        if (this.state.env.clusterRole === 'Leader') {
          options.push({
            action: () => (window.location.href = '/bo/dashboard/cluster'),
            env: <span className="fas fa-network-wired" />,
            label: 'Cluster view',
            value: 'cluster-view',
          });
        }
        if (this.state.env.scriptingEnabled === true) {
          options.push({
            action: () => (window.location.href = '/bo/dashboard/plugins'),
            env: <span className="fas fa-book-dead" />,
            label: 'Plugins',
            value: 'plugins',
          });
        }
        if (this.state.env.providerDashboardUrl) {
          const providerDashboardTitle = this.state.env.providerDashboardTitle;
          options.push({
            action: () => (window.location.href = '/bo/dashboard/provider'),
            env: <img src="/assets/images/otoroshi-logo-inverse.png" width="16" />,
            label: providerDashboardTitle,
            value: providerDashboardTitle.toLowerCase(),
          });
        }
        options.push({
          action: () => (window.location.href = '/bo/dashboard/snowmonkey'),
          env: (
            <span>
              <svg
                xmlns="http://www.w3.org/2000/svg"
                className="monkeyMenu"
                viewBox="0 0 244.1 244.1">
                <title>nihonzaru</title>
                <g id="_x34_9a67235-e3a1-4929-8868-b30847745300">
                  <g id="b11c2c3a-c434-45dc-a441-e60dd5d9d3f6">
                    <circle className="st0" fill="#343736" cx="122" cy="122" r="120" />
                    <polygon
                      className="st1"
                      points="73.8,56.4 56,85.6 52.7,123.5 71,159.6 69.8,187.7 85.7,206.2 122.1,216.9 157.1,204.9 182.5,179
			196,116.3 218.9,76.5 152.4,40.7 130.6,35.1"
                      fill="#FFFFFF"
                    />
                    <path
                      className="st0"
                      fill="#343736"
                      d="M213.9,111.2c6.8-2.9,13.7-5.7,20.5-8.5c-2.4-1-3.4-2-6.7-2.4c2.7-1.9,4.5-1.8,7.3-2.9
			c-5.1,0-15.2,0.4-21.3-2.8c3.8-0.8,7.5-2,11.1-3.6c-14.4-0.6-10.8,1.5-21.1-5.3c3.6-0.8,7.9,1.9,11.8,1.4c-1.9-1.3-4-2.3-6.2-3
			c0.8-0.2,17.5-2.1,17.5-6c0-0.5-17.6-5.5-21.3-6.2c3.4-0.3,6.7-0.8,10-1.5c-5.5-0.8-11.1-1-16.6-0.6c8-5.1,16.9-9.1,24.5-14.2
			c-8.4,0.5-16.3,3.9-24.7,5.7c3.6-5.4,22.6-12,22.6-17c0,0.2-22.1,11.3-24.6,12.7c4.4-5.2,9.3-8.7,14.2-13.3
			c-9.3,2.5-15.7,7.4-25.6,8.4c4.8-3.9,11-5.1,16.1-8.6c-3,0.5-5.9,1.2-8.7,2.2c0.3-0.3,12.1-10.8,11.5-11.3
			C200,30.7,186.9,36,183,37c3.3-2.9,6.4-6,9.2-9.4c-5.7,3.1-13.8,7.8-18.9,11.7c-0.7,1.1,14.3-26,7.2-26.8
			c-2.8-0.3-12.5,12.7-15.3,14.8c0.4-1.9,3.6-13.2,2.6-13.4c-2.3-0.3-10.6,8.1-12.8,10.8c4.4-6.6,5.4-9,11.6-14.1
			c-4.9,1.4-9.6,3.3-14,5.7l3.5-7.3c-1,1.6-1.6,1.4-1.8-0.9c-3-0.9-14.8,26.3-5.7,2.5c-2.5,3.2-4.9,6.5-7.1,9.9
			c1.5-5.2,3.3-10.2,5.4-15.1c-0.9,1-1.5,0.6-1.8-1.2c-2.3-0.3-7,14.7-6.6,4.8c-2.9,4.6-11.9,17.5-12.4,6.8
			c-6.4,17.3,0.5,0.5-3.4,6.5c0.6-3.8,0.8-7.7,0.4-11.6c-1.5,3.7-2.5,7.5-3,11.4c-1-2-1.7-4.1-2.1-6.3c-0.9,4.9-1,13.1-0.8,19.3
			c-1.3-7.2-2.5-17.3-6-24.4c-0.2,6.3,0.7,12.6,2.7,18.6c-6.5-7.9-3.7-0.1-6.6-13.8c-1.5,3-2.4,6.2-2.7,9.5
			c-0.7-0.8-2.5-6.2-4.8-5.2c-1.7,0.7-0.9,11.2-0.9,11.5c-3.2-7.6-6.4-15.1-9.6-22.7v27.8c-4.2-6.7-4.3-15.8-7.3-23.3
			c0.3,6.4,1.2,12.8,2.6,19c-7-2.5-7-5-11.6-10.8c4.1,25.6,0.2-0.7-0.9,4.1c1.5,1.4,2.1,3.5,1.8,5.5c-7.1-5-12.9-12.6-16.8-20.3
			c-0.2,0.2-0.5,0.3-0.8,0.2C56,18.2,66.8,26,68.6,34C68,32.9,58.4,18.7,57.5,18.8c-4.9,0.8,4.8,12.4,6,14.3
			c-0.9-0.8-16.7-10.4-16.3-10.5c0.7-0.2,18.4,26.9,19.7,28.8c-3.3,0.8-10.3-8-13.6-9.4c2.4,3.6,5.4,6.8,8.9,9.4
			c-11.5-1.7-20.2-10.1-31-14.1c6.1,5,13.8,7.9,19.7,12.9c-3.7,1.2-21-5.1-21-0.5c0,0.7,32.3,14.8,34.5,16.2
			c-12.1-2.1-23.7-8.3-35.8-9.3c5,3.2,10,11.7,14.5,17.1c-7.3,0-13.6-3.7-21.3-2.7c5.2,2.2,10.9,3.4,16.5,3.4
			c-7.9,1.8-17.7,2.6-24.4,4.8c3.4,0.7,6.8,1.6,10.1,2.9c-4.2-0.6-8.4-0.9-12.7-1c6.2,2.2,12.6,3.7,19.1,4.6
			c-5.1-0.3-9,0.8-12.3,1.1l24.9,10.6c-5,4.1-24.7,1.8-29.2,2.7c1.2,0.7,0.4,1-2.6,0.9c5.7,1.5,6.4,2.7,11.4,5
			c-5.2-0.3-11.1-0.2-16.1,1.4c7.8,2.1,15.4,1.7,22.8,3.6c-2,1.7-23.2,9.3-23.2,11.9c0,3,27.4,2.1,28.9,4.4c-6,6.3-13.8,9.7-24.6,14
			c3.6,0.3,7.3,0.3,10.9-0.2c-5,6-11.7,13.6-15.5,12.4c-0.7,2.9,0.1,2.6-1.9,5.2c4.3-0.2,0.1-1,3.3,1.3c4.9-7.9,17-13.2,26.1-18.6
			c-3.2,6.8-5,8.3-11.6,12.7c12.3-1.8-2,0.9,4.1,3c2.1,0.7,15.5-6.7,19.1-7.5c-9.5,7.9-13,17.3-23.2,25.1c11.4-3.4,2.4-1.7,2.3,4.4
			c4-3,8.7-8.6,13.1-10.7c-1.9,2-18.7,39.1-16.8,39.9c2.4,0.9,19.6-20.2,23.1-22.8c-2.9,3.2-5.2,7-8,10.3c5.4-3.5,11.5-6.2,16.9-9.8
			c-4.2,4.4-7.9,9.2-11,14.5c6.8-5,12.6-12.3,18-18.6c0,24.1-0.8,26.1-16.8,41.8c3.5-1.5,11.6-5,14.7-8.2c2-15.3-2.9,9.1-0.9,9.9
			s15-6.8,17.3-7.9c-3.2,3.4-6.1,7.1-8.7,11c10.5-7.1,17.9-20.8,18.9-0.8c16.9-28.6,26.2,10.4,35.5,9.1c0.3-0.1,8.9-11.6,11.9-13.1
			c7.8-3.6,9.2-0.4,20.6-4c9.4-3,9.6,13,12.6-6.3c11.1,8.1,16.2,4.4,18.4-8.8c0.4,0.4,9.7,8.8,10.1,8.7c5.5-2.2-2.3-18.6,4.7-8
			c0.2-3.6-1.4-6.9-1.3-10.4c4.7,3.1,4.9,8.4,8.6,12.9c-0.3-3.4-1.1-6.7-2.5-9.9c2.4,1,4,4.1,5.7,5.9c-0.1-2,1.2-8.4-0.6-14
			c5.4,2.5,8,7.4,13.3,11.9c-2.5-4.8-5.4-9.3-8.7-13.4c4.7-5.6,10.2-0.4,12.7,1.8c-5.4-8.7-9.1-14.4-9.4-24.8
			c5.8,0.7,9.6-1,15.1,0.8c-2.9-6.4-8.6-9.8-12.3-16c2.8,1.7,5.7,3.2,8.8,4.4c-7.4-7.6-15.7-11.6-21.9-20
			c5.3-1.7,13.2,11.2,16.9,9.9c4.2-1.5-11-10.9-11.5-11.9c4-2.2,14.2,4.3,18.6,6.5c-6.8-7.7-11.4-12.5-19.1-18.4
			c3.3,2.2,7.1,3.6,11,4.1c-3.3-3.6-12.3-4.4-14.4-7.4c8.9-1.9,17.5-4.7,25.9-8.1c-9.3,1.2-18.4,3.2-27.3,6
			C204.4,114.3,213.4,111.5,213.9,111.2z M225,101.3c1.7,0.4,3.2-0.6,4.6,1.1C230.3,102.5,222.1,102.2,225,101.3L225,101.3z
			 M222.3,100.1c3,3.1-4,1.3-6.3,1.3L222.3,100.1z M40.2,84.9c-3.9-0.7-8.2-1.4-12.2-2.1C31.5,82,38.1,84.4,40.2,84.9z M32.6,171.4
			c-0.3,0.4-5.2,5.9-6.5,6.3c0.1-0.1,0-0.1-0.1,0C25.2,179.1,35.8,167.2,32.6,171.4z M154.1,18.4l4.8-2.6c-5.2,5.6-8.4,15.5-15.1,19
			C145.3,29.2,148.9,21,154.1,18.4L154.1,18.4z M149,17.1c1.4-2.5,2.7-5.9,4.8-8c0.4,1.3-2.6,5.2-3.3,6.5l2.4-2.5
			C147.3,20.1,149.4,17.6,149,17.1z M127.3,28.7c0.8-3.3,2.6-7.1,5.1-9.4C132.5,23.4,125.5,36.4,127.3,28.7z M176,113.7
			c-1.8,2.6-4,4.9-6.6,6.7c10.3,3.5,8,22.1-4,19.2l5.2-7.5c-3.2,1.7-14.8,6-16.2,8.5c-4.5,8.4,3.3-1.7,0.2,10.5
			c-2.7,10.6,3.4,21-9.7,27.2c19.1,10.6-37.4,31.8-53.4,12.4c-11.3-13.7,1.7-7.5,2-16.3c0.3-12.9-9.7-18.1,1.4-30.8
			c-22.2,26.1-47.2-45.6-14-66.3h-6.7c3.4-3.5,6.9-8,10.8-10.9c-3.1,2.7-5.8,6.2-8.6,9.2c3.9-1.2,8.2-1.8,12-3.3l-3.7,3.3
			c4.1-2.1,8.9-2.8,13.2-4.6c-2.6,3.5-4.1,8-6.9,11.3l18.3-12.2v7.2c3.7-2.7,9.4-2.7,13.2-5.3l2.3,7.1c1-3.9,3.2-5,3.3-10.7
			c1.7,6.5,3.2,8,9.6,7.8c12.7-0.4,1.2-7.7,19.4,0.1C168.1,81.1,185,100.2,176,113.7L176,113.7z M200.7,92c-1.3,0.7-4.4-0.6-2-2
			C200,89.2,203.1,90.6,200.7,92z"
                    />
                    <path
                      className="st0"
                      fill="#343736"
                      d="M93.2,91.2c0,0,17.2,2.4,19.4,3.2s0,7.7,0,7.7s0.3-1.8-5.3-4S90.7,97.7,87.3,99l-8.5,3.2l-2.1-0.9l-7-3.1
			l11.6,6.8H64.1l16.9,2.8l4.7-2.5c0,0,2.8,5.9,7.2,6.9s11.3,1.6,15.7,0c2.6-0.9,5.1-1.9,7.5-3.1c0,0,0,5.3-1.6,7.8
			s-13.8,13.2-13.8,13.2l-10.6-11.3c0,0,7.2,1.6,11.9,0.3c2.8-0.7,5.5-1.7,8.2-2.8c0,0-12,0.6-16.7,0s-8.8-5.6-8.8-5.6l-1.9,1.9
			l2.8,10l-5.9-2.1l10.3,15.3c0,0-8.8,0.6-12.5-5.6s-6.2-16-7.8-16.6s-1.9,8.4-1.9,8.4l9.7,15.4l8.2,4.1l6.9,2.4c0,0,0.9,1-2.5,6.5
			s-7,9.6-4.4,15.5s-0.9,2.8,2.6-1.3s5.6-13.2,9.7-14.7s10-3.9,10-3.9s4.1-9.6,8.2-10.6s8.5-0.3,11.4,0s6.1,5.2,6.1,6.5
			c0.4,2.1,0.9,4.2,1.6,6.3l10.2,6.6c0,0-12.4-19.1-11.8-21s11.8,1.6,11.8,1.6s-12.9-11.3-14.8-14.9s-4.8-8.9-3.1-10.5
			c1.7-1.6,2.4-0.9,6.1,0.9s13.5,3.8,15.2,3.4s12.4-3.8,12.4-3.8s-12.5,1.6-15.8,1.2s-14.8-4.4-14.8-4.4s5.6-5.3,5.6-7.5
			s1.2-9.7,6-8.8s15,3.7,19.1,6.2s12.5,3.7,12.9,1.6s-6-9.4-10-9.4s-14.4-4.7-18.6-4.7s-12.1,5.3-17.9,4.7s-14.6-3.1-19-2.8
			s-14.1-4.4-19.7-1.9s-20.7,8.4-22.3,9.5s-8.8,8.7-6.9,9s11.3-1.3,11.3-1.3s-7.2-3.9-2.8-5.6S93.2,91.2,93.2,91.2z"
                    />
                    <path
                      className="st0"
                      fill="#343736"
                      d="M110.9,147.6c0,0,0.6,1.9,3.6,2.2c1.6,0.1,3.2,0.6,4.7,1.3c0,0,2,3.3,2,4.9s0.8,15.7,0.8,15.7
			s-1.9-14-2.2-15.2s-3.7-2.2-5.8-3.3S110.9,147.6,110.9,147.6z"
                    />
                    <path
                      className="st0"
                      fill="#343736"
                      d="M134,147.6c0,0-0.6,1.9-3.6,2.2c-1.6,0.1-3.2,0.6-4.7,1.3c0,0-2,3.3-2,4.9s-0.8,15.7-0.8,15.7
			s1.9-14,2.2-15.2s3.7-2.2,5.8-3.3S134,147.6,134,147.6z"
                    />
                    <path
                      className="st0"
                      fill="#343736"
                      d="M113.6,97.5c0,0-3.4-3.8-7.1-5.2s-17.7,1.4-19.4,2.6s-5.6,4.9-4.2,6.7s-2.5,5.2-2.5,5.2s-7.5,0.8-8.2,3.3
			s-1.6,4.4,0.9,10.5s-2.3,6.2-2.3,6.2s-3.8-3.6-7.2-12.2S57,104.5,62,100s13.8-8.8,18.6-9.7s18.8-0.3,19.9-0.6s11.1,1.2,13,2.3
			s3.9,3.9,2.9,4.9C115.6,97.4,114.6,97.6,113.6,97.5z"
                    />
                    <path
                      className="st0"
                      fill="#343736"
                      d="M72.2,93.4c0,0-4.3,15.8,1.4,6.5s9.8-11.6,9.8-11.6s13.9-4.2,14.6-1.4s-4.3-10.5-4.3-10.5l2.8,2.5l6.9-9.6
			l4.3,9.4l8.3-10.3c0,0,36.8-3,37.1-6.8s-40.2-19.2-40.2-19.2L69.8,54.4L47.2,93.4l0.8,29.4l24.2,31.7l10.4,12.7
			c0,0,12.4-14.1,13.6-18.6s-2-7.2-8.2-9.2s-17-9.8-19.3-22c-1.5-7,0.2-14.4,4.7-20L72.2,93.4z"
                    />
                    <path
                      className="st1"
                      fill="#FFFFFF"
                      d="M11,149.6c14.4-11.7,28.1-36.6,48.4-35.1l-3.4,2.7l3.4-0.7l-2.2,3.1l4.3-0.4c-1.7,2.1-2.8,4.5-3.2,7.2
			c1.9,1.2,4.4,0.8,5.8-1l-4.7,9.8l4.7-3.2l-1.7,7.9c0.9-1.6,2-2.2,3.5-1.8c-3.2,6.1,4.2,8.4,3.8,9.6c-0.3-2.4,0.6-4.7,2.5-6.2
			c-0.3,2.9-1.1,5.7-2.5,8.2l4.9-2.9l-2.4,5.3l3.8-2.3l-1.2,4.1l3.3-4.1v5c3.2-1.5,6.7-3.8,9.9-5l-6.1,6.8c3.2-2.5,7-4.4,10.3-6.8
			c-3.7,4.3-5.9,9.6-6.4,15.3c-0.2,4.3,3.5,7.9,3.3,11.9c-0.2,4.3-8.6,9.1-8.6,10.4c-0.2,14.8,36.9,21.5,10.1,33.5
			c1.6-3.9,2.2-8.3,3.9-12.1c-2.7,2.7-5.3,5.4-7.8,8.3c1.2-1.6-2.8-9.3,1.2-11c-6.8,3-13.4,4.6-19.5,9.9c3-5.8,4.3-11.1,8.2-16.4
			c-11.8,4-18.6,14-20.8,25.8c0.2-2.2-1.6-17.6,0.2-18.1c-2.1,0.6-9.1,10.7-10.4,12.8c3-6.6,6.6-19.4,12.3-24.2
			c-3.1,2.6-6.4,5.4-9.2,8.3c3.8-7.3,6.8-14.9,9.2-22.8c-5.9,3.2-12.8,5.5-18.3,9.4c5.5-3.7,14.1-8.5,17.6-14.3
			c1.1-1.8-21.6,14.4-26.1,18.6c2.2-3.6,3.9-7.6,6.1-11.1l-16.2,20.5c4.6-21,18.5-37.7,36.9-48.4c-18.6,1.4-24.6,20.5-45.5,21.4
			c17.4-7.3,25.6-22.5,41.2-30.5c-7.3,1.8-13.9,6.1-18.5,12.1c2.9-9.4,11.2-15.3,20.5-17.3c-14.6-0.4-21.3,12.6-34.7,15
			c11.1-8,19.1-20.5,31.8-25.7c-9.2,2.8-17.6,7.8-24.5,14.6c7.8-7.3,16.6-13.6,26.1-18.5C38.2,127.1,25.3,142.5,11,149.6z"
                    />
                    <path
                      className="st1"
                      fill="#FFFFFF"
                      d="M102.6,201c5.2,5.5,8.2,12.7,8.3,20.3l2-10.1c3.5,4.6,8.5,9,11.3,14.1c-4.2-4.7-7.2-11-10.8-16.2
			c4.3,3.8,6,3.2,10.8,10.6c1.9-3.7,5.1-7,6.6-10.8c-3.4,8.3-3.2,17.1,2,24.2l3.9-7.8l1.4,7.8l7.5-9.2c0.3,10.1,2.5,8.4,6.4,16
			c-0.9-1.8-5.6-19.7-4-19.4c6.5,1.2,15,11.6,18.6,16.1c-2.6-5-4.5-10.4-7.3-15.2c1.3,2.1,8.6,13.8,11.1,13.8
			c0.6,0-2.9-12.9-3.3-13.8c3.3,5.9,7.2,9.8,11.3,14.8c-2.7-5.8-4.4-12-7.3-17.6c2.5,4.6,6.2,8.5,8.9,12.9c-0.4-1.3-0.4-18.1,0-17.6
			c4.9,5.5,9.6,11.3,14.3,16.9c-3.7-7.5-9.4-16.5-11-24.8c0.9,4.4,10,13.4,12.7,18.2c-1.8-4.8-2.5-10-4.2-14.8
			c3.4,5.5,7.9,10.4,11.3,16c-3.4-12.3-7-24.3-8-37.1l12.3,23.5c-2.2-6.1-3.1-12.6-5-18.8l18.8,18.6l-13.8-20.9l4.6,2.1
			c-8.8-11.6-17-27.8-31-33c18,1.5,28.3,20.3,36.7,34.4c-2.9-7.7-5.4-15.6-7.6-23.5c3.3,6.2,7.7,11.9,11.1,18.1
			c-1.9-18.3-12.8-31.2-26.6-42.7c12.6,6.3,17.7,16.9,27.7,25.6c-5.3-9.9-9.3-20.1-18.3-27.3c9.7,8,19.6,15.4,27.8,25.1L215.5,150
			c7.6,4.4,15.9,7.7,24.6,9.6c-14.4-17.5-35-28.5-53.7-40.8c18.7-2.8,33.2,10.8,47.6,20.6c-13.5-13.2-25-21.9-41.9-30.3
			c14.4,0,28.6,1.7,41.9-4.7c-11.9,0-32.1,4.2-40.5-4.5c11.9,0.9,23.9,0.9,35.9,0L200.6,95l26.8,1.6l-41.9-9.8
			c11.1-7.2,22.2-5.2,35-5.1c-12.4-1.1-24.8-1.6-36.9-4.6l33.9-3.5c-14.4,0-30.4,2.6-43.5-2.6c13.1-1.8,26.2-2.6,38.3-8.5l-33.2,4.9
			c10.9-4,22.8-5.4,28.1-16.9l-18.6,10.8c3.9-3.6,7-8.1,10.8-11.8c-13.4,7-29.9,17.1-45.2,11.8c3.7-14.6,21.8-10.6,34.8-14.3
			c-4.1-0.7-8.1-2.4-12.2-3l11.1-1.7l-4.5-2.1l20.2-10.3l-21.2,8l21.4-16.2L176,38.8l2.3-7.2c-5.5,1.1-10.8,3-15.7,5.6l5.4,4.2
			c-9.6,2.5-16.1,4.2-17.4,9.6l-8.2-4c9.8-6.9,17.3-17.1,22.6-27.8l-16,17c2.1-4.3,3.3-9.1,5.4-13.4c-4.1,5.7-9.7,10.4-14.1,16
			c1-6.4,0.9-13.2,2.4-19.6c-0.1,4.5-2.9,10-4,14.4c-7-2.7-12.9-7.8-16.5-14.4l2.4,10.4c-4.2-2.2-9.5-3-13.6-5.6
			c4.2,3.5,7.1,8.5,11.1,12.2c-12-2.7-24.1-3.4-36.1,0c4.1,1.5,7.8,4.2,12,5.6c-4-0.1-8,0.9-12,0.7c4.5,1.1,8.7,4,13.2,5.4
			c-6,2.1-11.7,5.9-17.9,7.5c6.1-0.3,12.2,1.1,18.3,0.7c-6.2,2.1-12.1,5.1-18.3,7c6.7-1.2,13.5-2.1,20.2-3.5
			c-10.7,2.5-19.7,2.2-25,10.5c4.9-2,10.9-2.8,15.6-5.3l-9.3,6c4.7-2.4,9.7-4.2,14.5-6.5c-3.2,2.4-6,5.3-9.1,7.8
			c3.9-2.3,8.2-3.9,12.2-6.1c-3.3,3.6-6.1,7.7-9.6,11.1c5.7-6.2,12.2-12.4,20.2-14.3l-4.1,9.2c3-2.9,6.7-5.2,9.6-8.2l-5.6,9.2
			c3.7-4.1,8.1-7.5,11.9-11.5c-1.9,3.8-3.9,7.7-6.1,11.3c2.8-4.6,3.5-11.6,5.3-16.7c-8.9-0.4-15.6-5-18-13.9
			c3.9,3.6,8.8,6.2,12.7,9.9c-2.3-3.2-3.7-7.2-5.8-10.6c4.2,2.7,9.7,3.2,14,5.6l-2.1-8.5l5.6,5.4l5.6-7.7l-1.3,10.6l5.6-2.8
			c-4.6,2.8-5,6.2-10.8,8.5c0.7,0.6,7.6,6.6,8,6.6c-2.7,0.5-5.4,1.3-8,2.3c8.5,6.6,16.1,0.9,23.3-5.4l-5.4,7
			c10.9,8.5,24.6,14.2,31.1,27c5.4,10.6,10.2,51.4-2.4,51.9l1.2,5.7l-3.9-4.7l1.1,8.3l-4.5-5.3l0.7,6.2l-6.1-4.5v3.6
			c-3.4-3.4-8.2-6.2-11.6-9.8c4.1,7.5,7.6,15.2,10.6,23.2l-4.5-5c1.6,10,0.8,16-5.9,21.5c8.9-5.8,15.9-3.9,22.9,4.5
			c-0.9-1.1-3.9,3.2-5.2,4c-4.2-8.4-11.7-9.6-19.9-6.9c9.4,7.4,22.3,9.1,27.2,20.5c-8.1-3.6-13-9.9-21.9-12.9
			c5.4,4.8,9.4,10.3,15.3,14.6c-7.4-0.5-13.4-4.3-18-10.2c6.1,6.4,12,12.9,12.4,21.9c-8.6-2.8-12.2-10.5-15.1-19l-2.8,5.4
			c-4.9-1.1-9.9-0.2-16.6,0.2c-4.6,0.3-4.1,2.5-12.7,1.4C108.8,203.5,111.1,204.7,102.6,201z"
                    />
                    <path
                      className="st0"
                      fill="#343736"
                      d="M132.7,101.8c0,0,6.6-3.8,9.6-3.6s9.6-0.2,12,0.8s5.2,2.4,6.6,2.9s11,1.4,11,1.4l-7.3,1.4l4.7,2.3l-6.8,1.1
			l-4.4-1.3c0,0-1.8,0.7-5.2,2.3s-8.8,0.7-11.6,0c-2.9-0.5-5.8-0.8-8.7-0.9c0,0,3.1-2.8,3.3-3.5S132.7,101.8,132.7,101.8z"
                    />
                    <path
                      className="st0"
                      fill="#343736"
                      d="M130.6,179l-8.5,1.1c0,0-1.3-0.2-3.2-0.4c0.5-2.1,0.9-4.3,1-6.6c-0.1-2.6-0.1-11-2.2-13.5s-6-4.9-7.5-6
			s-2.2-4-2.2-4s-8,3.1-9.9,4.7s-6.4,8.2-7.4,10.3s-1.9,7.7-1.5,8.7c0.2,2,0.2,3.9,0,5.9c-1.8,0.5-6.6,2.1-7.4,5
			c-1.1,3.6,3.4,13.1,10.2,17.3c4.5,2.7,9.5,4.7,14.6,6c0,0,10.1,0.2,10.1-1.4s5.5-7.2,5.3-11.4s-2.2-10.5-2.2-10.5
			s-7.1,3.4-11.1,2.7c-3.8-0.7-15.9-2.2-14-3.4c4.8,0.4,13.1,1,19.5,0.3c6.5-0.6,13-0.6,19.4,0c3.4,0.2,15.3-1.3,15.3-1.3L130.6,179
			z"
                    />
                  </g>
                </g>
              </svg>
            </span>
          ),
          label: 'Snow Monkey',
          value: 'SnowMonkey',
        });
        return { options };
      });
  };

  gotoService = (e) => {
    if (e) {
      window.location.href = `/bo/dashboard/lines/${e.env}/services/${e.value}`;
    }
  };

  gotoTcpService = (e) => {
    if (e) {
      window.location.href = `/bo/dashboard/tcp/services/edit/${e.value}`;
    }
  };

  color(env) {
    if (env === 'prod') {
      return 'bg__success';
    } else if (env === 'preprod') {
      return 'bg__primary';
    } else if (env === 'experiments') {
      return 'bg__warning';
    } else if (env === 'dev') {
      return 'bg__info';
    } else {
      return 'bg__dark';
    }
  }

  listenToSlash = (e) => {
    const hasClassNameAndNotAceInput = e.target.className
      ? e.target.className.indexOf('ace_text-input') === -1
      : true;
    if (
      e.keyCode === 191 &&
      e.target.tagName.toLowerCase() !== 'input' &&
      hasClassNameAndNotAceInput
    ) {
      setTimeout(() => this.selector.focus());
    }
  };

  componentDidMount() {
    if (!this.mounted) {
      this.mounted = true;
      document.addEventListener('keydown', this.listenToSlash, false);
    }
    BackOfficeServices.env().then((env) => this.setState({ env }));
  }

  componentWillUnmount() {
    if (this.mounted) {
      this.mounted = false;
      document.removeEventListener('keydown', this.listenToSlash);
    }
  }

  render() {
    const selected = (this.props.params || {}).lineId;
    const isActive = this.state.isActive;
    return (
      <nav className={isActive ? "active" : "inactive"}>
              <a className="brand text__white--important" href="/bo/dashboard">
                <span>おとろし</span> &nbsp; {window.__title || 'Otoroshi'}
              </a>
              <button
                id="btn_sidebar"
                type="button"
                className="btn-black"
                data-toggle="collapse"
                data-target="#sidebar"
                aria-expanded="false"
                aria-controls="sidebar"
                onClick={this.handleToggle}>
                <span className="sr-only">Toggle sidebar</span>
                <span>Menu</span>
              </button>
            
            <form id="navbar" className="grow-1">
              {selected && (
                <div style={{ marginRight: 10 }}>
                  <span
                    title="Current line"
                    className="label bg__success"
                    style={{ fontSize: 20, cursor: 'pointer' }}>
                    {selected}
                  </span>
                </div>
              )}
              <div style={{ marginLeft: 10, marginRight: 10 }}>
                <Async
                  ref={(r) => (this.selector = r)}
                  name="service-search"
                  value="one"
                  placeholder="Search service, line, etc ..."
                  loadOptions={this.searchServicesOptions}
                  openOnFocus={true}
                  onChange={(i) => i.action()}
                  arrowRenderer={(a) => {
                    return (
                      <span
                        style={{ display: 'flex', height: 20 }}
                        title="You can jump directly into the search bar from anywhere just by typing '/'">
                        <svg xmlns="http://www.w3.org/2000/svg" width="19" height="20">
                          <defs>
                            <rect id="a" width="19" height="20" rx="3" />
                          </defs>
                          <g fill="none" fillRule="evenodd">
                            <rect stroke="#5F6165" x=".5" y=".5" width="18" height="19" rx="3" />
                            <path fill="#979A9C" d="M11.76 5.979l-3.8 9.079h-.91l3.78-9.08z" />
                          </g>
                        </svg>
                      </span>
                    );
                  }}
                  filterOptions={(opts, value, excluded, conf) => {
                    const [env, searched] = extractEnv(value);
                    const filteredOpts = !!env ? opts.filter((i) => i.env === env) : opts;
                    const matched = fuzzy.filter(searched, filteredOpts, {
                      extract: (i) => i.label,
                      pre: '<',
                      post: '>',
                    });
                    return matched.map((i) => i.original);
                  }}
                  optionRenderer={(p) => {
                    const env =
                      p.env && _.isString(p.env)
                        ? p.env.length > 4
                          ? p.env.substring(0, 4) + '.'
                          : p.env
                        : null;
                    return (
                      <div style={{ display: 'flex' }}>
                        <div
                          style={{
                            width: 60,
                            display: 'flex',
                            justifyContent: 'center',
                            alignItems: 'center',
                          }}>
                          {p.env && _.isString(p.env) && (
                            <span className={`label ${this.color(p.env)}`}>{env}</span>
                          )}
                          {p.env && !_.isString(p.env) && p.env}
                        </div>
                        <span>{p.label}</span>
                      </div>
                    );
                  }}
                  style={{ width: 400 }}
                />
              </div>
            </form>
            <div className="p-fixed flex align-items__center" style={{right:0, top:0}}>
              {window.__apiReadOnly && (
                  <a className="text__alert mr-10" title="Admin API in read-only mode">
                    <span className="fas fa-lock fa-lg" />
                  </a>
              )}
              {this.props.changePassword && (
                  <a
                    href="/bo/dashboard/admins"
                    onClick={(e) => (window.location = '/bo/dashboard/admins')}
                    data-toggle="dropdown"
                    role="button"
                    aria-haspopup="true"
                    aria-expanded="false">
                    <span
                      className="badge bg__alert mr-10"
                      data-toggle="tooltip"
                      data-placement="bottom"
                      title="You are using the default admin account. You should create a new admin account quickly and delete the default one.">
                      <i className="fas fa-exclamation-triangle" />
                    </span>
                  </a>
              )}
              <div className="dropdown">
                <i className="fas fa-cog" aria-hidden="true" />
                <div className="dropdown-content">
                <ul className="dropdown-menu">
                  {/*<li>
                    <a href="/bo/dashboard/users"><span className="fas fa-user" /> All users</a>
                  </li>*/}
                  <li>
                    <a href="#">
                      <img src="/assets/images/otoroshi-logo-inverse.png" width="16" /> version{' '}
                      {window.__currentVersion}
                    </a>
                  </li>
                  <li>
                    <a href="/docs/index.html" target="_blank">
                      <span className="fas fa-book" /> User manual
                    </a>
                  </li>
                  <li role="separator" className="divider" />
                  <li>
                    {window.__otoroshi__env__latest.userAdmin && (
                      <a href="/bo/dashboard/organizations">
                        <span className="fas fa-folder-open" /> Organizations
                      </a>
                    )}
                    {window.__user.tenantAdmin && (
                      <a href="/bo/dashboard/teams">
                        <span className="fas fa-folder-open" /> Teams
                      </a>
                    )}
                    <a href="/bo/dashboard/groups">
                      <span className="fas fa-folder-open" /> Service groups
                    </a>

                    {window.__otoroshi__env__latest.userAdmin && (
                      <a href="/bo/dashboard/clever">
                        <span className="fas fa-list-alt" /> Clever apps
                      </a>
                    )}
                  </li>
                  <li role="separator" className="divider" />
                  <li>
                    <a href="/bo/dashboard/jwt-verifiers">
                      <span className="fas fa-key" /> Jwt Verifiers
                    </a>
                    <a href="/bo/dashboard/auth-configs">
                      <span className="fas fa-lock" /> Authentication configs
                    </a>
                    <a href="/bo/dashboard/certificates">
                      <span className="fas fa-certificate" /> SSL/TLS Certificates
                    </a>
                    <a className="hide" href="/bo/dashboard/validation-authorities">
                      <span className="fas fa-gavel" /> Validation authorities
                    </a>
                    {this.state.env.scriptingEnabled === true && (
                      <a href="/bo/dashboard/plugins">
                        <span className="fas fa-book-dead" /> Plugins
                      </a>
                    )}
                  </li>
                  <li role="separator" className="divider" />
                  {window.__otoroshi__env__latest.userAdmin &&
                    this.state.env.clusterRole === 'Leader' && (
                      <li>
                        <a href="/bo/dashboard/cluster">
                          <span className="fas fa-network-wired" /> Cluster view
                        </a>
                      </li>
                    )}
                  {window.__otoroshi__env__latest.userAdmin &&
                    this.state.env.clusterRole === 'Leader' && (
                      <li role="separator" className="divider" />
                    )}
                  {(window.__otoroshi__env__latest.userAdmin || window.__user.tenantAdmin) && (
                    <>
                      {window.__otoroshi__env__latest.userAdmin && (
                        <>
                          <li>
                            <a href="/bo/dashboard/stats">
                              <i className="fas fa-signal" /> Analytics
                            </a>
                          </li>
                          <li>
                            <a href="/bo/dashboard/status">
                              <i className="fas fa-heart" /> Status
                            </a>
                          </li>
                          <li>
                            <a href="/bo/dashboard/events">
                              <i className="fas fa-list" /> Events log
                            </a>
                          </li>
                          <li className="hide">
                            <a href="/bo/dashboard/top10">
                              <span className="fas fa-fire" /> Top 10 services
                            </a>
                          </li>
                          <li className="hide">
                            <a href="/bo/dashboard/map">
                              <span className="fas fa-globe" /> Services map
                            </a>
                          </li>
                          <li role="separator" className="divider hide" />
                          <li className="hide">
                            <a href="/bo/dashboard/loggers">
                              <span className="fas fa-book" /> Loggers level
                            </a>
                          </li>
                          <li>
                            <a href="/bo/dashboard/audit">
                              <span className="fas fa-list" /> Audit log
                            </a>
                          </li>
                          <li>
                            <a href="/bo/dashboard/alerts">
                              <span className="fas fa-list" /> Alerts log
                            </a>
                          </li>
                        </>
                      )}
                      <li>
                        <a href="/bo/dashboard/exporters">
                          <span className="fas fa-paper-plane" /> Exporters
                        </a>
                      </li>
                      <li role="separator" className="divider" />
                    </>
                  )}
                  {window.__user.tenantAdmin && (
                    <>
                      <li>
                        <a href="/bo/dashboard/admins">
                          <span className="fas fa-user" /> Admins
                        </a>
                      </li>
                      <li>
                        <a href="/bo/dashboard/sessions/admin">
                          <span className="fas fa-user" /> Admins sessions
                        </a>
                      </li>
                      <li>
                        <a href="/bo/dashboard/sessions/private">
                          <span className="fas fa-lock" /> Priv. apps sessions
                        </a>
                      </li>
                    </>
                  )}
                  {window.__otoroshi__env__latest.userAdmin && (
                    <>
                      <li role="separator" className="divider" />
                      <li>
                        <a href="/bo/dashboard/snowmonkey">
                          <svg
                            xmlns="http://www.w3.org/2000/svg"
                            className="monkeyMenu"
                            viewBox="0 0 244.1 244.1"
                            style={{ width: 16, marginLeft: 0 }}>
                            <title>nihonzaru</title>
                            <g id="_x34_9a67235-e3a1-4929-8868-b30847745300">
                              <g id="b11c2c3a-c434-45dc-a441-e60dd5d9d3f6">
                                <circle className="st0" fill="#343736" cx="122" cy="122" r="120" />
                                <polygon
                                  className="st1"
                                  points="73.8,56.4 56,85.6 52.7,123.5 71,159.6 69.8,187.7 85.7,206.2 122.1,216.9 157.1,204.9 182.5,179
                  196,116.3 218.9,76.5 152.4,40.7 130.6,35.1"
                                  fill="#FFFFFF"
                                />
                                <path
                                  className="st0"
                                  fill="#343736"
                                  d="M213.9,111.2c6.8-2.9,13.7-5.7,20.5-8.5c-2.4-1-3.4-2-6.7-2.4c2.7-1.9,4.5-1.8,7.3-2.9
                  c-5.1,0-15.2,0.4-21.3-2.8c3.8-0.8,7.5-2,11.1-3.6c-14.4-0.6-10.8,1.5-21.1-5.3c3.6-0.8,7.9,1.9,11.8,1.4c-1.9-1.3-4-2.3-6.2-3
                  c0.8-0.2,17.5-2.1,17.5-6c0-0.5-17.6-5.5-21.3-6.2c3.4-0.3,6.7-0.8,10-1.5c-5.5-0.8-11.1-1-16.6-0.6c8-5.1,16.9-9.1,24.5-14.2
                  c-8.4,0.5-16.3,3.9-24.7,5.7c3.6-5.4,22.6-12,22.6-17c0,0.2-22.1,11.3-24.6,12.7c4.4-5.2,9.3-8.7,14.2-13.3
                  c-9.3,2.5-15.7,7.4-25.6,8.4c4.8-3.9,11-5.1,16.1-8.6c-3,0.5-5.9,1.2-8.7,2.2c0.3-0.3,12.1-10.8,11.5-11.3
                  C200,30.7,186.9,36,183,37c3.3-2.9,6.4-6,9.2-9.4c-5.7,3.1-13.8,7.8-18.9,11.7c-0.7,1.1,14.3-26,7.2-26.8
                  c-2.8-0.3-12.5,12.7-15.3,14.8c0.4-1.9,3.6-13.2,2.6-13.4c-2.3-0.3-10.6,8.1-12.8,10.8c4.4-6.6,5.4-9,11.6-14.1
                  c-4.9,1.4-9.6,3.3-14,5.7l3.5-7.3c-1,1.6-1.6,1.4-1.8-0.9c-3-0.9-14.8,26.3-5.7,2.5c-2.5,3.2-4.9,6.5-7.1,9.9
                  c1.5-5.2,3.3-10.2,5.4-15.1c-0.9,1-1.5,0.6-1.8-1.2c-2.3-0.3-7,14.7-6.6,4.8c-2.9,4.6-11.9,17.5-12.4,6.8
                  c-6.4,17.3,0.5,0.5-3.4,6.5c0.6-3.8,0.8-7.7,0.4-11.6c-1.5,3.7-2.5,7.5-3,11.4c-1-2-1.7-4.1-2.1-6.3c-0.9,4.9-1,13.1-0.8,19.3
                  c-1.3-7.2-2.5-17.3-6-24.4c-0.2,6.3,0.7,12.6,2.7,18.6c-6.5-7.9-3.7-0.1-6.6-13.8c-1.5,3-2.4,6.2-2.7,9.5
                  c-0.7-0.8-2.5-6.2-4.8-5.2c-1.7,0.7-0.9,11.2-0.9,11.5c-3.2-7.6-6.4-15.1-9.6-22.7v27.8c-4.2-6.7-4.3-15.8-7.3-23.3
                  c0.3,6.4,1.2,12.8,2.6,19c-7-2.5-7-5-11.6-10.8c4.1,25.6,0.2-0.7-0.9,4.1c1.5,1.4,2.1,3.5,1.8,5.5c-7.1-5-12.9-12.6-16.8-20.3
                  c-0.2,0.2-0.5,0.3-0.8,0.2C56,18.2,66.8,26,68.6,34C68,32.9,58.4,18.7,57.5,18.8c-4.9,0.8,4.8,12.4,6,14.3
                  c-0.9-0.8-16.7-10.4-16.3-10.5c0.7-0.2,18.4,26.9,19.7,28.8c-3.3,0.8-10.3-8-13.6-9.4c2.4,3.6,5.4,6.8,8.9,9.4
                  c-11.5-1.7-20.2-10.1-31-14.1c6.1,5,13.8,7.9,19.7,12.9c-3.7,1.2-21-5.1-21-0.5c0,0.7,32.3,14.8,34.5,16.2
                  c-12.1-2.1-23.7-8.3-35.8-9.3c5,3.2,10,11.7,14.5,17.1c-7.3,0-13.6-3.7-21.3-2.7c5.2,2.2,10.9,3.4,16.5,3.4
                  c-7.9,1.8-17.7,2.6-24.4,4.8c3.4,0.7,6.8,1.6,10.1,2.9c-4.2-0.6-8.4-0.9-12.7-1c6.2,2.2,12.6,3.7,19.1,4.6
                  c-5.1-0.3-9,0.8-12.3,1.1l24.9,10.6c-5,4.1-24.7,1.8-29.2,2.7c1.2,0.7,0.4,1-2.6,0.9c5.7,1.5,6.4,2.7,11.4,5
                  c-5.2-0.3-11.1-0.2-16.1,1.4c7.8,2.1,15.4,1.7,22.8,3.6c-2,1.7-23.2,9.3-23.2,11.9c0,3,27.4,2.1,28.9,4.4c-6,6.3-13.8,9.7-24.6,14
                  c3.6,0.3,7.3,0.3,10.9-0.2c-5,6-11.7,13.6-15.5,12.4c-0.7,2.9,0.1,2.6-1.9,5.2c4.3-0.2,0.1-1,3.3,1.3c4.9-7.9,17-13.2,26.1-18.6
                  c-3.2,6.8-5,8.3-11.6,12.7c12.3-1.8-2,0.9,4.1,3c2.1,0.7,15.5-6.7,19.1-7.5c-9.5,7.9-13,17.3-23.2,25.1c11.4-3.4,2.4-1.7,2.3,4.4
                  c4-3,8.7-8.6,13.1-10.7c-1.9,2-18.7,39.1-16.8,39.9c2.4,0.9,19.6-20.2,23.1-22.8c-2.9,3.2-5.2,7-8,10.3c5.4-3.5,11.5-6.2,16.9-9.8
                  c-4.2,4.4-7.9,9.2-11,14.5c6.8-5,12.6-12.3,18-18.6c0,24.1-0.8,26.1-16.8,41.8c3.5-1.5,11.6-5,14.7-8.2c2-15.3-2.9,9.1-0.9,9.9
                  s15-6.8,17.3-7.9c-3.2,3.4-6.1,7.1-8.7,11c10.5-7.1,17.9-20.8,18.9-0.8c16.9-28.6,26.2,10.4,35.5,9.1c0.3-0.1,8.9-11.6,11.9-13.1
                  c7.8-3.6,9.2-0.4,20.6-4c9.4-3,9.6,13,12.6-6.3c11.1,8.1,16.2,4.4,18.4-8.8c0.4,0.4,9.7,8.8,10.1,8.7c5.5-2.2-2.3-18.6,4.7-8
                  c0.2-3.6-1.4-6.9-1.3-10.4c4.7,3.1,4.9,8.4,8.6,12.9c-0.3-3.4-1.1-6.7-2.5-9.9c2.4,1,4,4.1,5.7,5.9c-0.1-2,1.2-8.4-0.6-14
                  c5.4,2.5,8,7.4,13.3,11.9c-2.5-4.8-5.4-9.3-8.7-13.4c4.7-5.6,10.2-0.4,12.7,1.8c-5.4-8.7-9.1-14.4-9.4-24.8
                  c5.8,0.7,9.6-1,15.1,0.8c-2.9-6.4-8.6-9.8-12.3-16c2.8,1.7,5.7,3.2,8.8,4.4c-7.4-7.6-15.7-11.6-21.9-20
                  c5.3-1.7,13.2,11.2,16.9,9.9c4.2-1.5-11-10.9-11.5-11.9c4-2.2,14.2,4.3,18.6,6.5c-6.8-7.7-11.4-12.5-19.1-18.4
                  c3.3,2.2,7.1,3.6,11,4.1c-3.3-3.6-12.3-4.4-14.4-7.4c8.9-1.9,17.5-4.7,25.9-8.1c-9.3,1.2-18.4,3.2-27.3,6
                  C204.4,114.3,213.4,111.5,213.9,111.2z M225,101.3c1.7,0.4,3.2-0.6,4.6,1.1C230.3,102.5,222.1,102.2,225,101.3L225,101.3z
                  M222.3,100.1c3,3.1-4,1.3-6.3,1.3L222.3,100.1z M40.2,84.9c-3.9-0.7-8.2-1.4-12.2-2.1C31.5,82,38.1,84.4,40.2,84.9z M32.6,171.4
                  c-0.3,0.4-5.2,5.9-6.5,6.3c0.1-0.1,0-0.1-0.1,0C25.2,179.1,35.8,167.2,32.6,171.4z M154.1,18.4l4.8-2.6c-5.2,5.6-8.4,15.5-15.1,19
                  C145.3,29.2,148.9,21,154.1,18.4L154.1,18.4z M149,17.1c1.4-2.5,2.7-5.9,4.8-8c0.4,1.3-2.6,5.2-3.3,6.5l2.4-2.5
                  C147.3,20.1,149.4,17.6,149,17.1z M127.3,28.7c0.8-3.3,2.6-7.1,5.1-9.4C132.5,23.4,125.5,36.4,127.3,28.7z M176,113.7
                  c-1.8,2.6-4,4.9-6.6,6.7c10.3,3.5,8,22.1-4,19.2l5.2-7.5c-3.2,1.7-14.8,6-16.2,8.5c-4.5,8.4,3.3-1.7,0.2,10.5
                  c-2.7,10.6,3.4,21-9.7,27.2c19.1,10.6-37.4,31.8-53.4,12.4c-11.3-13.7,1.7-7.5,2-16.3c0.3-12.9-9.7-18.1,1.4-30.8
                  c-22.2,26.1-47.2-45.6-14-66.3h-6.7c3.4-3.5,6.9-8,10.8-10.9c-3.1,2.7-5.8,6.2-8.6,9.2c3.9-1.2,8.2-1.8,12-3.3l-3.7,3.3
                  c4.1-2.1,8.9-2.8,13.2-4.6c-2.6,3.5-4.1,8-6.9,11.3l18.3-12.2v7.2c3.7-2.7,9.4-2.7,13.2-5.3l2.3,7.1c1-3.9,3.2-5,3.3-10.7
                  c1.7,6.5,3.2,8,9.6,7.8c12.7-0.4,1.2-7.7,19.4,0.1C168.1,81.1,185,100.2,176,113.7L176,113.7z M200.7,92c-1.3,0.7-4.4-0.6-2-2
                  C200,89.2,203.1,90.6,200.7,92z"
                                />
                                <path
                                  className="st0"
                                  fill="#343736"
                                  d="M93.2,91.2c0,0,17.2,2.4,19.4,3.2s0,7.7,0,7.7s0.3-1.8-5.3-4S90.7,97.7,87.3,99l-8.5,3.2l-2.1-0.9l-7-3.1
                  l11.6,6.8H64.1l16.9,2.8l4.7-2.5c0,0,2.8,5.9,7.2,6.9s11.3,1.6,15.7,0c2.6-0.9,5.1-1.9,7.5-3.1c0,0,0,5.3-1.6,7.8
                  s-13.8,13.2-13.8,13.2l-10.6-11.3c0,0,7.2,1.6,11.9,0.3c2.8-0.7,5.5-1.7,8.2-2.8c0,0-12,0.6-16.7,0s-8.8-5.6-8.8-5.6l-1.9,1.9
                  l2.8,10l-5.9-2.1l10.3,15.3c0,0-8.8,0.6-12.5-5.6s-6.2-16-7.8-16.6s-1.9,8.4-1.9,8.4l9.7,15.4l8.2,4.1l6.9,2.4c0,0,0.9,1-2.5,6.5
                  s-7,9.6-4.4,15.5s-0.9,2.8,2.6-1.3s5.6-13.2,9.7-14.7s10-3.9,10-3.9s4.1-9.6,8.2-10.6s8.5-0.3,11.4,0s6.1,5.2,6.1,6.5
                  c0.4,2.1,0.9,4.2,1.6,6.3l10.2,6.6c0,0-12.4-19.1-11.8-21s11.8,1.6,11.8,1.6s-12.9-11.3-14.8-14.9s-4.8-8.9-3.1-10.5
                  c1.7-1.6,2.4-0.9,6.1,0.9s13.5,3.8,15.2,3.4s12.4-3.8,12.4-3.8s-12.5,1.6-15.8,1.2s-14.8-4.4-14.8-4.4s5.6-5.3,5.6-7.5
                  s1.2-9.7,6-8.8s15,3.7,19.1,6.2s12.5,3.7,12.9,1.6s-6-9.4-10-9.4s-14.4-4.7-18.6-4.7s-12.1,5.3-17.9,4.7s-14.6-3.1-19-2.8
                  s-14.1-4.4-19.7-1.9s-20.7,8.4-22.3,9.5s-8.8,8.7-6.9,9s11.3-1.3,11.3-1.3s-7.2-3.9-2.8-5.6S93.2,91.2,93.2,91.2z"
                                />
                                <path
                                  className="st0"
                                  fill="#343736"
                                  d="M110.9,147.6c0,0,0.6,1.9,3.6,2.2c1.6,0.1,3.2,0.6,4.7,1.3c0,0,2,3.3,2,4.9s0.8,15.7,0.8,15.7
                  s-1.9-14-2.2-15.2s-3.7-2.2-5.8-3.3S110.9,147.6,110.9,147.6z"
                                />
                                <path
                                  className="st0"
                                  fill="#343736"
                                  d="M134,147.6c0,0-0.6,1.9-3.6,2.2c-1.6,0.1-3.2,0.6-4.7,1.3c0,0-2,3.3-2,4.9s-0.8,15.7-0.8,15.7
                  s1.9-14,2.2-15.2s3.7-2.2,5.8-3.3S134,147.6,134,147.6z"
                                />
                                <path
                                  className="st0"
                                  fill="#343736"
                                  d="M113.6,97.5c0,0-3.4-3.8-7.1-5.2s-17.7,1.4-19.4,2.6s-5.6,4.9-4.2,6.7s-2.5,5.2-2.5,5.2s-7.5,0.8-8.2,3.3
                  s-1.6,4.4,0.9,10.5s-2.3,6.2-2.3,6.2s-3.8-3.6-7.2-12.2S57,104.5,62,100s13.8-8.8,18.6-9.7s18.8-0.3,19.9-0.6s11.1,1.2,13,2.3
                  s3.9,3.9,2.9,4.9C115.6,97.4,114.6,97.6,113.6,97.5z"
                                />
                                <path
                                  className="st0"
                                  fill="#343736"
                                  d="M72.2,93.4c0,0-4.3,15.8,1.4,6.5s9.8-11.6,9.8-11.6s13.9-4.2,14.6-1.4s-4.3-10.5-4.3-10.5l2.8,2.5l6.9-9.6
                  l4.3,9.4l8.3-10.3c0,0,36.8-3,37.1-6.8s-40.2-19.2-40.2-19.2L69.8,54.4L47.2,93.4l0.8,29.4l24.2,31.7l10.4,12.7
                  c0,0,12.4-14.1,13.6-18.6s-2-7.2-8.2-9.2s-17-9.8-19.3-22c-1.5-7,0.2-14.4,4.7-20L72.2,93.4z"
                                />
                                <path
                                  className="st1"
                                  fill="#FFFFFF"
                                  d="M11,149.6c14.4-11.7,28.1-36.6,48.4-35.1l-3.4,2.7l3.4-0.7l-2.2,3.1l4.3-0.4c-1.7,2.1-2.8,4.5-3.2,7.2
                  c1.9,1.2,4.4,0.8,5.8-1l-4.7,9.8l4.7-3.2l-1.7,7.9c0.9-1.6,2-2.2,3.5-1.8c-3.2,6.1,4.2,8.4,3.8,9.6c-0.3-2.4,0.6-4.7,2.5-6.2
                  c-0.3,2.9-1.1,5.7-2.5,8.2l4.9-2.9l-2.4,5.3l3.8-2.3l-1.2,4.1l3.3-4.1v5c3.2-1.5,6.7-3.8,9.9-5l-6.1,6.8c3.2-2.5,7-4.4,10.3-6.8
                  c-3.7,4.3-5.9,9.6-6.4,15.3c-0.2,4.3,3.5,7.9,3.3,11.9c-0.2,4.3-8.6,9.1-8.6,10.4c-0.2,14.8,36.9,21.5,10.1,33.5
                  c1.6-3.9,2.2-8.3,3.9-12.1c-2.7,2.7-5.3,5.4-7.8,8.3c1.2-1.6-2.8-9.3,1.2-11c-6.8,3-13.4,4.6-19.5,9.9c3-5.8,4.3-11.1,8.2-16.4
                  c-11.8,4-18.6,14-20.8,25.8c0.2-2.2-1.6-17.6,0.2-18.1c-2.1,0.6-9.1,10.7-10.4,12.8c3-6.6,6.6-19.4,12.3-24.2
                  c-3.1,2.6-6.4,5.4-9.2,8.3c3.8-7.3,6.8-14.9,9.2-22.8c-5.9,3.2-12.8,5.5-18.3,9.4c5.5-3.7,14.1-8.5,17.6-14.3
                  c1.1-1.8-21.6,14.4-26.1,18.6c2.2-3.6,3.9-7.6,6.1-11.1l-16.2,20.5c4.6-21,18.5-37.7,36.9-48.4c-18.6,1.4-24.6,20.5-45.5,21.4
                  c17.4-7.3,25.6-22.5,41.2-30.5c-7.3,1.8-13.9,6.1-18.5,12.1c2.9-9.4,11.2-15.3,20.5-17.3c-14.6-0.4-21.3,12.6-34.7,15
                  c11.1-8,19.1-20.5,31.8-25.7c-9.2,2.8-17.6,7.8-24.5,14.6c7.8-7.3,16.6-13.6,26.1-18.5C38.2,127.1,25.3,142.5,11,149.6z"
                                />
                                <path
                                  className="st1"
                                  fill="#FFFFFF"
                                  d="M102.6,201c5.2,5.5,8.2,12.7,8.3,20.3l2-10.1c3.5,4.6,8.5,9,11.3,14.1c-4.2-4.7-7.2-11-10.8-16.2
                  c4.3,3.8,6,3.2,10.8,10.6c1.9-3.7,5.1-7,6.6-10.8c-3.4,8.3-3.2,17.1,2,24.2l3.9-7.8l1.4,7.8l7.5-9.2c0.3,10.1,2.5,8.4,6.4,16
                  c-0.9-1.8-5.6-19.7-4-19.4c6.5,1.2,15,11.6,18.6,16.1c-2.6-5-4.5-10.4-7.3-15.2c1.3,2.1,8.6,13.8,11.1,13.8
                  c0.6,0-2.9-12.9-3.3-13.8c3.3,5.9,7.2,9.8,11.3,14.8c-2.7-5.8-4.4-12-7.3-17.6c2.5,4.6,6.2,8.5,8.9,12.9c-0.4-1.3-0.4-18.1,0-17.6
                  c4.9,5.5,9.6,11.3,14.3,16.9c-3.7-7.5-9.4-16.5-11-24.8c0.9,4.4,10,13.4,12.7,18.2c-1.8-4.8-2.5-10-4.2-14.8
                  c3.4,5.5,7.9,10.4,11.3,16c-3.4-12.3-7-24.3-8-37.1l12.3,23.5c-2.2-6.1-3.1-12.6-5-18.8l18.8,18.6l-13.8-20.9l4.6,2.1
                  c-8.8-11.6-17-27.8-31-33c18,1.5,28.3,20.3,36.7,34.4c-2.9-7.7-5.4-15.6-7.6-23.5c3.3,6.2,7.7,11.9,11.1,18.1
                  c-1.9-18.3-12.8-31.2-26.6-42.7c12.6,6.3,17.7,16.9,27.7,25.6c-5.3-9.9-9.3-20.1-18.3-27.3c9.7,8,19.6,15.4,27.8,25.1L215.5,150
                  c7.6,4.4,15.9,7.7,24.6,9.6c-14.4-17.5-35-28.5-53.7-40.8c18.7-2.8,33.2,10.8,47.6,20.6c-13.5-13.2-25-21.9-41.9-30.3
                  c14.4,0,28.6,1.7,41.9-4.7c-11.9,0-32.1,4.2-40.5-4.5c11.9,0.9,23.9,0.9,35.9,0L200.6,95l26.8,1.6l-41.9-9.8
                  c11.1-7.2,22.2-5.2,35-5.1c-12.4-1.1-24.8-1.6-36.9-4.6l33.9-3.5c-14.4,0-30.4,2.6-43.5-2.6c13.1-1.8,26.2-2.6,38.3-8.5l-33.2,4.9
                  c10.9-4,22.8-5.4,28.1-16.9l-18.6,10.8c3.9-3.6,7-8.1,10.8-11.8c-13.4,7-29.9,17.1-45.2,11.8c3.7-14.6,21.8-10.6,34.8-14.3
                  c-4.1-0.7-8.1-2.4-12.2-3l11.1-1.7l-4.5-2.1l20.2-10.3l-21.2,8l21.4-16.2L176,38.8l2.3-7.2c-5.5,1.1-10.8,3-15.7,5.6l5.4,4.2
                  c-9.6,2.5-16.1,4.2-17.4,9.6l-8.2-4c9.8-6.9,17.3-17.1,22.6-27.8l-16,17c2.1-4.3,3.3-9.1,5.4-13.4c-4.1,5.7-9.7,10.4-14.1,16
                  c1-6.4,0.9-13.2,2.4-19.6c-0.1,4.5-2.9,10-4,14.4c-7-2.7-12.9-7.8-16.5-14.4l2.4,10.4c-4.2-2.2-9.5-3-13.6-5.6
                  c4.2,3.5,7.1,8.5,11.1,12.2c-12-2.7-24.1-3.4-36.1,0c4.1,1.5,7.8,4.2,12,5.6c-4-0.1-8,0.9-12,0.7c4.5,1.1,8.7,4,13.2,5.4
                  c-6,2.1-11.7,5.9-17.9,7.5c6.1-0.3,12.2,1.1,18.3,0.7c-6.2,2.1-12.1,5.1-18.3,7c6.7-1.2,13.5-2.1,20.2-3.5
                  c-10.7,2.5-19.7,2.2-25,10.5c4.9-2,10.9-2.8,15.6-5.3l-9.3,6c4.7-2.4,9.7-4.2,14.5-6.5c-3.2,2.4-6,5.3-9.1,7.8
                  c3.9-2.3,8.2-3.9,12.2-6.1c-3.3,3.6-6.1,7.7-9.6,11.1c5.7-6.2,12.2-12.4,20.2-14.3l-4.1,9.2c3-2.9,6.7-5.2,9.6-8.2l-5.6,9.2
                  c3.7-4.1,8.1-7.5,11.9-11.5c-1.9,3.8-3.9,7.7-6.1,11.3c2.8-4.6,3.5-11.6,5.3-16.7c-8.9-0.4-15.6-5-18-13.9
                  c3.9,3.6,8.8,6.2,12.7,9.9c-2.3-3.2-3.7-7.2-5.8-10.6c4.2,2.7,9.7,3.2,14,5.6l-2.1-8.5l5.6,5.4l5.6-7.7l-1.3,10.6l5.6-2.8
                  c-4.6,2.8-5,6.2-10.8,8.5c0.7,0.6,7.6,6.6,8,6.6c-2.7,0.5-5.4,1.3-8,2.3c8.5,6.6,16.1,0.9,23.3-5.4l-5.4,7
                  c10.9,8.5,24.6,14.2,31.1,27c5.4,10.6,10.2,51.4-2.4,51.9l1.2,5.7l-3.9-4.7l1.1,8.3l-4.5-5.3l0.7,6.2l-6.1-4.5v3.6
                  c-3.4-3.4-8.2-6.2-11.6-9.8c4.1,7.5,7.6,15.2,10.6,23.2l-4.5-5c1.6,10,0.8,16-5.9,21.5c8.9-5.8,15.9-3.9,22.9,4.5
                  c-0.9-1.1-3.9,3.2-5.2,4c-4.2-8.4-11.7-9.6-19.9-6.9c9.4,7.4,22.3,9.1,27.2,20.5c-8.1-3.6-13-9.9-21.9-12.9
                  c5.4,4.8,9.4,10.3,15.3,14.6c-7.4-0.5-13.4-4.3-18-10.2c6.1,6.4,12,12.9,12.4,21.9c-8.6-2.8-12.2-10.5-15.1-19l-2.8,5.4
                  c-4.9-1.1-9.9-0.2-16.6,0.2c-4.6,0.3-4.1,2.5-12.7,1.4C108.8,203.5,111.1,204.7,102.6,201z"
                                />
                                <path
                                  className="st0"
                                  fill="#343736"
                                  d="M132.7,101.8c0,0,6.6-3.8,9.6-3.6s9.6-0.2,12,0.8s5.2,2.4,6.6,2.9s11,1.4,11,1.4l-7.3,1.4l4.7,2.3l-6.8,1.1
                  l-4.4-1.3c0,0-1.8,0.7-5.2,2.3s-8.8,0.7-11.6,0c-2.9-0.5-5.8-0.8-8.7-0.9c0,0,3.1-2.8,3.3-3.5S132.7,101.8,132.7,101.8z"
                                />
                                <path
                                  className="st0"
                                  fill="#343736"
                                  d="M130.6,179l-8.5,1.1c0,0-1.3-0.2-3.2-0.4c0.5-2.1,0.9-4.3,1-6.6c-0.1-2.6-0.1-11-2.2-13.5s-6-4.9-7.5-6
                  s-2.2-4-2.2-4s-8,3.1-9.9,4.7s-6.4,8.2-7.4,10.3s-1.9,7.7-1.5,8.7c0.2,2,0.2,3.9,0,5.9c-1.8,0.5-6.6,2.1-7.4,5
                  c-1.1,3.6,3.4,13.1,10.2,17.3c4.5,2.7,9.5,4.7,14.6,6c0,0,10.1,0.2,10.1-1.4s5.5-7.2,5.3-11.4s-2.2-10.5-2.2-10.5
                  s-7.1,3.4-11.1,2.7c-3.8-0.7-15.9-2.2-14-3.4c4.8,0.4,13.1,1,19.5,0.3c6.5-0.6,13-0.6,19.4,0c3.4,0.2,15.3-1.3,15.3-1.3L130.6,179
                  z"
                                />
                              </g>
                            </g>
                          </svg>{' '}
                          Snow Monkey
                        </a>
                      </li>
                    </>
                  )}
                  {window.__otoroshi__env__latest.userAdmin && this.state.env.providerDashboardUrl && (
                    <>
                      <li role="separator" className="divider" />
                      <li>
                        <a href="/bo/dashboard/provider">
                          <img src="/assets/images/otoroshi-logo-inverse.png" width="16" />{' '}
                          {this.state.env.providerDashboardTitle}
                        </a>
                      </li>
                    </>
                  )}
                  {window.__otoroshi__env__latest.userAdmin && (
                    <>
                      <li role="separator" className="divider" />
                      <li>
                        <a href="/bo/dashboard/dangerzone">
                          <span className="fas fa-exclamation-triangle" /> Danger Zone
                        </a>
                      </li>
                    </>
                  )}
                  <li role="separator" className="divider" />
                  <li>
                    <a href="/backoffice/auth0/logout" className="link-logout">
                      <span className="fas fa-power-off" />
                      <span className="topbar-userName"> {window.__userid} </span>
                    </a>
                  </li>
                </ul>
              </div>
          </div>
        </div>
      </nav>
    );
  }
}

// https://assets-cdn.github.com/images/search-shortcut-hint.svg
