const http = require('http')

const keep = ['name', 'mean', 'min', 'max', '--', 'p50', 'p999', 'count'];

const filter = [
  'ng-report-request-overhead-in', 
  'ng-report-request-step-call-backend', 
  'ng-report-request-overhead-out', 
  'ng-report-request-overhead', 
  '--', 
  'ng-report-request-step-start-handling',
  'ng-report-request-step-check-concurrent-requests',
  'ng-report-request-step-find-route',
  'ng-report-request-step-compute-plugins',
  'ng-report-request-step-tenant-check',
  'ng-report-request-step-extract-tracking-id',
  'ng-report-request-step-check-global-maintenance',
  'ng-report-request-step-call-before-request-callbacks',
  'ng-report-request-step-call-pre-route-plugins',
  'ng-report-request-step-call-access-validator-plugins',
  'ng-report-request-step-enforce-global-limits',
  'ng-report-request-step-choose-backend',
  'ng-report-request-step-transform-request',
  'ng-report-request-step-call-backend', 
  'ng-report-request-step-transform-response',
  'ng-report-request-step-stream-response',
  'ng-report-request-step-trigger-analytics',
  'ng-report-request-duration',
]

function fetchMetrics() {
  return new Promise((success, failure) => {
    let body = '';
    const options = {
      hostname: 'otoroshi-api.oto.tools',
      port: 9999,
      path: '/metrics?filter=ng-*',
      method: 'GET'
    }
    const req = http.request(options, res => {
      res.on('data', d => {
        body = body + d;
      })
      res.on('end', () => {
        success(JSON.parse(body))
      })
    })
    req.on('error', error => {
      failure(error)
    })
    req.end()
  });
}

fetchMetrics().then(body => {
  const pluginKeys = body.map(r => r.name.replace(" {}", "")).filter(r => r.indexOf("plugin-cp") > -1); 
  const array = body.map(r => {
    return ({...r, name: r.name.replace(' {}', '')})
  });
  const allFilters = [ ...filter, '--', ...pluginKeys ];
  const finalArray = (allFilters.length === 0) ? array : allFilters.map(name => array.filter(n => n.name === name)[0] || {})
  const table = finalArray.map(row => {
    const res = {};   
    keep.map(n => {
      let value = String(row[n] || '');
      if (n !== 'name' && n !== 'count' && value.length > 9) {
        value = value.substring(0, 8);
      }
      res[n] = value
    });
    return res;
  });
  console.table(table);
})

