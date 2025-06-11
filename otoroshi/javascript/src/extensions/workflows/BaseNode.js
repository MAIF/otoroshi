import React, { forwardRef, } from "react";

export const BaseNode = forwardRef(({ className, selected, ...props }, ref) => (
    <div
        ref={ref}
        className={"relative rounded-md border bg-card p-5 text-card-foreground " + className +
            (selected ? "border-muted-foreground shadow-lg" : "") + "hover:ring-1"}
        tabIndex={0}
        {...props}
    />
));
