package com.iceyatom.containersort.client.sort;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iceyatom.containersort.client.config.ContainerSortConfig;
import com.iceyatom.containersort.client.config.SortMode;
import com.iceyatom.containersort.client.sort.ContainerSortHelper.Plan;
import com.iceyatom.containersort.client.sort.ContainerSortHelper.StackInfo;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.ShulkerBoxScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Game-side orchestration: decides which screens qualify (FR-12–FR-15), snapshots the
 * pre-sort arrangement for undo (FR-16), builds the abstract slot model handed to the
 * pure planner, and starts the {@link SortExecutor}.
 */
public final class SortManager {
	private static final Logger LOGGER = LoggerFactory.getLogger("containersort");
	/** Vanilla handler layout: the player's 27 inventory + 9 hotbar slots come last. */
	private static final int PLAYER_SLOTS = 36;

	private static Snapshot lastSnapshot;

	private SortManager() {
	}

	private record Snapshot(int containerId, List<ItemStack> stacks) {
	}

	/**
	 * True for plain-storage container screens only: chests (single/double/trapped),
	 * barrels and ender chests all use {@link ContainerScreen}, shulker boxes use
	 * {@link ShulkerBoxScreen}. Detection is by screen class, never by display name
	 * (FR-14); functional containers (furnace, hopper, brewing stand, ...) use other
	 * screen classes and are therefore excluded (FR-13).
	 */
	public static boolean isSupportedScreen(Screen screen) {
		return screen instanceof ContainerScreen || screen instanceof ShulkerBoxScreen;
	}

	/** The number of slots belonging to the container itself (player inventory excluded). */
	public static int containerSlotCount(AbstractContainerMenu menu) {
		return Math.max(0, menu.slots.size() - PLAYER_SLOTS);
	}

	/** The number of occupied container slots (FR-02's "two or more stacks" check). */
	public static int occupiedSlotCount(AbstractContainerMenu menu) {
		int n = containerSlotCount(menu);
		int occupied = 0;
		for (int i = 0; i < n; i++) {
			if (!menu.getSlot(i).getItem().isEmpty()) {
				occupied++;
			}
		}
		return occupied;
	}

	/** Whether the sort trigger (button or keybind) applies to this screen right now. */
	public static boolean canSort(Screen screen) {
		ContainerSortConfig cfg = ContainerSortConfig.get();
		if (!cfg.enabled || !isSupportedScreen(screen)) {
			return false;
		}
		AbstractContainerMenu menu = ((AbstractContainerScreen<?>) screen).getMenu();
		return containerSlotCount(menu) >= cfg.minSlotThreshold
				&& occupiedSlotCount(menu) >= 2
				&& !SortExecutor.isBusy();
	}

	/** Plans and begins a sort of the container shown by the given screen (FR-03). */
	public static void trySort(AbstractContainerScreen<?> screen) {
		ContainerSortConfig cfg = ContainerSortConfig.get();
		if (!canSort(screen)) {
			return;
		}
		AbstractContainerMenu menu = screen.getMenu();
		try {
			int n = containerSlotCount(menu);
			List<StackInfo> state = readContainer(menu, n);
			Plan plan = ContainerSortHelper.computeSortPlan(
					state, cfg.mergePartialStacks, comparatorFor(cfg.sortMode));
			if (plan == null) {
				LOGGER.warn("[ContainerSort] No safe sort plan exists for this container (it may be completely full); leaving it untouched");
				return;
			}
			if (plan.clicks().isEmpty()) {
				if (cfg.debugLogging) {
					LOGGER.info("[ContainerSort] Container already sorted; nothing to do");
				}
				return;
			}
			if (cfg.enableUndo) {
				List<ItemStack> copies = new ArrayList<>(n);
				for (int i = 0; i < n; i++) {
					copies.add(menu.getSlot(i).getItem().copy());
				}
				lastSnapshot = new Snapshot(menu.containerId, copies);
			}
			if (cfg.debugLogging) {
				LOGGER.info("[ContainerSort] Planned {} clicks for {} container slots", plan.clicks().size(), n);
			}
			SortExecutor.start(menu, plan.clicks());
		} catch (Exception e) {
			// NFR-04: never let a planning error escape into the screen/input pipeline.
			LOGGER.warn("[ContainerSort] Failed to plan sort; container left untouched", e);
		}
	}

