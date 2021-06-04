const fs = require('fs');

{
  console.log('Alerts: ')
  const content = fs.readFileSync('./otoroshi/app/events/alerts.scala').toString('utf8');
  const lines = content.split('\n');
  lines
    .filter(line => line.trim().startsWith('case class'))
    .map(line => {
      const clazz = line.replace('case class ', '');
      return clazz.split('\(')[0] || clazz
    })
    .map(line => console.log(' * ' + line))
  console.log('')
}

{
  console.log('Events: ')
  const content = fs.readFileSync('./otoroshi/app/events/analytics.scala').toString('utf8');
  const lines = content.split('\n');
  lines
    .filter(line => line.trim().startsWith('case class') && line.trim().indexOf('Event') > 0)
    .map(line => {
      const clazz = line.replace('case class ', '');
      return clazz.split('\(')[0] || clazz
    })
    .map(line => console.log(' * ' + line))
  console.log('')
}

{
  console.log('Audit: ')
  const content = fs.readFileSync('./otoroshi/app/events/audit.scala').toString('utf8');
  const lines = content.split('\n');
  lines
    .filter(line => line.trim().startsWith('case class'))
    .map(line => {
      const clazz = line.replace('case class ', '');
      return clazz.split('\(')[0] || clazz
    })
    .map(line => console.log(' * ' + line))
  console.log('')
}