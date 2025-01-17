/**
 * @license
 * Copyright (c) 2017 Google Inc. All rights reserved.
 * This code may only be used under the BSD style license found at
 * http://polymer.github.io/LICENSE.txt
 * Code distributed by Google as part of this project is also
 * subject to an additional IP rights grant found at
 * http://polymer.github.io/PATENTS.txt
 */

import {assert} from '../../platform/chai-web.js';
import {Allocator, AllocatorImpl} from '../allocator.js';
import {ArcHostImpl, SingletonArcHostFactory} from '../arc-host.js';
import {Runtime} from '../runtime.js';
import {Manifest} from '../manifest.js';

describe('Allocator', () => {
  it('starts arc with plan', async () => {
    const runtime = new Runtime();
    runtime.context = await runtime.parse(`
      import 'src/runtime/tests/artifacts/test-particles.manifest'
      recipe TestRecipe
        handle0: create
        handle1: create
        TestParticle
          foo: reads handle0
          bar: writes handle1
    `, {fileName: process.cwd() + '/input.manifest'});

    const allocator = new AllocatorImpl(runtime);
    const host = new ArcHostImpl('myhost', runtime);
    allocator.registerArcHost(new SingletonArcHostFactory(host));

    const arcId = await allocator.startArc({planName: 'TestRecipe', arcName: 'test'});
    const arc = host.getArcById(arcId);
    assert.equal(arc.id, arcId);
    assert.equal(arc.activeRecipe.name, 'TestRecipe');
  });

  // TODO(b/182410550): Add more tests. Currently Allocator functionality is tested
  // via other unit tests, that create Arcs and instantiate recipes using Runtime.
});
