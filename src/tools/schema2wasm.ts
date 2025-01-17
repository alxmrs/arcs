/**
 * @license
 * Copyright (c) 2019 Google Inc. All rights reserved.
 * This code may only be used under the BSD style license found at
 * http://polymer.github.io/LICENSE.txt
 * Code distributed by Google as part of this project is also
 * subject to an additional IP rights grant found at
 * http://polymer.github.io/PATENTS.txt
 */
import minimist from 'minimist';
import {Schema2Cpp} from './schema2cpp.js';
import {Schema2Kotlin} from './schema2kotlin.js';
import {Schema2Dot} from './schema2dot.js';

const opts = minimist(process.argv.slice(2), {
  boolean: ['cpp', 'kotlin', 'graph', 'update', 'wasm', 'test_harness', 'type_slicing', 'help'],
  string: ['outdir', 'outfile'],
  alias: {c: 'cpp', k: 'kotlin', g: 'graph', u: 'update', d: 'outdir', f: 'outfile'},
  default: {outdir: '.'}
});

if (opts.help || opts._.length === 0) {
  console.log(`
Usage
  $ tools/sigh schema2wasm [options] [file ...]

Description
  Generates entity class code from schemas for use in wasm particles.

Options
  --cpp, -c       generate C++ code
  --kotlin, -k    generate Kotlin code
  --graph, -g     generate a Graphviz (.dot) file for the type lattice
  --outdir, -d    output directory; defaults to '.'
  --outfile, -f   output filename; if omitted, generated from the manifest name
  --update, -u    only generate if the source file is newer than the destination
  --help          usage info
Kotlin-specific options
  --wasm          whether to output wasm-specific code
  --test_harness  whether to output particle test harnesses only
  --type_slicing  whether to enable type slicing interfaces
`);
  process.exit(0);
}
if (!opts.cpp && !opts.kotlin && !opts.graph) {
  console.error('No target specified (one or more of --cpp, --kotlin, --graph)');
  process.exit(1);
}
if (opts.outdir === '') {
  console.error('Output dir cannot be empty');
  process.exit(1);
}

async function main() {
  try {
    if (opts.cpp) {
      await new Schema2Cpp(opts).call();
    }
    if (opts.kotlin) {
      await new Schema2Kotlin(opts).call();
    }
    if (opts.graph) {
      await new Schema2Dot(opts).call();
    }
  } catch (e) {
    console.error(e);
    process.exit(1);
  }
}

void main();
