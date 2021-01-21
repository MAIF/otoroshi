import React, { Component } from 'react';
import {Link} from 'react-router-dom';
import _ from 'lodash';
import classNames from 'classnames';
import { Popover } from 'antd';
import Pagination from 'react-paginate';
import { BooleanInput } from '../components/inputs';
import * as BackOfficeServices from '../services/BackOfficeServices';

const formatPercentage = (value, decimal = 2) => {
  return parseFloat(value).toFixed(decimal) + " %"
}

export class GlobalStatusPage extends Component {
  state = {
    status: null,
    loading: true,
    stopTheCountUnknownStatus: true,
    page: 0,
    count: 0,
    pageSize: 4
  };

  componentDidMount() {
    this.props.setTitle(`Global status`);
    this.update();
  }

  handlePageClick = (data) => {
    this.setState({page: data.selected}, () => this.update())
  };

  update = () => {
    this.setState({ loading: true });
    BackOfficeServices.fetchGlobalStatus(this.state.page, this.state.pageSize)
      .then(({status, count}) => {
        this.setState({ status, count, loading: false });
      });
  };

  render() {
    if (!window.__user.superAdmin) {
      return null;
    }

    return (
      <div className="global-status">
        {this.state.loading && (
          <div style={{ textAlign: 'center', width: '100%' }}>
            <h3>
              Loading ...{' '}
              <svg
                width="30px"
                height="30px"
                xmlns="http://www.w3.org/2000/svg"
                viewBox="0 0 100 100"
                preserveAspectRatio="xMidYMid"
                className="uil-ring">
                <rect x="0" y="0" width="100" height="100" fill="none" className="bk" />
                <defs>
                  <filter id="uil-ring-shadow" x="-100%" y="-100%" width="300%" height="300%">
                    <feOffset result="offOut" in="SourceGraphic" dx="0" dy="0" />
                    <feGaussianBlur result="blurOut" in="offOut" stdDeviation="0" />
                    <feBlend in="SourceGraphic" in2="blurOut" mode="normal" />
                  </filter>
                </defs>
                <path
                  fill="#b5b3b3"
                  d="M10,50c0,0,0,0.5,0.1,1.4c0,0.5,0.1,1,0.2,1.7c0,0.3,0.1,0.7,0.1,1.1c0.1,0.4,0.1,0.8,0.2,1.2c0.2,0.8,0.3,1.8,0.5,2.8 c0.3,1,0.6,2.1,0.9,3.2c0.3,1.1,0.9,2.3,1.4,3.5c0.5,1.2,1.2,2.4,1.8,3.7c0.3,0.6,0.8,1.2,1.2,1.9c0.4,0.6,0.8,1.3,1.3,1.9 c1,1.2,1.9,2.6,3.1,3.7c2.2,2.5,5,4.7,7.9,6.7c3,2,6.5,3.4,10.1,4.6c3.6,1.1,7.5,1.5,11.2,1.6c4-0.1,7.7-0.6,11.3-1.6 c3.6-1.2,7-2.6,10-4.6c3-2,5.8-4.2,7.9-6.7c1.2-1.2,2.1-2.5,3.1-3.7c0.5-0.6,0.9-1.3,1.3-1.9c0.4-0.6,0.8-1.3,1.2-1.9 c0.6-1.3,1.3-2.5,1.8-3.7c0.5-1.2,1-2.4,1.4-3.5c0.3-1.1,0.6-2.2,0.9-3.2c0.2-1,0.4-1.9,0.5-2.8c0.1-0.4,0.1-0.8,0.2-1.2 c0-0.4,0.1-0.7,0.1-1.1c0.1-0.7,0.1-1.2,0.2-1.7C90,50.5,90,50,90,50s0,0.5,0,1.4c0,0.5,0,1,0,1.7c0,0.3,0,0.7,0,1.1 c0,0.4-0.1,0.8-0.1,1.2c-0.1,0.9-0.2,1.8-0.4,2.8c-0.2,1-0.5,2.1-0.7,3.3c-0.3,1.2-0.8,2.4-1.2,3.7c-0.2,0.7-0.5,1.3-0.8,1.9 c-0.3,0.7-0.6,1.3-0.9,2c-0.3,0.7-0.7,1.3-1.1,2c-0.4,0.7-0.7,1.4-1.2,2c-1,1.3-1.9,2.7-3.1,4c-2.2,2.7-5,5-8.1,7.1 c-0.8,0.5-1.6,1-2.4,1.5c-0.8,0.5-1.7,0.9-2.6,1.3L66,87.7l-1.4,0.5c-0.9,0.3-1.8,0.7-2.8,1c-3.8,1.1-7.9,1.7-11.8,1.8L47,90.8 c-1,0-2-0.2-3-0.3l-1.5-0.2l-0.7-0.1L41.1,90c-1-0.3-1.9-0.5-2.9-0.7c-0.9-0.3-1.9-0.7-2.8-1L34,87.7l-1.3-0.6 c-0.9-0.4-1.8-0.8-2.6-1.3c-0.8-0.5-1.6-1-2.4-1.5c-3.1-2.1-5.9-4.5-8.1-7.1c-1.2-1.2-2.1-2.7-3.1-4c-0.5-0.6-0.8-1.4-1.2-2 c-0.4-0.7-0.8-1.3-1.1-2c-0.3-0.7-0.6-1.3-0.9-2c-0.3-0.7-0.6-1.3-0.8-1.9c-0.4-1.3-0.9-2.5-1.2-3.7c-0.3-1.2-0.5-2.3-0.7-3.3 c-0.2-1-0.3-2-0.4-2.8c-0.1-0.4-0.1-0.8-0.1-1.2c0-0.4,0-0.7,0-1.1c0-0.7,0-1.2,0-1.7C10,50.5,10,50,10,50z"
                  filter="url(#uil-ring-shadow)"
                  transform="rotate(204 50 50)">
                  <animateTransform
                    attributeName="transform"
                    type="rotate"
                    from="0 50 50"
                    to="360 50 50"
                    repeatCount="indefinite"
                    dur="1s"
                  />
                </path>
              </svg>
            </h3>
          </div>
        )}
        {!this.state.loading && (
          <>
            <div className="row">
              <div className="col-sm-12">
                <BooleanInput
                  label="Don't use unknown status when calculating averages"
                  value={this.state.stopTheCountUnknownStatus}
                  help="Use unknown statuses when calculating averages could modify results and may not be representative"
                  onChange={(stopTheCountUnknownStatus) => this.setState({ stopTheCountUnknownStatus })}
                />
              </div>
            </div>
            <div className="content-health">
              {this.state.status
                .sort((s1, s2) => s1.service.localeCompare(s2.service))
                .map((health, idx) => {
                  return <Uptime key={idx} health={health} stopTheCountUnknownStatus={this.state.stopTheCountUnknownStatus} />
                })}
            </div>
            <Pagination
              previousLabel='previous'
              nextLabel='next'
              breakLabel='...'
              breakClassName={'break'}
              pageCount={Math.ceil(this.state.count / this.state.pageSize)}
              marginPagesDisplayed={1}
              pageRangeDisplayed={5}
              onPageChange={(data) => this.handlePageClick(data)}
              containerClassName={'pagination'}
              pageClassName={'page-selector'}
              forcePage={this.state.page}
              activeClassName={'active'}
            />
          </>
        )}
      </div>
    );
  }
}

