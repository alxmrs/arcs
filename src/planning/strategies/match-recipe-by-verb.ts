/**
 * @license
 * Copyright (c) 2017 Google Inc. All rights reserved.
 * This code may only be used under the BSD style license found at
 * http://polymer.github.io/LICENSE.txt
 * Code distributed by Google as part of this project is also
 * subject to an additional IP rights grant found at
 * http://polymer.github.io/PATENTS.txt
 */

import {assert} from '../../platform/assert-web.js';
import {HandleConnectionSpec} from '../../runtime/arcs-types/particle-spec.js';
import {Recipe, Particle, Slot, Handle, effectiveTypeForHandle} from '../../runtime/recipe/lib-recipe.js';
import {StrategizerWalker, Strategy} from '../strategizer.js';
import {Dictionary, GenerateParams, Descendant, compareComparables} from '../../utils/lib-utils.js';
import {Direction} from '../../runtime/arcs-types/enums.js';
import {Type} from '../../types/lib-types.js';

// This strategy substitutes '&verb' declarations with recipes,
// according to the following conditions:
// 1) the recipe is named by the verb described in the particle
// 2) the recipe has the slot pattern (if any) owned by the particle
//
// The strategy also reconnects any slots that were connected to the
// particle, so that the substituted recipe fully takes the particle's place.
//
// Note that the recipe may have the slot pattern multiple times over, but
// this strategy currently only connects the first instance of the pattern up
// if there are multiple instances.
type HandleConstraint = {handle: Handle, direction: Direction};
type SlotConstraint = {targetSlot: Slot, providedSlots: Slot[]};

export class MatchRecipeByVerb extends Strategy {

  async generate(inputParams: GenerateParams<Recipe>): Promise<Descendant<Recipe>[]> {
    const arc = this.arc;
    return StrategizerWalker.over(this.getResults(inputParams), new class extends StrategizerWalker {
      onParticle(recipe: Recipe, particle: Particle) {
        if (particle.name) {
          // Particle already has explicit name.
          return undefined;
        }

        let recipes = arc.context.findRecipesByVerb(particle.primaryVerb);

        // Extract slot information from recipe.
        //
        // Note that slots are always included when listed in the recipe, even if they don't connect
        // to anything. Hence, slotConstraints can contain entries for which only the dictionary key
        // is relevant (the constraint itself may have null targetSlot and no providedSlots).
        //
        // This impacts recipe filtering because the presence of a slot on a verb in a recipe forces the
        // matched recipe to list that same slot. Empty constraints are ignored while connecting the
        // verb up to the rest of the recipe.
        const slotConstraints: Dictionary<SlotConstraint> = {};
        for (const consumeSlot of particle.getSlotConnections()) {
          const targetSlot = consumeSlot.targetSlot;
          const providedSlots = consumeSlot.getConnectedProvideSlots();
          slotConstraints[consumeSlot.name] = {targetSlot, providedSlots};
        }

        const handleConstraints = {named: {}, unnamed: []};
        for (const handleConnection of Object.values(particle.connections)) {
          handleConstraints.named[handleConnection.name] = {direction: handleConnection.direction, handle: handleConnection.handle};
        }
        for (const unnamedConnection of particle.unnamedConnections) {
          handleConstraints.unnamed.push({direction: unnamedConnection.direction, handle: unnamedConnection.handle});
        }

        recipes = recipes.filter(recipe => MatchRecipeByVerb.satisfiesSlotConstraints(recipe, slotConstraints))
                         .filter(recipe => MatchRecipeByVerb.satisfiesHandleConstraints(recipe, handleConstraints));

        return recipes.map(recipe => {
          return (outputRecipe: Recipe, particleForReplacing) => {
            const {particles} = recipe.mergeInto(outputRecipe);

            particleForReplacing.remove();


            for (const consumeSlot of Object.keys(slotConstraints)) {
              const constraints = slotConstraints[consumeSlot];
              let slotMapped = false;
              for (const particle of particles) {
                if (particle.spec.slotConnectionNames().includes(consumeSlot)) {
                  if (constraints.targetSlot) {
                    const {mappedSlot} = outputRecipe.updateToClone({mappedSlot: constraints.targetSlot});
                    // if slotConnection doesn't exist, then create it before connecting it to slot.
                    const consumeConn = particle.getSlotConnectionByName(consumeSlot) || particle.addSlotConnection(consumeSlot);
                    consumeConn.connectToSlot(mappedSlot);
                  }
                  for (const slot of constraints.providedSlots) {
                    const {mappedSlot} = outputRecipe.updateToClone({mappedSlot: slot});

                    const consumeConn = particle.getSlotConnectionByName(consumeSlot) || particle.addSlotConnection(consumeSlot);
                    consumeConn.disconnectProvidedSlot(slot.name);
                    consumeConn.connectProvidedSlot(slot.name, mappedSlot);
                  }
                  slotMapped = true;
                  break;
                }
              }
              assert(slotMapped);
            }

            function tryApplyHandleConstraint(
                name: string, connSpec: HandleConnectionSpec, particle: Particle, constraint: HandleConstraint, handle: Handle) {
              let connection = particle.connections[name];
              if (connection && connection.handle) {
                return false;
              }
              if (!MatchRecipeByVerb.connectionMatchesConstraint(connection || connSpec, constraint)) {
                return false;
              }
              connection = connection || particle.addConnectionName(connSpec.name);
              for (let i = 0; i < handle.connections.length; i++) {
                const candidate = handle.connections[i];
                // TODO candidate.name === name triggers test failures
                // tslint:disable-next-line: triple-equals
                if (candidate.particle === particleForReplacing && candidate.name == name) {
                  // TODO(shans): This is not cool! We're performing recipe surgery by replacing
                  // the connection's handle and the handle's reference to its connection. Why
                  // are we doing this? Try to figure out a more rational way to make it work.
                  connection['_handle'] = handle;
                  handle.connections[i] = connection;
                  return true;
                }
              }
              return false;
            }

            function applyHandleConstraint(name: string, constraint: HandleConstraint, handle: Handle) {
              const {mappedHandle} = outputRecipe.updateToClone({mappedHandle: handle});
              for (const particle of particles) {
                if (name) {
                  if (tryApplyHandleConstraint(
                    name, particle.spec.getConnectionByName(name), particle, constraint, mappedHandle)) {
                    return true;
                  }
                } else {
                  for (const connSpec of particle.spec.handleConnections) {
                    if (tryApplyHandleConstraint(name, connSpec, particle, constraint, mappedHandle)) {
                      return true;
                    }
                  }
                }
              }
              return false;
            }

            for (const name in handleConstraints.named) {
              if (handleConstraints.named[name].handle) {
                assert(applyHandleConstraint(
                    name,
                    handleConstraints.named[name],
                    handleConstraints.named[name].handle));
              }
            }

            for (const connection of handleConstraints.unnamed) {
              if (connection.handle) {
                assert(applyHandleConstraint(null, connection, connection.handle));
              }
            }

            return 1;
          };
        });
      }
    }(StrategizerWalker.Permuted), this);
  }

