package com.iceyatom.containersort.client.sort;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure, game-independent sort planner (NFR-13).
 *
 * <p>Given an abstract snapshot of a container's slots, this class computes the exact
 * sequence of vanilla {@code PICKUP} slot clicks (left- and right-button) that
 * transforms the container into its consolidated (FR-06) and ordered
 * (FR-07/FR-08/FR-09) arrangement, or back into a previously snapshotted arrangement
 * (FR-16/FR-17). Every click is simulated with vanilla pickup/place/deposit/swap
 * semantics, and a final verification pass asserts that the plan reaches the target
 * and conserves every item (NFR-07) with an empty cursor. If any invariant fails the
 * planner returns {@code null} instead of a plan (NFR-04).
 *
 * <p>The class has no Minecraft imports, which keeps it unit-testable without a
 * running game instance. Stacks are represented by {@link StackInfo}, whose
 * {@code key} is an opaque object with value-equality over
 * "same item + same components" (FR-10).
 */
public final class ContainerSortHelper {
	private ContainerSortHelper() {
	}

	/**
	 * Immutable view of one slot. {@code key == null} means the slot is empty.
	 * Two stacks may be merged only when their keys are {@link Object#equals equal}.
	 */
	public record StackInfo(Object key, int count, int maxCount) {
		public static final StackInfo EMPTY = new StackInfo(null, 0, 64);

		public boolean isEmpty() {
			return key == null || count <= 0;
		}

		public boolean sameContents(StackInfo other) {
			if (isEmpty() || other.isEmpty()) {
				return isEmpty() && other.isEmpty();
			}
			return count == other.count && key.equals(other.key);
		}
	}

	/**
	 * One planned click ({@code button} 0 = left, 1 = right), together with the slot and
	 * cursor contents the planner expects immediately <em>before</em> the click is sent.
	 * The executor reconciles these expectations against the live menu after every click
	 * (PKT-04) and aborts the remaining sequence on mismatch.
	 */
	public record Click(int slot, int button, StackInfo expectedSlotBefore, StackInfo expectedCursorBefore) {
	}

	/** A complete, verified plan plus the arrangement it produces. */
	public record Plan(List<Click> clicks, List<StackInfo> resultState) {
	}

	/**
	 * Computes the click plan that consolidates and orders the given container slots.
	 *
	 * @param initial snapshot of the container's own slots (player inventory excluded)
	 * @param merge   whether to consolidate partial stacks first (FR-06)
	 * @param order   total, deterministic ordering over non-empty stacks (FR-07/FR-08)
	 * @return the plan, or {@code null} if no safe plan exists (NFR-04)
	 */
	public static Plan computeSortPlan(List<StackInfo> initial, boolean merge, Comparator<StackInfo> order) {
		try {
			Sim sim = new Sim(initial);
			if (merge) {
				mergePhase(sim);
			}
			List<StackInfo> target = computeTarget(sim.slots, order);
			arrangePhase(sim, target);
			verify(sim, initial, target);
			return new Plan(sim.clicks, List.copyOf(sim.slots));
		} catch (IllegalStateException e) {
			if (Boolean.getBoolean("containersort.plannerDebug")) {
				e.printStackTrace();
			}
			return null;
		}
	}

	/**
	 * Computes the click plan that restores an exact previous arrangement (FR-17 undo),
	 * including re-splitting stacks that a sort consolidated (via right-click placement).
	 * The current and target snapshots must contain the same items in the same totals.
	 *
	 * @return the plan, or {@code null} if the states are incompatible or no safe plan exists
	 */
	public static Plan computeRestorePlan(List<StackInfo> current, List<StackInfo> target) {
		if (current.size() != target.size() || !multisetEquals(current, target)) {
			return null;
		}
		try {
			Sim sim = new Sim(current);
			directRedistribute(sim, target);
			splitPhase(sim, target);
			// Splitting settles counts but not positions; a second in-place pass fixes
			// same-key count cycles the permutation phase cannot resolve (same-key
			// stacks never swap with a left-click).
			cycleRedistribute(sim, target);
			arrangePhase(sim, target);
			verify(sim, current, target);
			return new Plan(sim.clicks, List.copyOf(sim.slots));
		} catch (IllegalStateException e) {
			if (Boolean.getBoolean("containersort.plannerDebug")) {
				e.printStackTrace();
			}
			return null;
		}
	}

