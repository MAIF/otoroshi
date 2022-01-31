const http = require('http')

const keep = ['name', 'mean', 'min', 'max', '--', 'p50', 'p999', 'count'];
const filter = ['ng-report-request-step-call-backend', 'ng-report-request-overhead', 'ng-report-request-overhead-in', 'ng-report-request-overhead-out']

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
  const table = body.map(r => ({...r, name: r.name.replace(' {}', '')})).filter(r => filter.includes(r.name)).map(row => {
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

