import React, { Component } from 'react';
// import _ from 'lodash';
import classNames from 'classnames';
import { Popover } from 'antd';

export const formatPercentage = (value, decimal = 2) => {
  return parseFloat(value).toFixed(decimal) + ' %';
};

export class Uptime extends Component {
  render() {
    if (!this.props.health) {
      return null;
    }
    const test = this.props.health.dates.map((h) => {
      const availability = h.status.length
        ? h.status
            .filter((s) => s.health === 'GREEN' || s.health === 'YELLOW')
            .reduce((acc, curr) => acc + curr.percentage, 0)
            .toFixed(2)
        : `unknown`;
      return { date: h.dateAsString, availability, status: h.status };
    });

    const avg = this.props.health.dates
      .filter((d) => !this.props.stopTheCountUnknownStatus || d.status.length)
      .reduce((avg, value, _, { length }) => {
        return (
          avg +
          value.status
            .filter((s) => s.health === 'GREEN' || s.health === 'YELLOW')
            .reduce((acc, curr) => acc + curr.percentage, 0) /
            length
        );
      }, 0);

    return (
      <div className={`health-container ${this.props.className}`}>
        <div className="container--header d-flex align-items-center justify-content-between">
          <span style={{ color: 'var(--text)' }}>
            {new Date(
              this.props.health.dates[this.props.health.dates.length - 1]?.dateAsString
            ).toLocaleDateString('fr-FR', {
              year: 'numeric',
              month: 'long',
              day: 'numeric',
            })}
          </span>
          <div className="uptime-avg">{formatPercentage(avg)}</div>
        </div>
        <div className="flex-status">
          {test.map((t, idx) => {
            const clazz = {
              green: t.availability !== 'unknown' && t.availability >= 100,
              'light-green':
                t.availability !== 'unknown' && t.availability >= 99 && t.availability < 100,
              orange: t.availability !== 'unknown' && t.availability >= 95 && t.availability < 99,
              red: t.availability !== 'unknown' && t.availability < 95,
              gray: t.availability === 'unknown',
            };

            return (
              <Popover
                key={idx}
                placement="bottom"
                title={t.date}
                content={
                  !t.status.length ? (
                    <span>UNKNOWN</span>
                  ) : (
                    <div className="d-flex flex-column">
                      {t.status.map((s, i) => (
                        <div className="status-info">
                          <div className={`dot ${s.health.toLowerCase()}`} />
                          <div className={`info`}>{`${formatPercentage(s.percentage)}`}</div>
                        </div>
                      ))}
                    </div>
                  )
                }
              >
                <div key={idx} className={classNames('status', clazz)}></div>
              </Popover>
            );
          })}
        </div>
      </div>
    );
  }
}

// export function SmoothUptime({ health, stopTheCountUnknownStatus, className }) {
//   const test = health.dates.map((h) => {
//     const availability = h.status.length
//       ? h.status
//         .filter((s) => s.health === 'GREEN' || s.health === 'YELLOW')
//         .reduce((acc, curr) => acc + curr.percentage, 0)
//         .toFixed(2)
//       : `unknown`;
//     return { date: h.dateAsString, availability, status: h.status };
//   });

//   const avg = health.dates
//     .filter((d) => !stopTheCountUnknownStatus || d.status.length)
//     .reduce((avg, value, _, { length }) => {
//       return (
//         avg +
//         value.status
//           .filter((s) => s.health === 'GREEN' || s.health === 'YELLOW')
//           .reduce((acc, curr) => acc + curr.percentage, 0) /
//         length
//       );
//     }, 0);

//   const getAvailability = (item) => {
//     return (item.availability !== 'unknown' && item.availability >= 100) ||
//       (item.availability !== 'unknown' && item.availability >= 99 && item.availability < 100) ? 'green' :
//       (item.availability !== 'unknown' && item.availability >= 95 && item.availability < 99) ? 'orange' :
//         (item.availability !== 'unknown' && item.availability < 95) ? 'red' : 'gray'
//   }

//   const items = test.reduce((acc, item) => {
//     const clazz = getAvailability(item)

//     if (acc.current) {
//       if (clazz === acc.current.clazz) {
//         return {
//           ...acc,
//           current: {
//             ...acc.current,
//             last_date: item.date,
//             length: acc.current.length + 1
//           }
//         }
//       } else {
//         return {
//           result: [...acc.result, acc.current],
//           current: {
//             starting_date: item.date,
//             clazz,
//             length: 2
//           }
//         }
//       }
//     } else {
//       return {
//         ...acc,
//         current: {
//           starting_date: item.date,
//           clazz,
//           length: 2
//         }
//       }
//     }
//   }, {
//     result: [],
//     current: undefined
//   })

//   const result = [...items.result, items.current]

//   console.log(result)

//   return (
//     <div className={`health-container ${className}`}>
//       <div
//         className="container--header"
//         style={{
//           display: 'flex',
//           justifyContent: 'flex-end',
//         }}
//       >
//         <div className="uptime-avg">{formatPercentage(avg)}</div>
//       </div>
//       <div className="flex-status">
//         {result.map((t, idx) => {
//           const clazz = {
//             green: t.clazz === 'green',
//             orange: t.clazz === 'orange',
//             red: t.clazz === 'red',
//             gray: t.clazz === 'gray',
//           };

//           return (
//             <Popover
//               key={idx}
//               placement="bottom"
//               title={t.date}
//               content={
//                 <div className="d-flex flex-column">

//                 </div>
//               }
//             >
//               <div key={idx} className={classNames('status', clazz)}
//                 style={{
//                   flex: t.length
//                 }}></div>
//             </Popover>
//           );
//         })}
//       </div>
//     </div>
//   );
// }
