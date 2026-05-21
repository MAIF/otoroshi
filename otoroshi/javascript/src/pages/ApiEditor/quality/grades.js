// Maps a 0-100 quality percentage to a letter grade + color.
// The color palette is intentionally aligned with the GreenScore feature
// (see extensions/greenscore/util.js) so score visualizations stay consistent
// across the product.
export const QUALITY_GRADES = [
  { grade: 'A', min: 90, color: '#2ecc71' },
  { grade: 'B', min: 75, color: '#27ae60' },
  { grade: 'C', min: 60, color: '#f1c40f' },
  { grade: 'D', min: 40, color: '#d35400' },
  { grade: 'F', min: 0, color: '#c0392b' },
];

// Returns { grade, color } for a given percentage (0-100).
export function gradeFor(percent) {
  const found =
    QUALITY_GRADES.find((g) => percent >= g.min) || QUALITY_GRADES[QUALITY_GRADES.length - 1];
  return { grade: found.grade, color: found.color };
}
