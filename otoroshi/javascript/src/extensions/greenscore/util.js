export const MAX_GREEN_SCORE_NOTE = 6000;

export const GREEN_SCORE_GRADES = {
  '#2ecc71': (rank) => rank >= MAX_GREEN_SCORE_NOTE,
  '#27ae60': (rank) => rank < MAX_GREEN_SCORE_NOTE && rank >= 3000,
  '#f1c40f': (rank) => rank < 3000 && rank >= 2000,
  '#d35400': (rank) => rank < 2000 && rank >= 1000,
  '#c0392b': (rank) => rank < 1000,
};

export function getColorFromLetter(letter) {
  return Object.keys(GREEN_SCORE_GRADES)[letter.charCodeAt(0) - 65]
}

export function getLetter(score) {
  const rankIdx = Object.entries(GREEN_SCORE_GRADES).findIndex((grade) => grade[1](score));
  return String.fromCharCode(65 + rankIdx);
}

export function getColor(score) {
  const rankIdx = Object.entries(GREEN_SCORE_GRADES).findIndex((grade) => grade[1](score));
  return rankIdx === -1 ? 'Not evaluated' : Object.keys(GREEN_SCORE_GRADES)[rankIdx];
}