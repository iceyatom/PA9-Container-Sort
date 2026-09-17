package com.iceyatom.containersort.client.sort;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.iceyatom.containersort.client.config.ContainerSortConfig;
import com.iceyatom.containersort.client.sort.ContainerSortHelper.Click;
import com.iceyatom.containersort.client.sort.ContainerSortHelper.StackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * Executes a planned click sequence against the live container, one standard
 * {@code ContainerInput.PICKUP} click at a time (PKT-01/PKT-02), spaced by the
 * configured {@code click_delay_ms} throttle (PKT-03/NFR-03).
 *
 * <p>Before each click the live slot and cursor contents are reconciled against the
 * planner's expectations; on mismatch the executor waits briefly (the server may not
 * have confirmed the previous click yet) and then aborts the remaining sequence with a
 * warning rather than continuing from a desynchronised state (PKT-04, NFR-04). The
 * sequence is also aborted the moment the container screen closes (FR-18).
 */
public final class SortExecutor {
	private static final Logger LOGGER = LoggerFactory.getLogger("containersort");
	/** How long a reconciliation mismatch may persist before the sort is aborted. */
	private static final long RECONCILE_TIMEOUT_MS = 1500;

	private static ActiveSort active;

	private SortExecutor() {
	}

	private static final class ActiveSort {
		final AbstractContainerMenu menu;
		final List<Click> clicks;
		int next;
		long lastClickAt;
		long mismatchSince = -1;

		ActiveSort(AbstractContainerMenu menu, List<Click> clicks) {
			this.menu = menu;
			this.clicks = clicks;
		}
	}

	public static boolean isBusy() {
		return active != null;
	}

	/** Begins executing a plan against the given (already open) menu. */
	public static void start(AbstractContainerMenu menu, List<Click> clicks) {
		if (clicks.isEmpty()) {
			return;
		}
		active = new ActiveSort(menu, clicks);
		if (ContainerSortConfig.get().debugLogging) {
			LOGGER.info("[ContainerSort] Executing plan: {} clicks on container {}", clicks.size(), menu.containerId);
		}
	}

	/** Aborts the in-flight sequence if it targets the given menu (FR-18). */
	public static void abortFor(AbstractContainerMenu menu, String reason) {
		if (active != null && active.menu == menu) {
			abort(reason);
		}
	}

	/** Aborts any in-flight sequence (screen closed, disconnect, config disable, ...). */
	public static void abort(String reason) {
		if (active != null) {
			LOGGER.warn("[ContainerSort] Sort aborted after {}/{} clicks: {}",
					active.next, active.clicks.size(), reason);
			active = null;
		}
	}

	/** Driven every client tick; sends due clicks and reconciles state (FR-18/PKT-04). */
	public static void tick(Minecraft minecraft) {
		ActiveSort sort = active;
		if (sort == null) {
			return;
		}
		if (minecraft.player == null || minecraft.gameMode == null) {
			abort("player left the world mid-sort");
			return;
		}
		if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen)
				|| screen.getMenu() != sort.menu
				|| minecraft.player.containerMenu != sort.menu) {
			abort("container screen closed mid-sort");
			return;
		}
		long now = System.currentTimeMillis();
		int delay = ContainerSortConfig.get().clickDelayMs;
		// With a zero delay the whole remainder is sent this tick; otherwise one click
		// is sent per tick once the throttle interval has elapsed.
		while (sort.next < sort.clicks.size()) {
			if (now - sort.lastClickAt < delay) {
				return;
			}
			Click click = sort.clicks.get(sort.next);
			if (!reconcile(sort, click, now)) {
				return; // waiting on the server, or aborted inside reconcile()
			}
			minecraft.gameMode.handleContainerInput(
					sort.menu.containerId, click.slot(), click.button(),
					ContainerInput.PICKUP, minecraft.player);
			sort.next++;
			sort.lastClickAt = now;
			sort.mismatchSince = -1;
			if (delay > 0) {
				return;
			}
		}
		if (ContainerSortConfig.get().debugLogging) {
			LOGGER.info("[ContainerSort] Plan complete: {} clicks sent", sort.clicks.size());
		}
		active = null;
	}

	/**
	 * Verifies that the live slot and cursor match what the planner expects before the
	 * next click. Transient mismatches are tolerated up to {@link #RECONCILE_TIMEOUT_MS}
	 * (a server running a strictly server-authoritative container may confirm clicks a
	 * few ticks late); persistent mismatches abort the sequence (PKT-04).
	 */
	private static boolean reconcile(ActiveSort sort, Click click, long now) {
		ItemStack slotStack = sort.menu.getSlot(click.slot()).getItem();
		ItemStack cursorStack = sort.menu.getCarried();
		boolean ok = matches(click.expectedSlotBefore(), slotStack)
				&& matches(click.expectedCursorBefore(), cursorStack);
		if (ok) {
			return true;
		}
		if (sort.mismatchSince < 0) {
			sort.mismatchSince = now;
			return false;
		}
		if (now - sort.mismatchSince >= RECONCILE_TIMEOUT_MS) {
			abort("server state no longer matches the plan (slot " + click.slot() + ")");
		}
		return false;
	}

	private static boolean matches(StackInfo expected, ItemStack actual) {
		if (expected.isEmpty()) {
			return actual.isEmpty();
		}
		return !actual.isEmpty()
				&& actual.getCount() == expected.count()
				&& expected.key() instanceof ItemKey key
				&& key.matchesStack(actual);
	}
}