	// ------------------------------------------------------------------
	// Phase 1 (sort): consolidate partial stacks (FR-06)
	// ------------------------------------------------------------------

	/**
	 * Merges partial stacks per key: the lowest-indexed partials are filled from the
	 * highest-indexed ones until at most one partial stack per key remains.
	 */
	private static void mergePhase(Sim sim) {
		Map<Object, List<Integer>> byKey = new HashMap<>();
		for (int i = 0; i < sim.slots.size(); i++) {
			StackInfo s = sim.slots.get(i);
			if (!s.isEmpty() && s.maxCount() > 1) {
				byKey.computeIfAbsent(s.key(), k -> new ArrayList<>()).add(i);
			}
		}
		for (List<Integer> indices : byKey.values()) {
			if (indices.size() < 2) {
				continue;
			}
			int i = 0;
			int j = indices.size() - 1;
			while (i < j) {
				int fillIdx = indices.get(i);
				int srcIdx = indices.get(j);
				StackInfo fill = sim.slots.get(fillIdx);
				StackInfo src = sim.slots.get(srcIdx);
				if (fill.isEmpty() || fill.count() >= fill.maxCount()) {
					i++;
					continue;
				}
				if (src.isEmpty()) {
					j--;
					continue;
				}
				sim.leftClick(srcIdx);   // pick up the source stack
				sim.leftClick(fillIdx);  // deposit into the partial stack
				if (!sim.cursor.isEmpty()) {
					sim.leftClick(srcIdx); // return the remainder to the (now empty) source slot
				} else {
					j--;
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Phase 0 (restore): position-aware same-key count fixing
	// ------------------------------------------------------------------

	/**
	 * For slots whose current and target contents are the same key but different
	 * counts, transfers the surplus directly into a same-key slot that needs it,
	 * one right-click per item. This settles same-key count differences in place,
	 * which is the only way to fix them when the container has no empty slot to
	 * re-route through (a left-click between same-key stacks merges, never swaps).
	 */
	private static void directRedistribute(Sim sim, List<StackInfo> target) {
		int n = sim.slots.size();
		int guard = 0;
		boolean progress = true;
		while (progress && ++guard <= 4 * n) {
			progress = false;
			for (int i = 0; i < n; i++) {
				StackInfo cur = sim.slots.get(i);
				StackInfo tgt = target.get(i);
				if (cur.isEmpty() || tgt.isEmpty() || !cur.key().equals(tgt.key())
						|| cur.count() <= tgt.count()) {
					continue;
				}
				for (int j = 0; j < n; j++) {
					StackInfo cj = sim.slots.get(j);
					StackInfo tj = target.get(j);
					if (j == i || tj.isEmpty() || !tj.key().equals(cur.key())) {
						continue;
					}
					// Destination: an empty slot that wants this key, or a same-key stack
					// short of its own target count. Only transfers that settle BOTH
					// endpoints exactly are performed — partial transfers would create
					// stack counts that exist in neither the current nor the target
					// arrangement and strand the later phases.
					int have = cj.isEmpty() ? 0 : cj.count();
					if (!cj.isEmpty() && (!cj.key().equals(cur.key()) || have >= cj.maxCount())) {
						continue;
					}
					if (have >= tj.count()) {
						continue;
					}
					int moved = cur.count() - tgt.count();
					if (moved != tj.count() - have) {
						continue;
					}
					sim.leftClick(i);
					for (int k = 0; k < moved; k++) {
						sim.rightClick(j);
					}
					sim.leftClick(i); // return the rest; never empty because tgt.count() > 0
					progress = true;
					break;
				}
			}
		}
	}

	/**
	 * Resolves same-key count cycles among "positional" slots — slots whose current
	 * and target stacks share a key but differ in count. Left-clicks can never swap
	 * same-key stacks (they merge), so these cycles are settled in place by moving
	 * items one right-click at a time from a surplus slot into a deficit slot. Only
	 * keys whose positional surpluses and deficits balance exactly are touched: for
	 * those the greedy transfers converge with every endpoint exactly on target,
	 * which keeps the overall stack multiset intact.
	 */
	private static void cycleRedistribute(Sim sim, List<StackInfo> target) {
		int n = sim.slots.size();
		// A key participates only when its positional subgroup (unsettled slots where
		// the current and target stacks share that key) is a pure permutation: the
		// subgroup's current counts and target counts must form the same multiset.
		// Then in-place transfers settle every one of those slots exactly, and the
		// container-wide stack multiset is preserved.
		Map<Object, Boolean> permutable = new HashMap<>();
		Map<Object, Map<Integer, Integer>> curCounts = new HashMap<>();
		Map<Object, Map<Integer, Integer>> tgtCounts = new HashMap<>();
		for (int i = 0; i < n; i++) {
			StackInfo cur = sim.slots.get(i);
			StackInfo tgt = target.get(i);
			if (!cur.isEmpty() && !tgt.isEmpty() && cur.key().equals(tgt.key())
					&& cur.count() != tgt.count()) {
				curCounts.computeIfAbsent(cur.key(), k -> new HashMap<>()).merge(cur.count(), 1, Integer::sum);
				tgtCounts.computeIfAbsent(cur.key(), k -> new HashMap<>()).merge(tgt.count(), 1, Integer::sum);
			}
		}
		for (Object key : curCounts.keySet()) {
			permutable.put(key, curCounts.get(key).equals(tgtCounts.get(key)));
		}
		int guard = 0;
		boolean progress = true;
		while (progress && ++guard <= 4 * n) {
			progress = false;
			for (int i = 0; i < n; i++) {
				StackInfo cur = sim.slots.get(i);
				StackInfo tgt = target.get(i);
				if (cur.isEmpty() || tgt.isEmpty() || !cur.key().equals(tgt.key())
						|| cur.count() <= tgt.count()
						|| !permutable.getOrDefault(cur.key(), false)) {
					continue;
				}
				for (int j = 0; j < n; j++) {
					StackInfo cj = sim.slots.get(j);
					StackInfo tj = target.get(j);
					if (j == i || cj.isEmpty() || tj.isEmpty()
							|| !cj.key().equals(cur.key()) || !tj.key().equals(cur.key())
							|| cj.count() >= tj.count() || cj.count() >= cj.maxCount()) {
						continue;
					}
					int moved = Math.min(cur.count() - tgt.count(),
							Math.min(tj.count() - cj.count(), cj.maxCount() - cj.count()));
					if (moved <= 0) {
						continue;
					}
					sim.leftClick(i);
					for (int k = 0; k < moved; k++) {
						sim.rightClick(j);
					}
					sim.leftClick(i); // return the rest; never empty because tgt.count() > 0
					progress = true;
					break;
				}
			}
		}
	}

	// ------------------------------------------------------------------
	// Phase 1 (restore): recreate the exact per-stack counts of the target
	// ------------------------------------------------------------------

	/**
	 * Makes the multiset of (key, count) stacks equal to the target's, combining stacks
	 * with left-click deposits and splitting them with single right-click placements.
	 * After this phase the arrangement phase only ever needs to permute whole stacks.
	 */
	private static void splitPhase(Sim sim, List<StackInfo> target) {
		Map<Object, List<Integer>> targetCounts = new HashMap<>();
		for (StackInfo t : target) {
			if (!t.isEmpty()) {
				targetCounts.computeIfAbsent(t.key(), k -> new ArrayList<>()).add(t.count());
			}
		}
		for (Map.Entry<Object, List<Integer>> entry : targetCounts.entrySet()) {
			Object key = entry.getKey();
			List<Integer> needed = new ArrayList<>(entry.getValue());
			needed.sort(Comparator.reverseOrder());
			// Pool: unsettled slots currently holding this key. Slots whose count already
			// matches a needed value are settled greedily and removed from both lists.
			List<Integer> pool = new ArrayList<>();
			for (int i = 0; i < sim.slots.size(); i++) {
				StackInfo s = sim.slots.get(i);
				if (!s.isEmpty() && s.key().equals(key)) {
					pool.add(i);
				}
			}
			int guard = 0;
			while (!needed.isEmpty()) {
				if (++guard > 16 * sim.slots.size() + 64) {
					throw new IllegalStateException("split guard tripped");
				}
				// Settle every pool stack whose count already matches an unmet need.
				boolean settled = false;
				for (int idx : List.copyOf(pool)) {
					int pos = needed.indexOf(sim.slots.get(idx).count());
					if (pos >= 0) {
						pool.remove(Integer.valueOf(idx));
						needed.remove(pos);
						settled = true;
					}
				}
				if (settled || needed.isEmpty()) {
					continue;
				}
				int c = needed.get(0); // largest unmet count
				// Combine pool stacks until one holds at least c items.
				if (largestCount(sim, pool) < c) {
					combineSmallestIntoLargest(sim, pool);
					continue;
				}
				int donor = smallestAtLeast(sim, pool, c); // strictly > c: exacts settled above
				// Prefer splitting straight into an empty slot whose target is exactly
				// the stack being created — it is then already in position, which keeps
				// the arrangement phase from needing scratch space later.
				int scratch = -1;
				for (int k = 0; k < sim.slots.size(); k++) {
					StackInfo tk = target.get(k);
					if (sim.slots.get(k).isEmpty() && !tk.isEmpty()
							&& tk.key().equals(key) && tk.count() == c) {
						scratch = k;
						break;
					}
				}
				if (scratch < 0) {
					scratch = findEmptySlot(sim, -1);
				}
				if (scratch >= 0) {
					// Split c items off the donor into an empty slot, one right-click each.
					sim.leftClick(donor);
					for (int k = 0; k < c; k++) {
						sim.rightClick(scratch);
					}
					sim.leftClick(donor); // return the remainder
					pool.add(scratch);
				} else {
					// No empty slot to split through: shed the donor's excess into another
					// same-key stack that still has head-room.
					int recipient = -1;
					for (int idx : pool) {
						StackInfo s = sim.slots.get(idx);
						if (idx != donor && s.count() < s.maxCount()) {
							recipient = idx;
							break;
						}
					}
					if (recipient < 0) {
						throw new IllegalStateException("no room to redistribute counts");
					}
					StackInfo d = sim.slots.get(donor);
					StackInfo r = sim.slots.get(recipient);
					int moved = Math.min(d.count() - c, r.maxCount() - r.count());
					sim.leftClick(donor);
					for (int k = 0; k < moved; k++) {
						sim.rightClick(recipient);
					}
					sim.leftClick(donor); // return what is left (>= c, so never empty)
				}
			}
		}
	}

	private static int largestCount(Sim sim, List<Integer> pool) {
		int best = 0;
		for (int idx : pool) {
			best = Math.max(best, sim.slots.get(idx).count());
		}
		return best;
	}

	private static int smallestAtLeast(Sim sim, List<Integer> pool, int count) {
		int bestIdx = -1;
		int bestCount = Integer.MAX_VALUE;
		for (int idx : pool) {
			int cur = sim.slots.get(idx).count();
			if (cur >= count && cur < bestCount) {
				bestCount = cur;
				bestIdx = idx;
			}
		}
		if (bestIdx < 0) {
			throw new IllegalStateException("no donor stack large enough");
		}
		return bestIdx;
	}

	private static void combineSmallestIntoLargest(Sim sim, List<Integer> pool) {
		int smallIdx = -1;
		int small = Integer.MAX_VALUE;
		for (int idx : pool) {
			StackInfo s = sim.slots.get(idx);
			if (s.count() < s.maxCount() && s.count() < small) {
				small = s.count();
				smallIdx = idx;
			}
		}
		int largeIdx = -1;
		int large = -1;
		for (int idx : pool) {
			StackInfo s = sim.slots.get(idx);
			if (idx != smallIdx && s.count() < s.maxCount() && s.count() > large) {
				large = s.count();
				largeIdx = idx;
			}
		}
		if (smallIdx < 0 || largeIdx < 0) {
			throw new IllegalStateException("cannot combine stacks further");
		}
		sim.leftClick(smallIdx);
		sim.leftClick(largeIdx);
		if (!sim.cursor.isEmpty()) {
			sim.leftClick(smallIdx); // return the remainder to the emptied source slot
		} else {
			pool.remove(Integer.valueOf(smallIdx));
		}
	}

	// ------------------------------------------------------------------
	// Target arrangement for sorting (FR-07/FR-08/FR-09)
	// ------------------------------------------------------------------

	/**
	 * Builds the desired final arrangement: all stacks in the supplied order,
	 * empties at the end (FR-09).
	 */
	private static List<StackInfo> computeTarget(List<StackInfo> slots, Comparator<StackInfo> order) {
		List<StackInfo> stacks = new ArrayList<>();
		for (StackInfo s : slots) {
			if (!s.isEmpty()) {
				stacks.add(s);
			}
		}
		stacks.sort(order);
		List<StackInfo> target = new ArrayList<>(stacks);
		while (target.size() < slots.size()) {
			target.add(StackInfo.EMPTY);
		}
		return target;
	}

	// ------------------------------------------------------------------
	// Phase 2: cycle-based rearrangement (FR-11)
	// ------------------------------------------------------------------

	/**
	 * Permutes whole stacks into their target slots by walking displacement chains:
	 * pick up the stack that belongs in the first unsettled slot, place it (displacing
	 * any different-keyed occupant onto the cursor), and keep placing the cursor into a
	 * slot that needs it until the cursor empties. Each misplaced stack is touched a
	 * bounded number of times — a cycle-minimal ordering rather than a naive full
	 * pairwise swap (FR-11). Same-key deposits are never generated here, because a
	 * left-click of a stack onto a partial stack of the same item merges instead of
	 * swapping; when a chain would require one, the cursor is parked in an empty slot
	 * and the chain re-routed. If no empty slot exists for such a re-route, planning
	 * fails and the caller leaves the container untouched.
	 */
	private static void arrangePhase(Sim sim, List<StackInfo> target) {
		int n = sim.slots.size();
		long[] guard = {0};
		// Priority pass: vacate every "blocked" slot — one whose occupant shares a key
		// with the slot's (different) target stack. Such a slot can never be settled by
		// a direct placement, because the incoming same-key stack would merge with the
		// occupant instead of swapping; the occupant must leave first. Blocked slots
		// whose occupant has an immediate clean destination are handled first, since
		// resolving them usually settles the whole displacement cycle they belong to.
		while (true) {
			if (++guard[0] > 16L * n + 32) {
				throw new IllegalStateException("arrangement guard tripped");
			}
			// Same-key count cycles can emerge as chains park stacks; they can only be
			// resolved in place with right-click transfers, never by swapping.
			cycleRedistribute(sim, target);
			int fallback = -1;
			int start = -1;
			for (int j = 0; j < n; j++) {
				StackInfo cur = sim.slots.get(j);
				StackInfo tgt = target.get(j);
				if (cur.isEmpty() || cur.sameContents(tgt) || !sameKey(cur, tgt)) {
					continue;
				}
				if (hasCleanDestinationFor(sim, target, cur)) {
					start = j;
					break;
				}
				if (fallback < 0 && findEmptySlot(sim, j) >= 0) {
					fallback = j;
				}
			}
			if (start < 0) {
				start = fallback;
			}
			if (start < 0) {
				break; // no blocked slots remain (or none can move — main loop decides)
			}
			sim.leftClick(start);
			routeCursor(sim, target, -1, guard);
		}
		for (int i = 0; i < n; i++) {
			while (!sim.slots.get(i).sameContents(target.get(i))) {
				if (++guard[0] > 16L * n + 32) {
					throw new IllegalStateException("arrangement guard tripped");
				}
				cycleRedistribute(sim, target);
				if (sim.slots.get(i).sameContents(target.get(i))) {
					break;
				}
				StackInfo occupant = sim.slots.get(i);
				if (!occupant.isEmpty()
						&& (target.get(i).isEmpty() || sameKey(occupant, target.get(i)))) {
					// The occupant must leave before slot i can settle.
					sim.leftClick(i);
					routeCursor(sim, target, i, guard);
					continue;
				}
				int src = findSlotWith(sim, target, target.get(i));
				if (src < 0) {
					throw new IllegalStateException("no source for slot " + i
							+ " wanting " + target.get(i) + "; state=" + sim.slots);
				}
				sim.leftClick(src);
				routeCursor(sim, target, i, guard);
			}
		}
	}

	/**
	 * Places the cursor stack until it is empty: into a slot it settles (preferring
	 * {@code preferred}), else parked into an empty slot. Parking back into the slot the
	 * cursor was just picked from is forbidden — that would be a no-op loop.
	 */
	private static void routeCursor(Sim sim, List<StackInfo> target, int preferred, long[] guard) {
		while (!sim.cursor.isEmpty()) {
			if (++guard[0] > 16L * sim.slots.size() + 32) {
				throw new IllegalStateException("arrangement guard tripped");
			}
			int dest = findCleanDestination(sim, target, preferred);
			if (dest >= 0) {
				sim.leftClick(dest);
				continue;
			}
			Click last = sim.clicks.get(sim.clicks.size() - 1);
			int forbid = last.expectedCursorBefore().isEmpty() ? last.slot() : -1;
			int park = findEmptySlot(sim, forbid);
			if (park < 0) {
				throw new IllegalStateException("no empty slot to re-route through");
			}
			sim.leftClick(park);
		}
	}

	/** Whether the given stack currently has a clean (settling) destination slot. */
	private static boolean hasCleanDestinationFor(Sim sim, List<StackInfo> target, StackInfo stack) {
		for (int k = 0; k < sim.slots.size(); k++) {
			StackInfo cur = sim.slots.get(k);
			if (cur.sameContents(target.get(k)) || !target.get(k).sameContents(stack)) {
				continue;
			}
			if (cur.isEmpty() || !cur.key().equals(stack.key())) {
				return true;
			}
		}
		return false;
	}

	private static boolean sameKey(StackInfo a, StackInfo b) {
		return !a.isEmpty() && !b.isEmpty() && a.key().equals(b.key());
	}

	/** Finds an unsettled slot whose current contents equal {@code wanted} exactly. */
	private static int findSlotWith(Sim sim, List<StackInfo> target, StackInfo wanted) {
		for (int j = 0; j < sim.slots.size(); j++) {
			StackInfo s = sim.slots.get(j);
			if (!s.sameContents(target.get(j)) && s.sameContents(wanted)) {
				return j;
			}
		}
		return -1;
	}

	/**
	 * Finds an unsettled slot that the cursor stack settles by direct placement:
	 * its target equals the cursor and it is empty or holds a different-keyed stack
	 * (which swaps onto the cursor). Prefers {@code preferred} so active chains close
	 * out the slot currently being worked on first.
	 */
	private static int findCleanDestination(Sim sim, List<StackInfo> target, int preferred) {
		int found = -1;
		for (int k = 0; k < sim.slots.size(); k++) {
			StackInfo cur = sim.slots.get(k);
			if (cur.sameContents(target.get(k)) || !target.get(k).sameContents(sim.cursor)) {
				continue;
			}
			if (!cur.isEmpty() && cur.key().equals(sim.cursor.key())) {
				continue; // would merge rather than swap
			}
			if (k == preferred) {
				return k;
			}
			if (found < 0) {
				found = k;
			}
		}
		return found;
	}

	private static int findEmptySlot(Sim sim, int exclude) {
		for (int k = 0; k < sim.slots.size(); k++) {
			if (k != exclude && sim.slots.get(k).isEmpty()) {
				return k;
			}
		}
		return -1;
	}

	// ------------------------------------------------------------------
	// Verification (NFR-06/NFR-07)
	// ------------------------------------------------------------------

	private static void verify(Sim sim, List<StackInfo> initial, List<StackInfo> target) {
		if (!sim.cursor.isEmpty()) {
			throw new IllegalStateException("plan ends with items on the cursor");
		}
		for (int i = 0; i < target.size(); i++) {
			if (!sim.slots.get(i).sameContents(target.get(i))) {
				throw new IllegalStateException("plan does not reach the target arrangement");
			}
		}
		if (!multisetEquals(initial, sim.slots)) {
			throw new IllegalStateException("plan changes the total item count");
		}
	}

	private static boolean multisetEquals(List<StackInfo> a, List<StackInfo> b) {
		return totalCounts(a).equals(totalCounts(b));
	}

	private static Map<Object, Long> totalCounts(List<StackInfo> slots) {
		Map<Object, Long> totals = new HashMap<>();
		for (StackInfo s : slots) {
			if (!s.isEmpty()) {
				totals.merge(s.key(), (long) s.count(), Long::sum);
			}
		}
		return totals;
	}

	// ------------------------------------------------------------------
	// Click simulator with vanilla PICKUP semantics
	// ------------------------------------------------------------------

	/**
	 * Simulates vanilla {@code PICKUP} clicks. Left click: empty cursor picks the slot
	 * up; cursor onto an empty slot places everything; cursor onto a same-key stack
	 * deposits up to the max stack size; cursor onto a different item swaps. Right
	 * click with a loaded cursor places exactly one item. Click patterns the planner
	 * should never generate (no-ops, ambiguous placements) throw instead of guessing.
	 */
	private static final class Sim {
		final List<StackInfo> slots;
		final List<Click> clicks = new ArrayList<>();
		StackInfo cursor = StackInfo.EMPTY;

		Sim(List<StackInfo> initial) {
			this.slots = new ArrayList<>(initial.size());
			for (StackInfo s : initial) {
				this.slots.add(s.isEmpty() ? StackInfo.EMPTY : s);
			}
		}

		void leftClick(int slot) {
			StackInfo s = slots.get(slot);
			clicks.add(new Click(slot, 0, s, cursor));
			if (cursor.isEmpty()) {
				if (s.isEmpty()) {
					throw new IllegalStateException("useless click: empty cursor on empty slot");
				}
				cursor = s;
				slots.set(slot, StackInfo.EMPTY);
			} else if (s.isEmpty()) {
				slots.set(slot, cursor);
				cursor = StackInfo.EMPTY;
			} else if (Objects.equals(s.key(), cursor.key())) {
				int space = s.maxCount() - s.count();
				int deposited = Math.min(space, cursor.count());
				if (deposited <= 0) {
					throw new IllegalStateException("useless click: deposit into full stack");
				}
				slots.set(slot, new StackInfo(s.key(), s.count() + deposited, s.maxCount()));
				cursor = deposited == cursor.count()
						? StackInfo.EMPTY
						: new StackInfo(cursor.key(), cursor.count() - deposited, cursor.maxCount());
			} else {
				slots.set(slot, cursor);
				cursor = s;
			}
		}

		void rightClick(int slot) {
			StackInfo s = slots.get(slot);
			clicks.add(new Click(slot, 1, s, cursor));
			if (cursor.isEmpty()) {
				throw new IllegalStateException("right-click pickup is not used by the planner");
			}
			if (s.isEmpty()) {
				slots.set(slot, new StackInfo(cursor.key(), 1, cursor.maxCount()));
			} else if (Objects.equals(s.key(), cursor.key()) && s.count() < s.maxCount()) {
				slots.set(slot, new StackInfo(s.key(), s.count() + 1, s.maxCount()));
			} else {
				throw new IllegalStateException("right-click place blocked");
			}
			cursor = cursor.count() == 1
					? StackInfo.EMPTY
					: new StackInfo(cursor.key(), cursor.count() - 1, cursor.maxCount());
		}
	}
}
