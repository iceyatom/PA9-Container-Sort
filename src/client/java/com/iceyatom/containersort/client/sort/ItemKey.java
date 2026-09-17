package com.iceyatom.containersort.client.sort;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * Merge/sort key for a stack: value-equality is "same item + same components", so a
 * renamed, enchanted, or otherwise component-bearing stack never merges with the plain
 * base item (FR-10). Also carries the strings the sort comparators order by:
 * the displayed name in the client's active locale (FR-07) and the registry ID
 * (FR-08 / ITEM_ID mode).
 */
public final class ItemKey {
	private final ItemStack prototype;
	private final int hash;
	private final String displayName;
	private final String registryId;

	public ItemKey(ItemStack stack) {
		this.prototype = stack.copyWithCount(1);
		this.hash = ItemStack.hashItemAndComponents(this.prototype);
		this.displayName = stack.getHoverName().getString();
		this.registryId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
	}

	public ItemStack prototype() {
		return prototype;
	}

	public String displayName() {
		return displayName;
	}

	public String registryId() {
		return registryId;
	}

	/** True when the given live stack is the same item with the same components. */
	public boolean matchesStack(ItemStack stack) {
		return ItemStack.isSameItemSameComponents(prototype, stack);
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ItemKey other && ItemStack.isSameItemSameComponents(prototype, other.prototype);
	}

	@Override
	public int hashCode() {
		return hash;
	}

	@Override
	public String toString() {
		return registryId + "('" + displayName + "')";
	}
}
