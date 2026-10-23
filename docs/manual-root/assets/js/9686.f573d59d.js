"use strict";(self.webpackChunkotoroshi_documentation=self.webpackChunkotoroshi_documentation||[]).push([["9686"],{77454(t,e,a){function r(t,e){t.accDescr&&e.setAccDescription?.(t.accDescr),t.accTitle&&e.setAccTitle?.(t.accTitle),t.title&&e.setDiagramTitle?.(t.title)}a.d(e,{S:()=>r}),(0,a(86827).K)(r,"populateCommonDb")},83509(t,e,a){a.d(e,{diagram:()=>m});var r=a(77454),i=a(5637),o=a(41916),l=a(34599),s=a(31293),n=a(86827),c=a(78731),d=l.UI.packet,h=class{constructor(){this.packet=[],this.setAccTitle=l.SV,this.getAccTitle=l.iN,this.setDiagramTitle=l.ke,this.getDiagramTitle=l.ab,this.getAccDescription=l.m7,this.setAccDescription=l.EI}static{(0,n.K)(this,"PacketDB")}getConfig(){let t=(0,o.$t)({...d,...(0,l.zj)().packet});return t.showBits&&(t.paddingY+=10),t}getPacket(){return this.packet}pushWord(t){t.length>0&&this.packet.push(t)}clear(){(0,l.IU)(),this.packet=[]}},k=(0,n.K)((t,e)=>{(0,r.S)(t,e);let a=-1,i=[],o=1,{bitsPerRow:l}=e.getConfig();for(let{start:r,end:n,bits:c,label:d}of t.blocks){if(void 0!==r&&void 0!==n&&n<r)throw Error(`Packet block ${r} - ${n} is invalid. End must be greater than start.`);if((r??=a+1)!==a+1)throw Error(`Packet block ${r} - ${n??r} is not contiguous. It should start from ${a+1}.`);if(0===c)throw Error(`Packet block ${r} is invalid. Cannot have a zero bit field.`);for(n??=r+(c??1)-1,c??=n-r+1,a=n,s.R.debug(`Packet block ${r} - ${a} with label ${d}`);i.length<=l+1&&e.getPacket().length<1e4;){let[t,a]=p({start:r,end:n,bits:c,label:d},o,l);if(i.push(t),t.end+1===o*l&&(e.pushWord(i),i=[],o++),!a)break;({start:r,end:n,bits:c,label:d}=a)}}e.pushWord(i)},"populate"),p=(0,n.K)((t,e,a)=>{if(void 0===t.start)throw Error("start should have been set during first phase");if(void 0===t.end)throw Error("end should have been set during first phase");if(t.start>t.end)throw Error(`Block start ${t.start} is greater than block end ${t.end}.`);if(t.end+1<=e*a)return[t,void 0];let r=e*a-1,i=e*a;return[{start:t.start,end:r,label:t.label,bits:r-t.start},{start:i,end:t.end,label:t.label,bits:t.end-i}]},"getNextFittingBlock"),b={parser:{yy:void 0},parse:(0,n.K)(async t=>{let e=await (0,c.qg)("packet",t),a=b.parser?.yy;if(!(a instanceof h))throw Error("parser.parser?.yy was not a PacketDB. This is due to a bug within Mermaid, please report this issue at https://github.com/mermaid-js/mermaid/issues.");s.R.debug(e),k(e,a)},"parse")},u=(0,n.K)((t,e,a,r)=>{let o=r.db,s=o.getConfig(),{rowHeight:n,paddingY:c,bitWidth:d,bitsPerRow:h}=s,k=o.getPacket(),p=o.getDiagramTitle(),b=n+c,u=b*(k.length+1)-(p?0:n),g=d*h+2,m=(0,i.D)(e);for(let[t,e]of(m.attr("viewBox",`0 0 ${g} ${u}`),(0,l.a$)(m,u,g,s.useMaxWidth),k.entries()))f(m,e,t,s);m.append("text").text(p).attr("x",g/2).attr("y",u-b/2).attr("dominant-baseline","middle").attr("text-anchor","middle").attr("class","packetTitle")},"draw"),f=(0,n.K)((t,e,a,{rowHeight:r,paddingX:i,paddingY:o,bitWidth:l,bitsPerRow:s,showBits:n})=>{let c=t.append("g"),d=a*(r+o)+o;for(let t of e){let e=t.start%s*l+1,a=(t.end-t.start+1)*l-i;if(c.append("rect").attr("x",e).attr("y",d).attr("width",a).attr("height",r).attr("class","packetBlock"),c.append("text").attr("x",e+a/2).attr("y",d+r/2).attr("class","packetLabel").attr("dominant-baseline","middle").attr("text-anchor","middle").text(t.label),!n)continue;let o=t.end===t.start,h=d-2;c.append("text").attr("x",e+(o?a/2:0)).attr("y",h).attr("class","packetByte start").attr("dominant-baseline","auto").attr("text-anchor",o?"middle":"start").text(t.start),o||c.append("text").attr("x",e+a).attr("y",h).attr("class","packetByte end").attr("dominant-baseline","auto").attr("text-anchor","end").text(t.end)}},"drawWord"),g={byteFontSize:"10px",startByteColor:"black",endByteColor:"black",labelColor:"black",labelFontSize:"12px",titleColor:"black",titleFontSize:"14px",blockStrokeColor:"black",blockStrokeWidth:"1",blockFillColor:"#efefef"},m={parser:b,get db(){return new h},renderer:{draw:u},styles:(0,n.K)(({packet:t}={})=>{let e=(0,o.$t)(g,t);return`
	.packetByte {
		font-size: ${e.byteFontSize};
	}
	.packetByte.start {
		fill: ${e.startByteColor};
	}
	.packetByte.end {
		fill: ${e.endByteColor};
	}
	.packetLabel {
		fill: ${e.labelColor};
		font-size: ${e.labelFontSize};
	}
	.packetTitle {
		fill: ${e.titleColor};
		font-size: ${e.titleFontSize};
	}
	.packetBlock {
		stroke: ${e.blockStrokeColor};
		stroke-width: ${e.blockStrokeWidth};
		fill: ${e.blockFillColor};
	}
	`},"styles")}}}]);