import React, { forwardRef } from "react"
import { Panel } from "@xyflow/react";
import { BaseNode } from "./BaseNode";

export const GroupNodeLabel = forwardRef(
    ({ children, className, ...props }, ref) => {
        return (
            <div ref={ref} className="h-full w-full" {...props}>
                <div
                    className={"w-fit bg-gray-200 bg-secondary p-2 text-xs text-card-foreground " + className}>
                    {children}
                </div>
            </div>
        );
    },
);


export const GroupNode = forwardRef(
    ({ selected, label, position, ...props }, ref) => {
        const getLabelClassName = (position) => {
            switch (position) {
                case "top-left":
                    return "rounded-br-sm";
                case "top-center":
                    return "rounded-b-sm";
                case "top-right":
                    return "rounded-bl-sm";
                case "bottom-left":
                    return "rounded-tr-sm";
                case "bottom-right":
                    return "rounded-tl-sm";
                case "bottom-center":
                    return "rounded-t-sm";
                default:
                    return "rounded-br-sm";
            }
        }

        return (
            <BaseNode
                ref={ref}
                selected={selected}
                className="h-full overflow-hidden rounded-sm bg-white bg-opacity-50 p-0"
                {...props}
            >
                <Panel className={"m-0 p-0"} position={position}>
                    {props.data.label && (
                        <GroupNodeLabel className={getLabelClassName(position)}>
                            {props.data.label}
                        </GroupNodeLabel>
                    )}
                </Panel>
            </BaseNode>
        );
    },
)