	/**
	 * Restores the most recent pre-sort snapshot, valid only while the same container
	 * screen remains open (FR-17).
	 *
	 * @return a user-facing error, or {@code null} on success
	 */
	public static String tryUndo(Screen screen) {
		if (!ContainerSortConfig.get().enableUndo) {
			return "Undo is disabled in the config";
		}
		if (!(screen instanceof AbstractContainerScreen<?> containerScreen) || !isSupportedScreen(screen)) {
			return "Open the sorted container first";
		}
		AbstractContainerMenu menu = containerScreen.getMenu();
		Snapshot snapshot = lastSnapshot;
		if (snapshot == null) {
			return "No sort snapshot exists";
		}
		// Typing a command closes the container screen, so the snapshot is also accepted
		// for a reopened container: the restore plan below verifies the contents are
		// still compatible, which is what actually protects against the wrong chest.
		if (SortExecutor.isBusy()) {
			return "A sort is still running";
		}
		try {
			int n = containerSlotCount(menu);
			if (snapshot.stacks().size() != n) {
				return "The container layout changed since the sort";
			}
			List<StackInfo> current = readContainer(menu, n);
			List<StackInfo> target = new ArrayList<>(n);
			for (ItemStack stack : snapshot.stacks()) {
				target.add(toStackInfo(stack));
			}
			Plan plan = ContainerSortHelper.computeRestorePlan(current, target);
			if (plan == null) {
				return "The container contents changed too much to undo";
			}
			lastSnapshot = null;
			SortExecutor.start(menu, plan.clicks());
			return null;
		} catch (Exception e) {
			LOGGER.warn("[ContainerSort] Failed to plan undo; container left untouched", e);
			return "Undo failed; see the log";
		}
	}

	private static List<StackInfo> readContainer(AbstractContainerMenu menu, int slotCount) {
		List<StackInfo> state = new ArrayList<>(slotCount);
		for (int i = 0; i < slotCount; i++) {
			state.add(toStackInfo(menu.getSlot(i).getItem()));
		}
		return state;
	}

	private static StackInfo toStackInfo(ItemStack stack) {
		if (stack.isEmpty()) {
			return StackInfo.EMPTY;
		}
		return new StackInfo(new ItemKey(stack), stack.getCount(), stack.getMaxStackSize());
	}

	/**
	 * Ordering over stacks per the configured mode. ALPHABETICAL uses a locale-aware
	 * collation of the displayed name with the registry ID as a deterministic tiebreak
	 * (FR-07/FR-08); ITEM_ID is fully locale-independent; COUNT_DESC orders by stack
	 * count first. All modes end with a component-hash tiebreak so distinct variants of
	 * the same item (FR-10) get a stable relative order, and same-key stacks are placed
	 * full-first.
	 */
	private static Comparator<StackInfo> comparatorFor(SortMode mode) {
		Collator collator = Collator.getInstance();
		Comparator<ItemKey> byName = Comparator.comparing(ItemKey::displayName, collator);
		Comparator<ItemKey> byId = Comparator.comparing(ItemKey::registryId);
		Comparator<ItemKey> byHash = Comparator.comparingInt(ItemKey::hashCode);
		Comparator<ItemKey> keyCmp = switch (mode) {
			case ALPHABETICAL, COUNT_DESC -> byName.thenComparing(byId).thenComparing(byHash);
			case ITEM_ID -> byId.thenComparing(byName).thenComparing(byHash);
		};
		Comparator<StackInfo> byKey = Comparator.comparing(s -> (ItemKey) s.key(), keyCmp);
		Comparator<StackInfo> byCountDesc = Comparator.comparingInt(StackInfo::count).reversed();
		return mode == SortMode.COUNT_DESC
				? byCountDesc.thenComparing(byKey)
				: byKey.thenComparing(byCountDesc);
	}
}
