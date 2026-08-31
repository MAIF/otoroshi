"use strict";(self.webpackChunkotoroshi_documentation=self.webpackChunkotoroshi_documentation||[]).push([["8008"],{25871(t,e,a){function r(t,e){t.accDescr&&e.setAccDescription?.(t.accDescr),t.accTitle&&e.setAccTitle?.(t.accTitle),t.title&&e.setDiagramTitle?.(t.title)}a.d(e,{S:()=>r}),(0,a(40797).K2)(r,"populateCommonDb")},35211(t,e,a){a.d(e,{diagram:()=>g});var r=a(38342),i=a(25871),o=a(28718),l=a(67994),s=a(40797),n=a(78731),c=l.UI.packet,d=class{constructor(){this.packet=[],this.setAccTitle=l.SV,this.getAccTitle=l.iN,this.setDiagramTitle=l.ke,this.getDiagramTitle=l.ab,this.getAccDescription=l.m7,this.setAccDescription=l.EI}static{(0,s.K2)(this,"PacketDB")}getConfig(){let t=(0,o.$t)({...c,...(0,l.zj)().packet});return t.showBits&&(t.paddingY+=10),t}getPacket(){return this.packet}pushWord(t){t.length>0&&this.packet.push(t)}clear(){(0,l.IU)(),this.packet=[]}},h=(0,s.K2)((t,e)=>{(0,i.S)(t,e);let a=-1,r=[],o=1,{bitsPerRow:l}=e.getConfig();for(let{start:i,end:n,bits:c,label:d}of t.blocks){if(void 0!==i&&void 0!==n&&n<i)throw Error(`Packet block ${i} - ${n} is invalid. End must be greater than start.`);if((i??=a+1)!==a+1)throw Error(`Packet block ${i} - ${n??i} is not contiguous. It should start from ${a+1}.`);if(0===c)throw Error(`Packet block ${i} is invalid. Cannot have a zero bit field.`);for(n??=i+(c??1)-1,c??=n-i+1,a=n,s.Rm.debug(`Packet block ${i} - ${a} with label ${d}`);r.length<=l+1&&e.getPacket().length<1e4;){let[t,a]=k({start:i,end:n,bits:c,label:d},o,l);if(r.push(t),t.end+1===o*l&&(e.pushWord(r),r=[],o++),!a)break;({start:i,end:n,bits:c,label:d}=a)}}e.pushWord(r)},"populate"),k=(0,s.K2)((t,e,a)=>{if(void 0===t.start)throw Error("start should have been set during first phase");if(void 0===t.end)throw Error("end should have been set during first phase");if(t.start>t.end)throw Error(`Block start ${t.start} is greater than block end ${t.end}.`);if(t.end+1<=e*a)return[t,void 0];let r=e*a-1,i=e*a;return[{start:t.start,end:r,label:t.label,bits:r-t.start},{start:i,end:t.end,label:t.label,bits:t.end-i}]},"getNextFittingBlock"),p={parser:{yy:void 0},parse:(0,s.K2)(async t=>{let e=await (0,n.qg)("packet",t),a=p.parser?.yy;if(!(a instanceof d))throw Error("parser.parser?.yy was not a PacketDB. This is due to a bug within Mermaid, please report this issue at https://github.com/mermaid-js/mermaid/issues.");s.Rm.debug(e),h(e,a)},"parse")},b=(0,s.K2)((t,e,a,i)=>{let o=i.db,s=o.getConfig(),{rowHeight:n,paddingY:c,bitWidth:d,bitsPerRow:h}=s,k=o.getPacket(),p=o.getDiagramTitle(),b=n+c,f=b*(k.length+1)-(p?0:n),g=d*h+2,m=(0,r.D)(e);for(let[t,e]of(m.attr("viewBox",`0 0 ${g} ${f}`),(0,l.a$)(m,f,g,s.useMaxWidth),k.entries()))u(m,e,t,s);m.append("text").text(p).attr("x",g/2).attr("y",f-b/2).attr("dominant-baseline","middle").attr("text-anchor","middle").attr("class","packetTitle")},"draw"),u=(0,s.K2)((t,e,a,{rowHeight:r,paddingX:i,paddingY:o,bitWidth:l,bitsPerRow:s,showBits:n})=>{let c=t.append("g"),d=a*(r+o)+o;for(let t of e){let e=t.start%s*l+1,a=(t.end-t.start+1)*l-i;if(c.append("rect").attr("x",e).attr("y",d).attr("width",a).attr("height",r).attr("class","packetBlock"),c.append("text").attr("x",e+a/2).attr("y",d+r/2).attr("class","packetLabel").attr("dominant-baseline","middle").attr("text-anchor","middle").text(t.label),!n)continue;let o=t.end===t.start,h=d-2;c.append("text").attr("x",e+(o?a/2:0)).attr("y",h).attr("class","packetByte start").attr("dominant-baseline","auto").attr("text-anchor",o?"middle":"start").text(t.start),o||c.append("text").attr("x",e+a).attr("y",h).attr("class","packetByte end").attr("dominant-baseline","auto").attr("text-anchor","end").text(t.end)}},"drawWord"),f={byteFontSize:"10px",startByteColor:"black",endByteColor:"black",labelColor:"black",labelFontSize:"12px",titleColor:"black",titleFontSize:"14px",blockStrokeColor:"black",blockStrokeWidth:"1",blockFillColor:"#efefef"},g={parser:p,get db(){return new d},renderer:{draw:b},styles:(0,s.K2)(({packet:t}={})=>{let e=(0,o.$t)(f,t);return`
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