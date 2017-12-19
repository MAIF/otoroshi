import React, { Component } from 'react';
import PropTypes from 'prop-types';
import * as BackOfficeServices from '../services/BackOfficeServices';

export class ServicesMapPage extends Component {
  componentDidMount() {
    this.props.setTitle(`Services Map`);
    BackOfficeServices.fetchServicesMap().then(data => {
      this.drawGraphAsZoomableCirclePacking(data);
    });
  }

  drawGraphAsZoomableCirclePacking = root => {
    const w = window.innerWidth - 230;
    const h = window.innerHeight - 190;
    const r = h - 30;
    const x = d3.scale.linear().range([0, r]);
    const y = d3.scale.linear().range([0, r]);
    let node = root;
    let zoomed = false;

    const pack = d3.layout
      .pack()
      .size([r, r])
      .value(d => d.size);

    const vis = d3
      .select(this.svg)
      .attr('width', w)
      .attr('height', h)
      .append('g')
      .attr('transform', `translate(${(w - r) / 2}, ${(h - r) / 2})`);

    const nodes = pack.nodes(root);

    function shortName({ name, r }) {
      const length = r / 3 - 3;
      if (name.length < length) {
        return name;
      } else {
        return name.substring(0, length) + '...';
      }
    }

    vis
      .selectAll('circle')
      .data(nodes)
      .enter()
      .append('circle')
      .attr('class', d => (d.children ? 'parent' : 'child'))
      .attr('cx', d => d.x)
      .attr('cy', d => d.y)
      .attr('r', d => d.r)
      .on('click', d => zoom(node == d ? root : d))
      .append('title')
      .text(d => d.name);

    vis
      .selectAll('text')
      .data(nodes)
      .enter()
      .append('text')
      .attr('class', d => (d.children ? 'parent' : 'child'))
      .attr('x', d => d.x)
      .attr('y', d => {
        if (d === root) {
          return d.y - d.r + 40;
        }
        return d.y;
      })
      .attr('style', d => {
        if (d === root) {
          return 'font-weight:bold;font-size:20px;color:white;';
        }
        return '';
      })
      .attr('dy', '.35em')
      .attr('text-anchor', 'middle')
      .text(d => (d.children ? shortName(d) : ''));

    d3.select(window).on('click', () => zoom(root));

    function zoom(d, i) {
      if (d === root) {
        zoomed = false;
      } else {
        zoomed = true;
      }
      let k = r / d.r / 2;
      x.domain([d.x - d.r, d.x + d.r]);
      y.domain([d.y - d.r, d.y + d.r]);

      let t = vis.transition().duration(d3.event.altKey ? 3500 : 750);

      t
        .selectAll('circle')
        .attr('cx', d => x(d.x))
        .attr('cy', d => y(d.y))
        .attr('r', d => k * d.r);

      t
        .selectAll('text')
        .attr('x', d => x(d.x))
        .attr('y', d => {
          if (d === root) {
            return y(d.y - d.r + 40);
          }
          return zoomed ? y(d.children ? d.y - d.r + 20 : d.y) : y(d.y);
        })
        .attr(
          'style',
          d =>
            (zoomed && d.children) || d === root
              ? 'font-weight:bold;font-size:20px;color:white;'
              : ''
        )
        .text(d => (zoomed ? d.name : d.children ? shortName(d) : ''))
        .style('opacity', d => (k * d.r > 20 ? 1 : 0));

      node = d;
      if (d3.event) {
        d3.event.stopPropagation();
      }
    }
  };

  render() {
    return (
      <div className="services-map">
        <svg ref={r => (this.svg = r)} />
      </div>
    );
  }
}
