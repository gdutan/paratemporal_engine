package sandbox.fastcopy;

import com.ms4systems.devs.core.model.AtomicModel;

/**
 * MEASUREMENT PROTOTYPE (not part of the library). The opt-in copy hook proposed in
 * docs/engine/ParaDEVS_FastCopy_Proposal.tex.
 *
 * A model that implements this returns a new, independent model with the same state. It copies only
 * its OWN fields (for a coupled model: builds the same children and copies their fields); the engine
 * copies the MS4 bookkeeping (simulator event times, initialized flags) itself, see {@link Bookkeeping}.
 * Immutable data (tables, laws, strings) may be shared between the original and the copy.
 */
public interface ParaCopyable {
    AtomicModel paraCopy();
}