  static satisfiesHandleConstraints(recipe: Recipe, handleConstraints: {named: Dictionary<HandleConstraint>, unnamed: HandleConstraint[]}) {
    for (const handleName in handleConstraints.named) {
      if (!MatchRecipeByVerb.satisfiesHandleConnection(
              recipe, handleName, handleConstraints.named[handleName])) {
        return false;
      }
    }
    for (const handleConstraint of handleConstraints.unnamed) {
      if (!MatchRecipeByVerb.satisfiesUnnamedHandleConnection(
              recipe, handleConstraint)) {
        return false;
      }
    }
    return true;
  }

  static satisfiesUnnamedHandleConnection(recipe: Recipe, handleConstraint: HandleConstraint) {
    // refuse to match unnamed handle connections unless some type information is present.
    if (!handleConstraint.handle) {
      return false;
    }
    for (const particle of recipe.particles) {
      for (const connection of Object.values(particle.connections)) {
        if (MatchRecipeByVerb.connectionMatchesConstraint(
                connection, handleConstraint)) {
          return true;
        }
      }
      if (particle.spec) {
        for (const connectionSpec of particle.spec.handleConnections) {
          if (MatchRecipeByVerb.connectionSpecMatchesConstraint(connectionSpec, handleConstraint)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  static satisfiesHandleConnection(recipe: Recipe, handleName: string, handleConstraint: HandleConstraint) {
    for (const particle of recipe.particles) {
      if (particle.connections[handleName]) {
        if (MatchRecipeByVerb.connectionMatchesConstraint(
                particle.connections[handleName], handleConstraint)) {
          return true;
        }
      } else if (particle.spec && particle.spec.getConnectionByName(handleName)) {
        if (MatchRecipeByVerb.connectionSpecMatchesConstraint(
            particle.spec.getConnectionByName(handleName), handleConstraint)) {
          return true;
        }
      }
    }
    return false;
  }

  static connectionSpecMatchesConstraint(connSpec: HandleConnectionSpec, handleConstraint: HandleConstraint): boolean {
    if (connSpec.direction !== handleConstraint.direction) {
      return false;
    }
    return true;
  }
  static connectionMatchesConstraint(connection: {direction: Direction}, handleConstraint: HandleConstraint): boolean {
    if (connection.direction !== handleConstraint.direction) {
      return false;
    }
    if (!handleConstraint.handle) {
      return true;
    }
    const connections: {type?: Type, direction?: Direction}[] = [...handleConstraint.handle.connections, connection];
    return Boolean(effectiveTypeForHandle(handleConstraint.handle.mappedType, connections));
  }

  static satisfiesSlotConstraints(recipe: Recipe, slotConstraints: Dictionary<SlotConstraint>): boolean {
    for (const slotName in slotConstraints) {
      if (!MatchRecipeByVerb.satisfiesSlotConnection(
              recipe, slotName, slotConstraints[slotName])) {
        return false;
      }
    }
    return true;
  }

  static satisfiesSlotConnection(recipe: Recipe, slotName: string, constraints: SlotConstraint): boolean {
    for (const particle of recipe.particles) {
      if (!particle.spec) continue;
      if (MatchRecipeByVerb.slotsMatchConstraint(particle, slotName, constraints)) {
        return true;
      }
    }
    return false;
  }

  // Returns true when:
  //  - [particle] has a slotSpec called [name]; and
  //  - [particle] doesn't have a slot called [name] that is mapped to a targetSlot (if [constraints] has a targetSlot); and
  //  - all of the [providedSlots] named in [constraints] have a matching providedSlotConnection in the slotSpec called [name].
  static slotsMatchConstraint(particle: Particle, name: string, constraints: SlotConstraint): boolean {
    const slotSpec = particle.spec.slotConnections.get(name);
    if (slotSpec == null) {
      return false;
    }
    const slotConn = particle.getSlotConnectionByName(name);
    if (slotConn && slotConn.targetSlot && constraints.targetSlot) {
      return false;
    }
    for (const slot of constraints.providedSlots) {
      if (slotSpec.provideSlotConnections.find(spec => spec.name === slot.name) === undefined) {
        return false;
      }
    }
    return true;
  }
}
