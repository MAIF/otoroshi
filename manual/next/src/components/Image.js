import React from "react";
import useBaseUrl from "@docusaurus/useBaseUrl";

export function Image({ src, alt = "", className = "", center, ...props }) {
  const imageUrl = useBaseUrl(src);
  const img = (
    <img
      src={imageUrl}
      alt={alt}
      className={className}
      loading="lazy"
      {...props}
    />
  );
  if (center) {
    return (
      <div className="center">{img}</div>
    )
  } else {
    return img;
  }
}