class Uptime extends Component {
  render() {
    const test = this.props.health.dates
      .map(h => {
        const availability = h.status.length ? h.status
          .filter(s => s.health === "GREEN" || s.health === "YELLOW")
          .reduce((acc, curr) => acc + curr.percentage, 0).toFixed(2) : `unknown`
        return { date: h.dateAsString, availability, status: h.status }
      })

    const avg = this.props.health.dates
      .filter(d => !this.props.stopTheCountUnknownStatus || d.status.length)
      .reduce((avg, value, _, { length }) => {
        return avg + value.status
          .filter(s => s.health === "GREEN" || s.health === "YELLOW")
          .reduce((acc, curr) => acc + curr.percentage, 0) / length
      }, 0)
    return (
      <div className="health-container global">
        <div className="container--header" style={{
          display: 'flex',
          justifyContent: 'space-between'
        }}>
          <Link to={`/lines/${this.props.health.line}/services/${this.props.health.descriptor}/health`}>
            <h3>{this.props.health.service}</h3>
          </Link>
          <div className="uptime-avg">{formatPercentage(avg)}</div>
        </div>
        <div className="flex-status">
          {test.map((t, idx) => (
            <Popover
              key={idx}
              placement="bottom"
              title={t.date}
              content={!t.status.length ?
                <span>UNKNOWN</span> :
                <ul>{t.status.map((s, i) => <li key={i}>{`${s.health}: ${formatPercentage(s.percentage)}`}</li>)}</ul>}>
              <div key={idx} className={classNames('status', {
                green: t.availability !== 'unknown' && t.availability >= 100,
                'light-green': t.availability !== 'unknown' && t.availability >= 99 && t.availability < 100,
                orange: t.availability !== 'unknown' && t.availability >= 95 && t.availability < 99,
                red: t.availability !== 'unknown' && t.availability < 95,
                gray: t.availability === 'unknown'
              })}></div>
            </Popover>
          ))}
        </div>
      </div>
    )
  }
}
