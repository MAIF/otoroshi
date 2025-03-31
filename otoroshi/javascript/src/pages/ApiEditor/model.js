export const API_STATE = {
  STAGING: 'staging',
  PUBLISHED: 'published',
  DEPRECATED: 'deprecated',
  REMOVED: 'removed',
};

export const CONSUMER_KIND = {
  APIKEY: 'Apikey',
  MTLS: 'Mtls',
  KEYLESS: 'Keyless',
  OAUTH2: 'OAuth2',
  JWT: 'JWT',
};

export function generateHourlyData(day) {
  const statuses = ['GREEN', 'YELLOW'];
  const result = { dates: [] };

  const baseDate = new Date();
  baseDate.setDate(baseDate.getDate() + day); // Increment day for 3 consecutive days

  for (let hour = 0; hour < 24; hour++) {
    const date = new Date(baseDate);
    date.setHours(hour);

    result.dates.push({
      status: [
        {
          health: statuses[Math.floor(Math.random() * statuses.length)], // Randomly 'GREEN' or 'YELLOW'
          percentage: Math.floor(Math.random() * 3 + 98), // Random number between 0 and 100
        },
      ],
      dateAsString: date.toISOString(), // Full ISO date-time string
    });
  }

  return result;
}
